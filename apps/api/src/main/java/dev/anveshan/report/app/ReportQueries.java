package dev.anveshan.report.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import dev.anveshan.report.domain.TipTapDoc;
import dev.anveshan.report.infra.ReportStore;
import dev.anveshan.report.infra.ReportStore.ReportRow;
import dev.anveshan.report.infra.ReportStore.SectionRow;
import dev.anveshan.shared.web.ApiException;
import dev.anveshan.sources.api.ClaimLibrary;
import dev.anveshan.sources.api.SourceLibrary;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET /reports/{id}} (TECHSPEC §8.5): the report with every cited claim and source, owner only. */
@Service
public class ReportQueries {

	public record ReportView(UUID id, UUID runId, String title, String language, String citationStyle, Instant createdAt,
			List<SectionView> sections, List<CitationView> citations, Map<String, ClaimView> claims,
			Map<String, SourceView> sources) {
	}

	public record SectionView(UUID id, String key, String title, Map<String, Object> contentDoc, int version) {
	}

	public record CitationView(int n, UUID claimId, UUID sourceId) {
	}

	public record ClaimView(String text, String trustLabel, Map<String, Object> labelReason, String quote, UUID sourceId,
			UUID chunkId, int quoteStart, int quoteEnd) {
	}

	public record SourceView(String title, String site, LocalDate publishedAt, String type, String credibilityTier,
			String credibilityReason, String url) {
	}

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final ReportStore store;
	private final ClaimLibrary claims;
	private final SourceLibrary sources;
	private final JsonMapper json;

	ReportQueries(ReportStore store, ClaimLibrary claims, SourceLibrary sources, JsonMapper json) {
		this.store = store;
		this.claims = claims;
		this.sources = sources;
		this.json = json;
	}

	public ReportView get(UUID ownerId, UUID reportId) {
		ReportRow r = store.findOwned(ownerId, reportId).orElseThrow(ApiException::notFound);
		List<SectionRow> rows = store.sections(r.id());
		List<CitationView> citations = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		Set<UUID> claimIds = new LinkedHashSet<>();
		Set<UUID> sourceIds = new LinkedHashSet<>();
		for (SectionRow s : rows) {
			for (Map<String, Object> c : TipTapDoc.citations(s.contentDoc())) {
				UUID claim = UUID.fromString((String) c.get("claimId"));
				UUID source = UUID.fromString((String) c.get("sourceId"));
				int n = ((Number) c.get("n")).intValue();
				if (seen.add(claim + "|" + source)) {
					citations.add(new CitationView(n, claim, source));
				}
				claimIds.add(claim);
				sourceIds.add(source);
			}
		}
		Map<String, ClaimView> claimViews = new LinkedHashMap<>();
		for (var c : claims.reportClaims(r.runId(), List.copyOf(claimIds))) {
			claimViews.put(c.id().toString(), new ClaimView(c.text(), c.label(),
					c.labelReasonJson() == null ? Map.of() : json.readValue(c.labelReasonJson(), MAP), c.quote(),
					c.sourceId(), c.chunkId(), c.quoteStart(), c.quoteEnd()));
		}
		Map<String, SourceView> sourceViews = new LinkedHashMap<>();
		for (var s : sources.describe(List.copyOf(sourceIds))) {
			sourceViews.put(s.id().toString(), new SourceView(s.title(), s.site(), s.publishedAt(), s.type(), s.tier(),
					s.tierReason(), s.url()));
		}
		return new ReportView(r.id(), r.runId(), r.title(), r.language(), r.citationStyle(), r.createdAt(),
				rows.stream().map(s -> new SectionView(s.id(), s.key(), s.title(), s.contentDoc(), s.version())).toList(),
				citations, claimViews, sourceViews);
	}

}
