package dev.anveshan.sources.app;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.anveshan.sources.api.Embeddings;
import dev.anveshan.sources.api.SourceLibrary;
import dev.anveshan.sources.api.SourceSearch.Candidate;
import dev.anveshan.sources.domain.Chunker;
import dev.anveshan.sources.domain.Chunker.Chunk;
import dev.anveshan.sources.domain.Credibility;
import dev.anveshan.sources.domain.Credibility.Rating;
import dev.anveshan.sources.domain.Credibility.Signals;
import dev.anveshan.sources.domain.Texts;
import dev.anveshan.sources.domain.Urls;
import dev.anveshan.sources.infra.CredibilityProperties;
import dev.anveshan.sources.infra.FetchProperties;
import dev.anveshan.sources.infra.PageReader;
import dev.anveshan.sources.infra.PageReader.Page;
import dev.anveshan.sources.infra.PageReader.Unreadable;
import dev.anveshan.sources.infra.RobotsCache;
import dev.anveshan.sources.infra.SourceStore;
import dev.anveshan.sources.infra.SourceStore.NewChunk;
import dev.anveshan.sources.infra.SourceStore.NewDocument;
import dev.anveshan.sources.infra.SourceStore.StoredSource;

/**
 * TECHSPEC §12.4–12.5: reuse a PUBLIC source if it exists → robots.txt → fetch and clean → credibility (§12.8) →
 * chunk → embed (at most 2 at once, so ONNX never starves request threads) → save document + chunks in one short
 * transaction (no network inside it). A page that can't be read is UNAVAILABLE with a plain reason, never an error.
 */
@Service
class DefaultSourceLibrary implements SourceLibrary {

	private static final Logger log = LoggerFactory.getLogger(DefaultSourceLibrary.class);
	private static final Pattern STARS = Pattern.compile("\\((\\d+) stars\\)");
	static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

	private final SourceStore store;
	private final PageReader reader;
	private final RobotsCache robots;
	private final Embeddings embeddings;
	private final Credibility.Rules rules;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final FetchProperties fetch;
	/** TECHSPEC §12.5: a dedicated, bounded lane for embeddings (2 at a time). */
	private final Semaphore embeddingLane = new Semaphore(2, true);

	DefaultSourceLibrary(SourceStore store, PageReader reader, RobotsCache robots, Embeddings embeddings,
			CredibilityProperties credibility, PlatformTransactionManager tm, Clock clock, FetchProperties fetch) {
		this.store = store;
		this.reader = reader;
		this.robots = robots;
		this.embeddings = embeddings;
		this.rules = credibility.rules();
		this.tx = new TransactionTemplate(tm);
		this.clock = clock;
		this.fetch = fetch;
	}

	@Override
	public IngestResult ingest(Candidate c) {
		String url = c.url();
		var existing = store.findPublic(url, c.doi());
		if (existing.isPresent()) {
			return reused(existing.get());
		}
		if (!robots.allowed(url)) {
			return IngestResult.unavailable(url, c.title(), "The site doesn't allow automated reading (robots.txt).");
		}
		PageReader.Result read = reader.read(url);
		if (read instanceof Unreadable u) {
			return IngestResult.unavailable(url, c.title(), u.reason());
		}
		Page page = (Page) read;
		LocalDate today = LocalDate.now(clock);
		Rating rating = Credibility.rate(new Signals(c.type().name(), c.doi(), url, c.published(), stars(c.snippet()),
				c.type().name().equals("REPOSITORY") ? c.published() : null), rules, today);
		Map<String, Object> metadata = new LinkedHashMap<>(page.metadata());
		metadata.put("foundBy", c.adapter());
		if (page.finalUrl() != null && !page.finalUrl().toString().equals(url)) {
			metadata.put("finalUrl", page.finalUrl().toString());
		}
		Integer stars = stars(c.snippet());
		if (stars != null) {
			metadata.put("stars", stars);
		}
		String title = firstNonBlank(c.title(), page.title(), url);
		NewDocument doc = new NewDocument("PUBLIC", null, url, c.doi(), null, Texts.contentHash(page.text()), title,
				page.authors(), Urls.host(url), c.published() != null ? c.published() : page.published(),
				Credibility.documentType(c.type().name(), url, rules), rating.tier().name(), rating.reason(), metadata,
				page.text());
		List<NewChunk> chunks = embed(page.text(), page.pageStarts());
		try {
			UUID id = tx.execute(s -> store.insert(doc, chunks));
			return new IngestResult(id, Outcome.NEW, null, title, url, rating.tier().name(), rating.reason(),
					chunks.size());
		}
		catch (DuplicateKeyException e) {
			// another run saved the same URL/DOI meanwhile: use theirs
			return store.findPublic(url, c.doi()).map(this::reused).orElseThrow(() -> e);
		}
	}

	@Override
	public List<IngestResult> ingestBest(List<Candidate> ranked, int needed) {
		List<IngestResult> results = new ArrayList<>();
		int usable = 0;
		int next = 0;
		while (usable < needed && next < ranked.size()) {
			int wave = Math.min(fetch.parallel(), ranked.size() - next);
			List<Candidate> batch = ranked.subList(next, next + wave);
			next += wave;
			List<java.util.concurrent.Future<IngestResult>> futures = new ArrayList<>();
			java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
			try {
				for (Candidate c : batch) {
					futures.add(pool.submit(() -> ingest(c)));
				}
				for (int i = 0; i < futures.size(); i++) {
					IngestResult r;
					try {
						r = futures.get(i).get();
					}
					catch (java.util.concurrent.ExecutionException e) {
						log.warn("Reading a source failed: {}", e.getCause().getClass().getSimpleName());
						r = IngestResult.unavailable(batch.get(i).url(), batch.get(i).title(), "The page couldn't be read.");
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException("interrupted");
					}
					results.add(r);
					if (r.outcome() != Outcome.UNAVAILABLE) {
						usable++;
					}
				}
			}
			finally {
				pool.shutdownNow();
			}
		}
		return results;
	}

	@Override
	public IngestResult ingestUpload(UUID ownerId, String filename, String contentType, byte[] bytes,
			UploadPurpose purpose) {
		if (bytes.length > MAX_UPLOAD_BYTES) {
			return IngestResult.unavailable(null, filename, "The file is larger than 10 MB.");
		}
		PageReader.Result read = reader.readFile(bytes, contentType, filename);
		if (read instanceof Unreadable u) {
			return IngestResult.unavailable(null, filename, u.reason());
		}
		Page page = (Page) read;
		String title = firstNonBlank(page.title(), filename);
		List<NewChunk> chunks = embed(page.text(), page.pageStarts());
		UUID id = tx.execute(s -> {
			UUID upload = store.insertUpload(ownerId, filename, page.contentType(), bytes.length, sha256(bytes),
					purpose.name());
			return store.insert(new NewDocument("PRIVATE", ownerId, null, null, upload, Texts.contentHash(page.text()),
					title, List.of(), null, null, "UPLOAD", "MEDIUM", "Your own document", page.metadata(), page.text()),
					chunks);
		});
		return new IngestResult(id, Outcome.NEW, null, title, null, "MEDIUM", "Your own document", chunks.size());
	}

	@Override
	public boolean useInRun(UUID ownerId, UUID runId, UUID sourceId, UUID subQuestionId, int rank) {
		return store.link(ownerId, runId, sourceId, subQuestionId, rank);
	}

	@Override
	public List<SourceInfo> describe(List<UUID> sourceIds) {
		return store.describe(sourceIds);
	}

	@Override
	public List<RunSource> runSources(UUID runId) {
		return store.runSources(runId);
	}

	@Override
	public boolean skip(UUID runId, UUID sourceId) {
		return store.skip(runId, sourceId);
	}

	@Override
	public List<RelevantChunk> topChunks(UUID runId, String question, int k) {
		float[] q;
		try {
			embeddingLane.acquire();
			try {
				q = embeddings.query(question);
			}
			finally {
				embeddingLane.release();
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted");
		}
		return store.topChunks(runId, q, k);
	}

	/** Chunks + their "passage:" embeddings, in batches, through the bounded lane. */
	private List<NewChunk> embed(String text, int[] pageStarts) {
		List<Chunk> chunks = Chunker.chunk(text, Chunker.MAX_CHARS, pageStarts);
		List<String> texts = chunks.stream().map(ch -> ch.text(text)).toList();
		List<float[]> vectors;
		try {
			embeddingLane.acquire();
			try {
				vectors = embeddings.passages(texts);
			}
			finally {
				embeddingLane.release();
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted");
		}
		List<NewChunk> out = new ArrayList<>(chunks.size());
		for (int i = 0; i < chunks.size(); i++) {
			Chunk ch = chunks.get(i);
			out.add(new NewChunk(ch.position(), texts.get(i), ch.start(), ch.end(), ch.page(), vectors.get(i)));
		}
		log.debug("Embedded {} chunks", out.size());
		return out;
	}

	private IngestResult reused(StoredSource s) {
		return new IngestResult(s.id(), Outcome.REUSED, null, s.title(), s.url(), s.tier(), s.tierReason(), s.chunks());
	}

	/** GitHub candidates carry "(N stars)" at the end of their snippet (GitHubAdapter). */
	static Integer stars(String snippet) {
		if (snippet == null) {
			return null;
		}
		Matcher m = STARS.matcher(snippet);
		return m.find() ? Integer.valueOf(m.group(1)) : null;
	}

	private static byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String firstNonBlank(String... values) {
		for (String v : values) {
			if (v != null && !v.isBlank()) {
				return v.strip();
			}
		}
		return "Untitled";
	}

}
