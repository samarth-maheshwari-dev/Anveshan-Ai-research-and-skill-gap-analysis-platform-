package dev.anveshan.report.app;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.anveshan.report.app.ReportQueries.SectionView;
import dev.anveshan.report.app.ReportQueries.SourceView;
import dev.anveshan.report.domain.ReportDocs;
import dev.anveshan.report.domain.ReportDocs.ClaimInfo;
import dev.anveshan.report.domain.ReportDocs.Renumbered;
import dev.anveshan.report.domain.TipTapDoc;
import dev.anveshan.report.infra.ReportStore;
import dev.anveshan.report.infra.ReportStore.ReportRow;
import dev.anveshan.report.infra.ReportStore.SectionRow;
import dev.anveshan.shared.web.ApiException;
import dev.anveshan.sources.api.ClaimLibrary;
import dev.anveshan.sources.api.ClaimLibrary.ReportClaim;
import dev.anveshan.sources.api.SourceLibrary;
import dev.anveshan.sources.api.SourceLibrary.SourceInfo;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Report Studio's writes and the citation panel (Module F, TECHSPEC §8.5 / §9.7). Every call is scoped to the
 * owner in SQL first (another user's report → 404). After any change the whole report is <b>normalized</b> by code:
 * citations renumbered by first appearance (L8), labels taken from the run's claims, References rebuilt, Markdown
 * re-derived — so the numbers never have gaps and never come from the browser.
 */
@Service
public class ReportEditor {

	/** Sections written by code from the sources: shown, never edited by hand. */
	static final Set<String> GENERATED = Set.of(DefaultReports.CONFLICTS_KEY, DefaultReports.REFERENCES_KEY);

	public record Highlight(int start, int end) {
	}

	/** One source's verdict on the claim (why the label is what it is, F-FR-2). */
	public record EvidenceView(UUID sourceId, String title, String site, String credibilityTier, String verdict,
			boolean own) {
	}

	/** F-FR-1: the exact passage behind a citation, with the quoted span to highlight. */
	public record PassageView(UUID claimId, String claimText, String trustLabel, String quote, String chunkText,
			Highlight highlight, UUID sourceId, SourceView source, List<EvidenceView> evidence) {
	}

	public record VersionView(int version, Instant createdAt, boolean byYou) {
	}

	public record VersionPage(List<VersionView> items, String nextCursor) {
	}

	public record Restored(int version, List<SectionView> sections) {
	}

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final ReportStore store;
	private final ClaimLibrary claims;
	private final SourceLibrary sources;
	private final JsonMapper json;

	ReportEditor(ReportStore store, ClaimLibrary claims, SourceLibrary sources, JsonMapper json) {
		this.store = store;
		this.claims = claims;
		this.sources = sources;
		this.json = json;
	}

	// ------------------------------------------------------------------ reads

	public ReportRow owned(UUID ownerId, UUID reportId) {
		return store.findOwned(ownerId, reportId).orElseThrow(ApiException::notFound);
	}

	/** T-15-01: {@code highlight} is exactly the claim's stored quote span in its chunk (L1). */
	@Transactional(readOnly = true)
	public PassageView passage(UUID ownerId, UUID reportId, UUID claimId) {
		ReportRow r = owned(ownerId, reportId);
		ReportClaim c = claims.reportClaims(r.runId(), List.of(claimId)).stream().findFirst()
				.orElseThrow(ApiException::notFound);
		ClaimLibrary.Passage p = claims.passages(List.of(c.chunkId())).get(c.chunkId());
		if (p == null) {
			throw ApiException.notFound();
		}
		List<Map<String, Object>> verdicts = verdicts(c.labelReasonJson());
		Set<UUID> ids = new LinkedHashSet<>();
		ids.add(c.sourceId());
		verdicts.forEach(v -> ids.add(UUID.fromString((String) v.get("sourceId"))));
		Map<UUID, SourceInfo> info = new LinkedHashMap<>();
		sources.describe(List.copyOf(ids)).forEach(s -> info.put(s.id(), s));
		List<EvidenceView> evidence = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Map<String, Object> v : verdicts) {
			UUID src = UUID.fromString((String) v.get("sourceId"));
			String verdict = String.valueOf(v.get("verdict"));
			SourceInfo s = info.get(src);
			if (s != null && seen.add(src + "|" + verdict)) {
				evidence.add(new EvidenceView(src, s.title(), s.site(), s.tier(), verdict, src.equals(c.sourceId())));
			}
		}
		SourceInfo own = info.get(c.sourceId());
		return new PassageView(c.id(), c.text(), c.label(), c.quote(), p.text(),
				new Highlight(c.quoteStart(), c.quoteEnd()), c.sourceId(), own == null ? null : view(own), evidence);
	}

	@Transactional(readOnly = true)
	public VersionPage versions(UUID ownerId, UUID reportId, String cursor, int limit) {
		ReportRow r = owned(ownerId, reportId);
		int size = Math.clamp(limit, 1, 100);
		Integer before = null;
		if (cursor != null && !cursor.isBlank()) {
			try {
				before = Integer.parseInt(cursor.strip());
			}
			catch (NumberFormatException e) {
				throw ApiException.badRequest("This page link is no longer valid. Load the list again.");
			}
		}
		List<ReportStore.VersionRow> rows = store.versions(r.id(), before, size);
		List<VersionView> items = rows.stream().limit(size)
				.map(v -> new VersionView(v.version(), v.createdAt(), ownerId.equals(v.createdBy()))).toList();
		String next = rows.size() > size ? Integer.toString(rows.get(size - 1).version()) : null;
		return new VersionPage(items, next);
	}

	// ------------------------------------------------------------------ writes

	/**
	 * PATCH a section (SCHEMA §6.4): sanitized, saved only at {@code expectedVersion} (else 409 VERSION_CONFLICT),
	 * then the report is normalized and saved as a new version (F-FR-6: every save is a version).
	 */
	@Transactional
	public SectionView save(UUID ownerId, UUID reportId, UUID sectionId, Integer expectedVersion, Object contentDoc) {
		ReportRow r = owned(ownerId, reportId);
		if (expectedVersion == null) {
			throw ApiException.badRequest("Send the section's version in the If-Match header.");
		}
		Map<String, Object> doc;
		try {
			doc = ReportDocs.sanitize(contentDoc);
		}
		catch (IllegalArgumentException e) {
			throw ApiException.badRequest(e.getMessage());
		}
		return write(ownerId, r, sectionId, expectedVersion, doc);
	}

	/** Regenerate (F-FR-7): the writer's Markdown with {@code [c:<claimId>]} markers replaces the section. */
	@Transactional
	public SectionView replaceWithMarkdown(UUID ownerId, UUID reportId, UUID sectionId, Integer expectedVersion,
			String markdown) {
		ReportRow r = owned(ownerId, reportId);
		// numbers and sources are settled by normalize(); distinct placeholders keep "[c:A][c:B]" from merging
		java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
		Map<String, Object> doc = TipTapDoc.from(markdown, (claimId, sourceId) -> new TipTapDoc.Citation(claimId,
				sourceId == null ? claimId : sourceId, n.incrementAndGet(), null));
		return write(ownerId, r, sectionId, expectedVersion, ReportDocs.sanitize(doc));
	}

	private SectionView write(UUID ownerId, ReportRow r, UUID sectionId, Integer expectedVersion,
			Map<String, Object> doc) {
		store.lock(r.id());
		SectionRow current = store.section(r.id(), sectionId).orElseThrow(ApiException::notFound);
		if (GENERATED.contains(current.key())) {
			throw ApiException.badRequest("This section is built by Anveshan from the sources and can't be changed here.");
		}
		int expected = expectedVersion == null ? current.version() : expectedVersion;
		if (store.saveEdited(sectionId, expected, doc, ReportDocs.markdown(doc)).isEmpty()) {
			throw conflict(current.version());
		}
		normalize(r.id(), r.runId());
		store.snapshot(r.id(), ownerId);
		SectionRow saved = store.section(r.id(), sectionId).orElseThrow();
		return new SectionView(saved.id(), saved.key(), saved.title(), saved.contentDoc(), saved.version());
	}

	/** F-FR-6: the version's sections come back as new versions of themselves; history is kept (T-15-04). */
	@Transactional
	public Restored restore(UUID ownerId, UUID reportId, int version) {
		ReportRow r = owned(ownerId, reportId);
		store.lock(r.id());
		String snapshot = store.snapshotOf(r.id(), version).orElseThrow(ApiException::notFound);
		JsonNode root = json.readTree(snapshot);
		JsonNode list = root.isArray() ? root : root.path("sections"); // P14's first snapshots were a bare list
		List<SectionRow> current = store.sections(r.id());
		for (JsonNode s : list) {
			String id = s.path("id").asString("");
			String key = s.path("key").asString("");
			SectionRow target = current.stream()
					.filter(c -> c.id().toString().equals(id) || (id.isEmpty() && c.key().equals(key))).findFirst()
					.orElse(null);
			if (target == null || DefaultReports.REFERENCES_KEY.equals(target.key())) {
				continue; // a section that no longer exists; References are rebuilt anyway
			}
			Map<String, Object> doc = json.readValue(s.path("contentDoc").toString(), MAP);
			store.restore(target.id(), doc, ReportDocs.markdown(doc));
		}
		normalize(r.id(), r.runId());
		int created = store.snapshot(r.id(), ownerId);
		return new Restored(created, store.sections(r.id()).stream()
				.map(s -> new SectionView(s.id(), s.key(), s.title(), s.contentDoc(), s.version())).toList());
	}

	/**
	 * Numbers, labels, References and Markdown recomputed from the current text (L8). Call inside the transaction
	 * that holds {@link ReportStore#lock}.
	 */
	@Transactional
	public void normalize(UUID reportId, UUID runId) {
		List<SectionRow> rows = store.sections(reportId);
		List<SectionRow> body = rows.stream().filter(s -> !DefaultReports.REFERENCES_KEY.equals(s.key())).toList();
		Set<UUID> cited = new LinkedHashSet<>();
		body.forEach(s -> TipTapDoc.citations(s.contentDoc()).forEach(c -> {
			try {
				cited.add(UUID.fromString(String.valueOf(c.get("claimId"))));
			}
			catch (IllegalArgumentException e) {
				// not a claim id: the renumbering removes it
			}
		}));
		Map<UUID, ClaimInfo> info = new LinkedHashMap<>();
		for (ReportClaim c : claims.reportClaims(runId, List.copyOf(cited))) {
			Set<UUID> others = new HashSet<>();
			verdicts(c.labelReasonJson()).forEach(v -> others.add(UUID.fromString((String) v.get("sourceId"))));
			info.put(c.id(), new ClaimInfo(c.sourceId(), others, c.label()));
		}
		Renumbered renumbered = ReportDocs.renumber(body.stream().map(SectionRow::contentDoc).toList(), info);
		for (int i = 0; i < body.size(); i++) {
			SectionRow s = body.get(i);
			Map<String, Object> doc = renumbered.docs().get(i);
			String md = ReportDocs.markdown(doc);
			if (!Objects.equals(json.valueToTree(doc), json.valueToTree(s.contentDoc())) || !md.equals(s.contentMd())) {
				store.saveDerived(s.id(), doc, md);
			}
		}
		Map<UUID, SourceInfo> sourceInfo = new LinkedHashMap<>();
		sources.describe(List.copyOf(renumbered.numbers().keySet())).forEach(s -> sourceInfo.put(s.id(), s));
		List<String> refs = DefaultReports.referenceLines(renumbered.numbers(), sourceInfo);
		Map<String, Object> refDoc = TipTapDoc.paragraphs(refs);
		String refMd = String.join("\n\n", refs);
		rows.stream().filter(s -> DefaultReports.REFERENCES_KEY.equals(s.key())).findFirst().ifPresent(s -> {
			if (!Objects.equals(json.valueToTree(refDoc), json.valueToTree(s.contentDoc())) || !refMd.equals(s.contentMd())) {
				store.saveDerived(s.id(), refDoc, refMd);
			}
		});
	}

	/** Labels changed (Find better sources): numbers stay, labels in the citation nodes follow. */
	@Transactional
	public void refreshLabels(UUID reportId) {
		ReportRow r = store.findById(reportId).orElse(null);
		if (r == null) {
			return;
		}
		store.lock(r.id());
		normalize(r.id(), r.runId());
	}

	/** The numbers the report uses now, by source (for citations in an Assist suggestion). */
	@Transactional(readOnly = true)
	public Map<UUID, Integer> numbers(UUID reportId) {
		Map<UUID, Integer> n = new LinkedHashMap<>();
		for (SectionRow s : store.sections(reportId)) {
			for (Map<String, Object> c : TipTapDoc.citations(s.contentDoc())) {
				if (c.get("sourceId") instanceof String src && c.get("n") instanceof Number num) {
					n.putIfAbsent(UUID.fromString(src), num.intValue());
				}
			}
		}
		return n;
	}

	// ------------------------------------------------------------------ helpers

	static ApiException conflict(int currentVersion) {
		return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Changed meanwhile",
				"This section was saved somewhere else meanwhile. Reload it to see the latest text.")
				.withProperty("currentVersion", Integer.toString(currentVersion));
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> verdicts(String labelReasonJson) {
		if (labelReasonJson == null) {
			return List.of();
		}
		Object v = json.readValue(labelReasonJson, MAP).get("verdicts");
		if (!(v instanceof List<?> l)) {
			return List.of();
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object o : l) {
			if (o instanceof Map<?, ?> m && m.get("sourceId") instanceof String) {
				out.add((Map<String, Object>) m);
			}
		}
		return out;
	}

	private static SourceView view(SourceInfo s) {
		return new SourceView(s.title(), s.site(), s.publishedAt(), s.type(), s.tier(), s.tierReason(), s.url());
	}

}
