package dev.anveshan.sources.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.anveshan.sources.api.Embeddings;
import dev.anveshan.sources.api.SourceSearch;
import dev.anveshan.sources.app.SearchAdapter.AdapterQuery;
import dev.anveshan.sources.domain.Dedupe;
import dev.anveshan.sources.domain.Doi;
import dev.anveshan.sources.domain.Ranking;
import dev.anveshan.sources.domain.Urls;
import dev.anveshan.sources.domain.Vectors;
import dev.anveshan.sources.infra.ExternalBudget;
import dev.anveshan.sources.infra.SearchCache;
import dev.anveshan.sources.infra.SearchProperties;
import dev.anveshan.sources.infra.SearchResilience;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

/**
 * TECHSPEC §11.2: every API that fits the request runs in parallel (one virtual thread each); then canonicalize →
 * filter (excluded sites, time range) → de-duplicate → rank locally (embeddings + small boosts, no LLM) → top N.
 * An API that fails, times out, is over budget or has an open circuit is reported, never thrown.
 */
@Service
class DefaultSourceSearch implements SourceSearch {

	private static final Logger log = LoggerFactory.getLogger(DefaultSourceSearch.class);

	private record AdapterResult(AdapterReport report, List<Candidate> candidates) {
	}

	private final List<SearchAdapter> adapters;
	private final SearchResilience resilience;
	private final ExternalBudget budget;
	private final SearchCache cache;
	private final SearchProperties props;
	private final Embeddings embeddings;

	DefaultSourceSearch(List<SearchAdapter> adapters, SearchResilience resilience, ExternalBudget budget,
			SearchCache cache, SearchProperties props, Embeddings embeddings) {
		this.adapters = List.copyOf(adapters);
		this.resilience = resilience;
		this.budget = budget;
		this.cache = cache;
		this.props = props;
		this.embeddings = embeddings;
	}

	@Override
	public SearchOutcome search(SearchRequest req) {
		List<SearchAdapter> chosen = adapters.stream()
				.filter(a -> a.kinds().stream().anyMatch(req.kinds()::contains))
				.filter(a -> a.isAvailableFor(req.ownerId()))
				.toList();
		List<String> queries = queries(req);

		Map<SearchAdapter, Future<AdapterResult>> running = new LinkedHashMap<>();
		List<AdapterResult> results = new ArrayList<>();
		ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
		try {
			for (SearchAdapter a : chosen) {
				running.put(a, pool.submit(() -> run(a, req, queries)));
			}
			long deadline = System.nanoTime() + props.adapterTimeout().toNanos();
			for (var e : running.entrySet()) {
				SearchAdapter a = e.getKey();
				try {
					long left = Math.max(1, deadline - System.nanoTime());
					results.add(e.getValue().get(left, TimeUnit.NANOSECONDS));
				}
				catch (TimeoutException ex) {
					e.getValue().cancel(true);
					log.warn("Search API {} timed out", a.name());
					results.add(failed(a));
				}
				catch (ExecutionException ex) {
					results.add(failed(a));
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					results.add(failed(a));
				}
			}
		}
		finally {
			pool.shutdownNow(); // don't wait for a timed-out API: its thread is interrupted and its result ignored
		}

		List<Candidate> all = new ArrayList<>();
		results.forEach(r -> all.addAll(r.candidates()));
		List<Candidate> ranked = rank(req, Dedupe.merge(clean(all, req)));
		return new SearchOutcome(ranked.subList(0, Math.min(req.keep(), ranked.size())),
				results.stream().map(AdapterResult::report).toList());
	}

	/** The plan's queries (de-duplicated, best first); the sub-question itself when there are none. */
	private List<String> queries(SearchRequest req) {
		LinkedHashSet<String> q = new LinkedHashSet<>();
		for (String s : req.queries() == null ? List.<String>of() : req.queries()) {
			if (s != null && !s.isBlank()) {
				q.add(s.strip());
			}
		}
		if (q.isEmpty()) {
			q.add(req.question());
		}
		return q.stream().limit(props.queriesPerAdapter()).toList();
	}

	/** One API, every query; OK if at least one query answered. Never throws. */
	private AdapterResult run(SearchAdapter a, SearchRequest req, List<String> queries) {
		if (resilience.circuitOpen(a.name())) {
			return new AdapterResult(new AdapterReport(a.name(), a.label(), AdapterStatus.SKIPPED_CIRCUIT_OPEN, 0),
					List.of());
		}
		List<Candidate> found = new ArrayList<>();
		boolean answered = false;
		boolean overBudget = false;
		for (String text : queries) {
			AdapterQuery q = new AdapterQuery(req.ownerId(), text, req.since(), props.resultsPerQuery(),
					req.includeSites() == null ? List.of() : req.includeSites(),
					req.excludeSites() == null ? List.of() : req.excludeSites());
			String key = cacheKey(a, q);
			Optional<List<Candidate>> hit = cache.get(key);
			if (hit.isPresent()) {
				found.addAll(hit.get());
				answered = true;
				continue;
			}
			if (!budget.tryUse(a.name())) {
				overBudget = true;
				break;
			}
			try {
				List<Candidate> r = resilience.call(a.name(), () -> a.search(q));
				cache.put(key, r);
				found.addAll(r);
				answered = true;
			}
			catch (CallNotPermittedException e) {
				break; // circuit opened meanwhile
			}
			catch (RuntimeException e) {
				// type only: an API's message may echo the request
				log.warn("Search API {} failed: {}", a.name(), e.getClass().getSimpleName());
			}
		}
		AdapterStatus status = answered ? AdapterStatus.OK
				: overBudget ? AdapterStatus.SKIPPED_BUDGET : AdapterStatus.FAILED;
		return new AdapterResult(new AdapterReport(a.name(), a.label(), status, found.size()), found);
	}

	private static AdapterResult failed(SearchAdapter a) {
		return new AdapterResult(new AdapterReport(a.name(), a.label(), AdapterStatus.FAILED, 0), List.of());
	}

	/** Per-user key for BYOK web search; shared for the free APIs (same query → same public results). */
	private static String cacheKey(SearchAdapter a, AdapterQuery q) {
		String owner = a.name().equals("tavily") ? q.ownerId().toString() : "*";
		return String.join("|", a.name(), owner, q.text().strip().toLowerCase(Locale.ROOT),
				String.valueOf(q.since()), String.valueOf(q.maxResults()), String.join(",", q.includeSites()),
				String.join(",", q.excludeSites()));
	}

	/** Canonical URL + DOI; drops what has no usable URL, is on an excluded site, or is older than the range. */
	private static List<Candidate> clean(List<Candidate> raw, SearchRequest req) {
		List<Candidate> out = new ArrayList<>();
		for (Candidate c : raw) {
			String doi = Doi.normalize(c.doi());
			String url = Urls.canonical(c.url());
			if (url == null && doi != null) {
				url = doi;
			}
			if (url == null || c.title() == null || c.title().isBlank()) {
				continue;
			}
			String u = url;
			if (req.excludeSites() != null && req.excludeSites().stream().anyMatch(s -> Urls.onSite(u, s))) {
				continue;
			}
			if (req.since() != null && c.published() != null && c.published().isBefore(req.since())) {
				continue;
			}
			out.add(new Candidate(c.title().strip(), url, doi, c.snippet(), c.published(), c.type(), c.adapter(), 0));
		}
		return out;
	}

	private List<Candidate> rank(SearchRequest req, List<Candidate> candidates) {
		if (candidates.isEmpty()) {
			return candidates;
		}
		float[] question = embeddings.query(req.question());
		List<float[]> vectors = embeddings.passages(candidates.stream()
				.map(c -> c.snippet() == null ? c.title() : c.title() + ". " + c.snippet()).toList());
		List<Candidate> scored = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			Candidate c = candidates.get(i);
			boolean preferred = req.includeSites() != null && req.includeSites().stream().anyMatch(s -> Urls.onSite(c.url(), s));
			scored.add(c.withScore(Ranking.score(Vectors.cosine(question, vectors.get(i)), c.type(), c.published(),
					req.since(), preferred)));
		}
		scored.sort(Comparator.comparingDouble(Candidate::score).reversed());
		return scored;
	}

}
