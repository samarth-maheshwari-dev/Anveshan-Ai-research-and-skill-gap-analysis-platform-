package dev.anveshan.report.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.anveshan.report.api.Reports;
import dev.anveshan.report.domain.CitationNumbering;
import dev.anveshan.report.domain.Markers;
import dev.anveshan.report.domain.References;
import dev.anveshan.report.domain.TipTapDoc;
import dev.anveshan.report.domain.TipTapDoc.Citation;
import dev.anveshan.report.infra.ReportStore;
import dev.anveshan.report.infra.ReportStore.NewSection;
import dev.anveshan.sources.api.ClaimLibrary;
import dev.anveshan.sources.api.ClaimLibrary.ReportClaim;
import dev.anveshan.sources.api.SourceLibrary;
import dev.anveshan.sources.api.SourceLibrary.SourceInfo;

/**
 * Assembly (TECHSPEC §12.9): citation numbers by first appearance (one per source, L8), every citation node's claim
 * checked against the run's claims, the Conflicts section and the References list built by code.
 */
@Service
class DefaultReports implements Reports {

	static final String CONFLICTS_KEY = "conflicts";
	static final String REFERENCES_KEY = "references";

	private final ReportStore store;
	private final ClaimLibrary claims;
	private final SourceLibrary sources;
	private final TransactionTemplate tx;

	DefaultReports(ReportStore store, ClaimLibrary claims, SourceLibrary sources, PlatformTransactionManager tm) {
		this.store = store;
		this.claims = claims;
		this.sources = sources;
		this.tx = new TransactionTemplate(tm);
	}

	@Override
	public Assembled assemble(AssembleInput in) {
		// 1. every cited claim must be a claim of this run (L8); unknown ones are simply not cited
		Set<UUID> cited = new LinkedHashSet<>();
		in.sections().forEach(s -> cited.addAll(Markers.citedClaims(s.markdown())));
		in.conflicts().forEach(c -> cited.add(c.claimId()));
		Map<UUID, ReportClaim> claimById = new HashMap<>();
		claims.reportClaims(in.runId(), List.copyOf(cited)).forEach(c -> claimById.put(c.id(), c));

		// 2. reading order of sources → numbers (body first, then the conflicts section)
		List<UUID> reading = new ArrayList<>();
		for (var s : in.sections()) {
			for (UUID claim : Markers.citedClaims(s.markdown())) {
				ReportClaim c = claimById.get(claim);
				if (c != null) {
					reading.add(c.sourceId());
				}
			}
		}
		for (var c : in.conflicts()) {
			reading.addAll(c.supporting());
			reading.addAll(c.contradicting());
		}
		Map<UUID, Integer> numbers = CitationNumbering.number(reading);
		Map<UUID, SourceInfo> info = new LinkedHashMap<>();
		sources.describe(List.copyOf(numbers.keySet())).forEach(s -> info.put(s.id(), s));

		java.util.function.BiFunction<UUID, UUID, Citation> cite = (claimId, sourceId) -> {
			ReportClaim c = claimById.get(claimId);
			if (c == null) {
				return null;
			}
			UUID src = sourceId != null ? sourceId : c.sourceId();
			Integer n = numbers.get(src);
			return n == null ? null : new Citation(claimId, src, n, c.label());
		};
		java.util.function.Function<UUID, Integer> numberOfClaim = claimId -> {
			ReportClaim c = claimById.get(claimId);
			return c == null ? null : numbers.get(c.sourceId());
		};

		// 3. sections
		boolean hindi = "hi".equals(in.language());
		List<NewSection> out = new ArrayList<>();
		for (var s : in.sections()) {
			out.add(new NewSection(s.key(), s.title(), TipTapDoc.from(s.markdown(), cite),
					TipTapDoc.markdownWithNumbers(s.markdown(), numberOfClaim, (c, src) -> numbers.get(src))));
		}
		String conflictsMd = conflicts(in.conflicts(), info, hindi);
		out.add(new NewSection(CONFLICTS_KEY, hindi ? "विरोधाभास और खुले प्रश्न" : "Conflicts & open questions",
				TipTapDoc.from(conflictsMd, cite),
				TipTapDoc.markdownWithNumbers(conflictsMd, numberOfClaim, (c, src) -> numbers.get(src))));
		List<String> refs = new ArrayList<>();
		numbers.forEach((src, n) -> {
			SourceInfo s = info.get(src);
			if (s != null) {
				refs.add(References.ieee(n, new References.Ref(s.title(), s.authors(), s.site(), s.publishedAt(), s.url(),
						s.doi())));
			}
		});
		out.add(new NewSection(REFERENCES_KEY, hindi ? "संदर्भ" : "References", TipTapDoc.paragraphs(refs),
				String.join("\n\n", refs)));

		UUID reportId = tx.execute(st -> {
			UUID id = store.upsert(in.runId(), in.ownerId(), in.title(), in.language());
			store.replaceSections(id, out);
			return id;
		});
		int citations = out.stream().mapToInt(s -> TipTapDoc.citations(s.contentDoc()).size()).sum();
		return new Assembled(reportId, out.size(), citations, numbers.size());
	}

	@Override
	public Optional<UUID> reportIdForRun(UUID runId) {
		return store.idForRun(runId);
	}

	@Override
	public MarkerCheck checkMarkers(String markdown, Map<String, UUID> aliases) {
		Markers.Resolved r = Markers.resolve(markdown, aliases);
		return new MarkerCheck(r.markdown(), r.removed(), r.unmarked());
	}

	/** F-FR-11: both sides of every conflict with their sources, plus the one-line possible reason. */
	static String conflicts(List<ConflictDraft> conflicts, Map<UUID, SourceInfo> info, boolean hindi) {
		if (conflicts.isEmpty()) {
			return hindi ? "स्रोतों के बीच कोई विरोधाभास नहीं मिला।" : "No conflicting evidence was found between the sources.";
		}
		StringBuilder md = new StringBuilder();
		for (ConflictDraft c : conflicts) {
			md.append("**").append(escape(c.text())).append("**\n\n");
			md.append("- ").append(hindi ? "समर्थन: " : "Supported by: ").append(sides(c.claimId(), c.supporting(), info))
					.append('\n');
			md.append("- ").append(hindi ? "विरोध: " : "Contradicted by: ").append(sides(c.claimId(), c.contradicting(), info))
					.append('\n');
			if (c.reason() != null && !c.reason().isBlank()) {
				md.append("- ").append(hindi ? "संभावित कारण: " : "Possible reason: ").append(escape(c.reason().strip()))
						.append('\n');
			}
			md.append('\n');
		}
		return md.toString().strip();
	}

	private static String sides(UUID claimId, List<UUID> sourceIds, Map<UUID, SourceInfo> info) {
		List<String> parts = new ArrayList<>();
		for (UUID s : sourceIds) {
			SourceInfo i = info.get(s);
			parts.add(escape(i == null ? "a source" : i.title()) + " [c:" + claimId + ":" + s + "]");
		}
		return String.join("; ", parts);
	}

	/** Model and page text is data: no Markdown/HTML control characters survive into the conflicts section. */
	private static String escape(String s) {
		return s.replaceAll("[*_`#<>\\[\\]]", " ").replaceAll("\\s+", " ").strip();
	}

}
