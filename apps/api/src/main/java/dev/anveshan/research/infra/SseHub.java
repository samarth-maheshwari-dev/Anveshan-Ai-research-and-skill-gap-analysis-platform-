package dev.anveshan.research.infra;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.anveshan.research.infra.RunEventStore.EventRow;

/**
 * Live SSE connections per run (TECHSPEC §8.6, single instance — TS-D10). Pull-based: a "poke" makes each
 * connection read {@code run_event} rows after the last id it sent, under that connection's lock — replay on
 * connect and live pushes can't interleave, skip or duplicate an event.
 * <p>
 * Pushes run on virtual threads, never on the committing thread, and use a {@link ReentrantLock} (on JDK 21 a
 * {@code synchronized} block that blocks on I/O pins the carrier thread). Pokes are coalesced per stream: at most
 * one push runs and one waits, however many events a burst produces — a stalled browser costs one parked virtual
 * thread, not the server's carrier threads.
 */
@Component
public class SseHub implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(SseHub.class);

	/** A run that reached a final status has nothing more to say: the stream is closed after that event. */
	private static final Pattern FINAL_STATUS = Pattern.compile("\"status\":\\s*\"(COMPLETED|CANCELLED)\"");

	static final class Stream {

		final UUID runId;
		final SseEmitter emitter;
		final ReentrantLock lock = new ReentrantLock();
		final AtomicBoolean pushQueued = new AtomicBoolean();
		long lastId; // guarded by lock
		volatile boolean closed;

		Stream(UUID runId, SseEmitter emitter, long lastId) {
			this.runId = runId;
			this.emitter = emitter;
			this.lastId = lastId;
		}

	}

	private final Map<UUID, Set<Stream>> streams = new ConcurrentHashMap<>();
	private final ExecutorService pushes = Executors.newVirtualThreadPerTaskExecutor();
	private final RunEventStore events;
	private final ResearchProperties props;
	private final TaskScheduler scheduler;

	SseHub(RunEventStore events, ResearchProperties props, TaskScheduler scheduler) {
		this.events = events;
		this.props = props;
		this.scheduler = scheduler;
	}

	@EventListener(ApplicationReadyEvent.class)
	void startKeepAlive() {
		scheduler.scheduleWithFixedDelay(this::keepAlive, props.sseKeepAlive());
	}

	/**
	 * Opens a stream: replays every event after {@code lastEventId}, then stays open for live ones. If the run has
	 * already finished, the stream ends right after the replay instead of idling until its timeout.
	 */
	public SseEmitter open(UUID runId, long lastEventId, boolean runFinished) {
		SseEmitter emitter = new SseEmitter(props.sseTimeout().toMillis());
		Stream s = new Stream(runId, emitter, Math.max(0, lastEventId));
		streams.compute(runId, (k, set) -> {
			Set<Stream> out = set == null ? ConcurrentHashMap.newKeySet() : set;
			out.add(s);
			return out;
		});
		emitter.onCompletion(() -> drop(s));
		emitter.onTimeout(() -> drop(s));
		emitter.onError(e -> drop(s));
		drain(s); // replay on the request thread: the client asked for it
		if (runFinished && !s.closed) {
			drop(s);
			emitter.complete();
		}
		return emitter;
	}

	/** New events were committed for {@code runId}: push them to everyone watching it (asynchronously, coalesced). */
	public void poke(UUID runId) {
		Set<Stream> set = streams.get(runId);
		if (set == null) {
			return;
		}
		for (Stream s : set) {
			if (s.pushQueued.compareAndSet(false, true)) {
				pushes.execute(() -> {
					s.lock.lock();
					try {
						// cleared only once this pass owns the stream: while another pass is stuck in a read or send,
						// every further poke finds the flag set and adds nothing (≤ 1 running + 1 waiting per stream)
						s.pushQueued.set(false);
						drainLocked(s);
					}
					finally {
						s.lock.unlock();
					}
				});
			}
		}
	}

	/** Live connections for a run (tests, metrics). */
	public int connections(UUID runId) {
		return streams.getOrDefault(runId, Set.of()).size();
	}

	void drain(Stream s) {
		s.lock.lock();
		try {
			drainLocked(s);
		}
		finally {
			s.lock.unlock();
		}
	}

	/** Sends every event after the stream's last id. Caller holds {@code s.lock}. */
	private void drainLocked(Stream s) {
		try {
			List<EventRow> page;
			do {
				if (s.closed) {
					return;
				}
				page = events.after(s.runId, s.lastId);
				for (EventRow e : page) {
					s.emitter.send(SseEmitter.event().id(Long.toString(e.id())).name(e.type()).data(e.payloadJson()));
					s.lastId = e.id();
					if ("run.status".equals(e.type()) && FINAL_STATUS.matcher(e.payloadJson()).find()) {
						drop(s); // don't rely on onCompletion: it only fires once the response is attached
						s.emitter.complete();
						return;
					}
				}
			}
			while (page.size() == RunEventStore.PAGE);
		}
		catch (Exception e) {
			// client gone (IOException), emitter already done, or the DB read failed: close this stream — the
			// browser reconnects with Last-Event-ID and gets everything it missed replayed
			drop(s);
			try {
				s.emitter.completeWithError(e);
			}
			catch (RuntimeException alreadyDone) {
				// the emitter was completed or timed out meanwhile: nothing left to close
			}
		}
	}

	private void keepAlive() {
		streams.values().forEach(set -> set.forEach(s -> pushes.execute(() -> {
			if (!s.lock.tryLock()) {
				return; // a push is running (or stuck): that is activity enough — don't pile up another waiter
			}
			try {
				if (!s.closed) {
					s.emitter.send(SseEmitter.event().comment("keep-alive"));
				}
			}
			catch (Exception e) {
				drop(s);
			}
			finally {
				s.lock.unlock();
			}
		})));
	}

	private void drop(Stream s) {
		s.closed = true;
		// atomic with open(): a stream added for the same run at this moment is never lost
		streams.computeIfPresent(s.runId, (k, set) -> {
			set.remove(s);
			return set.isEmpty() ? null : set;
		});
		log.debug("SSE stream closed for run {}", s.runId);
	}

	@Override
	public void destroy() {
		pushes.shutdownNow();
	}

}
