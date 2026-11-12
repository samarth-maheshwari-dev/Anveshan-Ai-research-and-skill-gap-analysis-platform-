package dev.anveshan.sources.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.anveshan.sources.api.ClaimLibrary;
import dev.anveshan.sources.api.Embeddings;
import dev.anveshan.sources.domain.Credibility.Tier;
import dev.anveshan.sources.domain.Grounding;
import dev.anveshan.sources.domain.Locator;
import dev.anveshan.sources.domain.Locator.Span;
import dev.anveshan.sources.domain.TrustLabeler;
import dev.anveshan.sources.domain.TrustLabeler.Judged;
import dev.anveshan.sources.domain.TrustLabeler.TrustLabel;
import dev.anveshan.sources.domain.TrustLabeler.Verdict;
import dev.anveshan.sources.infra.ClaimStore;
import dev.anveshan.sources.infra.ClaimStore.VerdictRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/** Claims: anchoring (L1), reuse (TD-4), verification passages, labels (L2). */
@Service
class DefaultClaimLibrary implements ClaimLibrary {

	private final ClaimStore store;
	private final Embeddings embeddings;
	private final JsonMapper json;
	private final TransactionTemplate tx;
	/** Claims dropped because their quote wasn't in the chunk (TECHSPEC §12.6 "unanchored claims"). */
	private final Counter unanchored;
	/** Claims dropped because they weren't about their quote (invented numbers / topics). */
	private final Counter ungrounded;

	DefaultClaimLibrary(ClaimStore store, Embeddings embeddings, JsonMapper json, PlatformTransactionManager tm,
			MeterRegistry meters) {
		this.store = store;
		this.embeddings = embeddings;
		this.json = json;
		this.tx = new TransactionTemplate(tm);
		this.unanchored = Counter.builder("claims.unanchored").description("Claims dropped: quote not found in the source")
				.register(meters);
		this.ungrounded = Counter.builder("claims.ungrounded").description("Claims dropped: not about their quote")
				.register(meters);
	}

	@Override
	public Map<UUID, Passage> passages(List<UUID> chunkIds) {
		Map<UUID, Passage> out = new LinkedHashMap<>();
		store.passages(chunkIds).forEach(p -> out.put(p.chunkId(), p));
		return out;
	}

	@Override
	public List<Claim> cached(UUID chunkId, String promptVersion) {
		return store.cached(chunkId, promptVersion);
	}

	@Override
	public Anchored anchor(UUID chunkId, String text, String quote, String promptVersion) {
		Passage p = store.passages(List.of(chunkId)).stream().findFirst()
				.orElseThrow(() -> new IllegalArgumentException("unknown chunk"));
		Optional<Span> span = Locator.locate(p.text(), quote);
		if (span.isEmpty()) {
			unanchored.increment();
			return new Anchored(AnchorOutcome.NOT_IN_SOURCE, null);
		}
		String exact = p.text().substring(span.get().start(), span.get().end());
		String claimText = text.strip();
		if (!Grounding.grounded(claimText, exact)) {
			ungrounded.increment();
			return new Anchored(AnchorOutcome.NOT_GROUNDED, null);
		}
		for (Claim c : store.cached(chunkId, promptVersion)) {
			if (c.text().equalsIgnoreCase(claimText)) {
				return new Anchored(AnchorOutcome.REUSED, c);
			}
		}
		float[] vector = embeddings.passage(claimText);
		UUID id = tx.execute(s -> store.insertClaim(chunkId, p.sourceId(), claimText, exact, span.get().start(),
				span.get().end(), promptVersion, vector));
		return new Anchored(AnchorOutcome.SAVED,
				new Claim(id, chunkId, p.sourceId(), claimText, exact, span.get().start(), span.get().end()));
	}

	@Override
	public void useInRun(UUID runId, UUID claimId, UUID subQuestionId) {
		store.link(runId, claimId, subQuestionId);
	}

	@Override
	public List<Claim> runClaims(UUID runId) {
		return store.runClaims(runId);
	}

	@Override
	public List<Claim> claims(List<UUID> ids) {
		return store.claims(ids);
	}

	@Override
	public List<Passage> verificationPassages(UUID runId, Claim claim, int others) {
		List<Passage> out = new ArrayList<>(store.passages(List.of(claim.chunkId())));
		out.addAll(store.similarOtherSources(runId, claim.id(), claim.sourceId(), others));
		return out;
	}

	@Override
	public void saveVerdict(UUID runId, UUID claimId, UUID chunkId, String verdict, String reason) {
		store.saveVerdict(runId, claimId, chunkId, verdict, reason);
	}

	@Override
	public LabelCounts labelRun(UUID runId) {
		Map<UUID, List<VerdictRow>> byClaim = new HashMap<>();
		for (VerdictRow v : store.verdicts(runId)) {
			byClaim.computeIfAbsent(v.claimId(), k -> new ArrayList<>()).add(v);
		}
		int[] counts = new int[4];
		tx.executeWithoutResult(s -> {
			for (UUID claimId : store.runClaimIds(runId)) {
				List<VerdictRow> rows = byClaim.getOrDefault(claimId, List.of());
				TrustLabel label = TrustLabeler.label(rows.stream()
						.map(v -> new Judged(Verdict.valueOf(v.verdict()), Tier.valueOf(v.tier()))).toList());
				counts[label.ordinal()]++;
				store.setLabel(runId, claimId, label.name(), json.writeValueAsString(reason(rows)));
			}
		});
		return new LabelCounts(counts[TrustLabel.SUPPORTED.ordinal()], counts[TrustLabel.WEAK.ordinal()],
				counts[TrustLabel.CONFLICTING.ordinal()], counts[TrustLabel.NO_SOURCE.ordinal()]);
	}

	@Override
	public List<ReportClaim> reportClaims(UUID runId, List<UUID> claimIds) {
		return store.reportClaims(runId, claimIds);
	}

	@Override
	public List<Match> usableMatches(UUID runId, float[] vector) {
		return store.usableMatches(runId, vector);
	}

	@Override
	public List<Conflict> conflicts(UUID runId) {
		Map<UUID, String> text = new LinkedHashMap<>();
		Map<UUID, java.util.LinkedHashSet<UUID>> supports = new HashMap<>();
		Map<UUID, java.util.LinkedHashSet<UUID>> contradicts = new HashMap<>();
		for (Object[] row : store.conflictRows(runId)) {
			UUID claim = (UUID) row[0];
			text.putIfAbsent(claim, (String) row[1]);
			(("SUPPORTS".equals(row[3])) ? supports : contradicts)
					.computeIfAbsent(claim, k -> new java.util.LinkedHashSet<>()).add((UUID) row[2]);
		}
		List<Conflict> out = new ArrayList<>();
		text.forEach((claim, t) -> {
			var s = supports.getOrDefault(claim, new java.util.LinkedHashSet<>());
			var c = contradicts.getOrDefault(claim, new java.util.LinkedHashSet<>());
			if (!s.isEmpty() && !c.isEmpty()) { // both sides still there (a skipped source can remove one)
				out.add(new Conflict(claim, t, List.copyOf(s), List.copyOf(c)));
			}
		});
		return out;
	}

	/** Which verdicts produced the label, so the UI can show "supported by 2 sources (1 high)". */
	private static Map<String, Object> reason(List<VerdictRow> rows) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("rule", "PRD §14.3");
		r.put("verdicts", rows.stream().map(v -> Map.of("chunkId", v.chunkId(), "sourceId", v.sourceId(), "verdict",
				v.verdict(), "tier", v.tier())).toList());
		return r;
	}

}
