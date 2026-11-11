package dev.anveshan.research.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SseHub push behaviour without a database: the event store is a counting fake. */
class SseHubTest {

	/** Counts reads per run; reads for a "blocked" run wait on a latch (a stalled client/DB). */
	static class CountingStore extends RunEventStore {

		final Map<UUID, AtomicInteger> reads = new ConcurrentHashMap<>();
		final Map<UUID, CountDownLatch> blocked = new ConcurrentHashMap<>();

		CountingStore() {
			super(null);
		}

		@Override
		public List<EventRow> after(UUID runId, long lastId) {
			reads.computeIfAbsent(runId, k -> new AtomicInteger()).incrementAndGet();
			CountDownLatch gate = blocked.get(runId);
			if (gate != null) {
				try {
					gate.await(30, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			try {
				Thread.sleep(5); // a read takes a little time, so pokes pile up while it runs
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return List.of();
		}

		int reads(UUID run) {
			AtomicInteger n = reads.get(run);
			return n == null ? 0 : n.get();
		}

	}

	final CountingStore store = new CountingStore();
	final SseHub hub = new SseHub(store, new ResearchProperties(3, Duration.ofSeconds(2), Duration.ofMinutes(10),
			Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ofSeconds(15), Duration.ofSeconds(60),
			Duration.ofMinutes(5), Duration.ofDays(7)), null);

	@AfterEach
	void close() {
		store.blocked.values().forEach(CountDownLatch::countDown);
		hub.destroy();
	}

	@Test
	@DisplayName("T-09-23 a burst of 500 events for one run costs a stream a handful of reads, not 500 (pokes coalesce)")
	void pokesCoalesce() {
		UUID run = UUID.randomUUID();
		hub.open(run, 0, false);
		int afterReplay = store.reads(run);
		for (int i = 0; i < 500; i++) {
			hub.poke(run);
		}
		await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(10))
				.untilAsserted(() -> assertThat(store.reads(run) - afterReplay).isBetween(1, 10));
	}

	@Test
	@DisplayName("T-09-23 a stream stuck in a read/send doesn't hold up another run's stream")
	void stuckStreamDoesNotBlockOthers() {
		UUID stuck = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		hub.open(stuck, 0, false);
		hub.open(other, 0, false);
		CountDownLatch gate = new CountDownLatch(1);
		store.blocked.put(stuck, gate);
		int otherBefore = store.reads(other);

		hub.poke(stuck); // parks on the latch
		hub.poke(other);
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(store.reads(other)).isGreaterThan(otherBefore));
		gate.countDown();
	}

	@Test
	@DisplayName("T-09-23 while a push is stuck in a read, 100 more pokes add at most one more read: waiters don't pile "
			+ "up (REV-P09-R3-01)")
	void stuckPushDoesNotPileUp() throws Exception {
		UUID run = UUID.randomUUID();
		hub.open(run, 0, false);
		CountDownLatch gate = new CountDownLatch(1);
		store.blocked.put(run, gate);
		int before = store.reads(run);
		hub.poke(run); // this pass parks inside the read
		await().atMost(Duration.ofSeconds(5)).until(() -> store.reads(run) == before + 1);
		for (int i = 0; i < 100; i++) {
			hub.poke(run);
			Thread.sleep(2); // spaced, so each poke comes after the previous task has started
		}
		store.blocked.remove(run);
		gate.countDown();
		await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(10))
				.untilAsserted(() -> assertThat(store.reads(run) - before).isBetween(1, 3));
	}

	@Test
	@DisplayName("T-09-25 opening the stream of a finished run closes it right after the (empty) replay")
	void finishedRunStreamCloses() {
		UUID run = UUID.randomUUID();
		hub.open(run, 0, true);
		assertThat(hub.connections(run)).isZero();
	}

}
