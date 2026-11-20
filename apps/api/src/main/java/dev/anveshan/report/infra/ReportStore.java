package dev.anveshan.report.infra;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SQL for reports (TECHSPEC §7.6). Reads are owner-scoped in the WHERE clause (RULES §0.6). */
@Component
public class ReportStore {

	public record ReportRow(UUID id, UUID runId, UUID ownerId, String title, String language, String citationStyle,
			Instant createdAt) {
	}

	public record SectionRow(UUID id, int position, String key, String title, Map<String, Object> contentDoc,
			String contentMd, int version) {
	}

	public record NewSection(String key, String title, Map<String, Object> contentDoc, String contentMd) {
	}

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final JdbcClient jdbc;
	private final JsonMapper json;

	ReportStore(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** Creates the run's report or refreshes it; returns its id. Call inside a transaction with {@link #replaceSections}. */
	public UUID upsert(UUID runId, UUID ownerId, String title, String language) {
		return jdbc.sql("""
				INSERT INTO report (run_id, owner_id, title, language) VALUES (:run, :owner, :title, :lang)
				ON CONFLICT (run_id) DO UPDATE SET title = EXCLUDED.title, language = EXCLUDED.language, updated_at = now()
				RETURNING id""")
				.param("run", runId).param("owner", ownerId).param("title", title).param("lang", language)
				.query(UUID.class).single();
	}

	/** The assembler's sections replace the old ones; the result is saved as the next version (SCHEMA §7 shape). */
	public void replaceSections(UUID reportId, List<NewSection> sections) {
		jdbc.sql("DELETE FROM report_section WHERE report_id = :r").param("r", reportId).update();
		int position = 0;
		for (NewSection s : sections) {
			jdbc.sql("""
					INSERT INTO report_section (report_id, position, key, title, content_doc, content_md)
					VALUES (:r, :p, :k, :t, CAST(:doc AS jsonb), :md)""")
					.param("r", reportId).param("p", (short) position++).param("k", s.key()).param("t", s.title())
					.param("doc", json.writeValueAsString(s.contentDoc())).param("md", s.contentMd()).update();
		}
		snapshot(reportId, null);
	}

	/**
	 * Saves every section as the report's next version: {@code {"sections":[{id,key,title,contentDoc,version}]}}
	 * (SCHEMA §7). Returns the new version number.
	 */
	public int snapshot(UUID reportId, UUID userId) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (SectionRow s : sections(reportId)) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", s.id().toString());
			m.put("key", s.key());
			m.put("title", s.title());
			m.put("contentDoc", s.contentDoc());
			m.put("version", s.version());
			rows.add(m);
		}
		return jdbc.sql("""
				INSERT INTO report_version (report_id, version, snapshot, created_by)
				SELECT :r, COALESCE(max(version), 0) + 1, CAST(:snap AS jsonb), :u FROM report_version WHERE report_id = :r
				RETURNING version""")
				.param("r", reportId).param("snap", json.writeValueAsString(Map.of("sections", rows))).param("u", userId)
				.query(Integer.class).single();
	}

	/** Serializes edits of one report (numbering touches every section): row lock until the transaction ends. */
	public void lock(UUID reportId) {
		jdbc.sql("SELECT id FROM report WHERE id = :r FOR UPDATE").param("r", reportId).query(UUID.class).single();
		jdbc.sql("UPDATE report SET updated_at = now() WHERE id = :r").param("r", reportId).update();
	}

	public Optional<SectionRow> section(UUID reportId, UUID sectionId) {
		return jdbc.sql("""
				SELECT id, position, key, title, content_doc::text, content_md, version
				  FROM report_section WHERE report_id = :r AND id = :s""")
				.param("r", reportId).param("s", sectionId).query(this::section).optional();
	}

	/**
	 * A user's change (SCHEMA §6.4): only when the section is still at {@code expectedVersion}.
	 *
	 * @return the new version, or empty when someone saved meanwhile (→ 409)
	 */
	public Optional<Integer> saveEdited(UUID sectionId, int expectedVersion, Map<String, Object> doc, String md) {
		return jdbc.sql("""
				UPDATE report_section SET content_doc = CAST(:doc AS jsonb), content_md = :md, version = version + 1
				 WHERE id = :s AND version = :v RETURNING version""")
				.param("doc", json.writeValueAsString(doc)).param("md", md).param("s", sectionId)
				.param("v", expectedVersion).query(Integer.class).optional();
	}

	/** Derived changes only (numbers, labels, references): the section's version stays — it is the user's token. */
	public void saveDerived(UUID sectionId, Map<String, Object> doc, String md) {
		jdbc.sql("UPDATE report_section SET content_doc = CAST(:doc AS jsonb), content_md = :md WHERE id = :s")
				.param("doc", json.writeValueAsString(doc)).param("md", md).param("s", sectionId).update();
	}

	/** Restore: the old content becomes a new version of the section (history kept, F-FR-6). */
	public void restore(UUID sectionId, Map<String, Object> doc, String md) {
		jdbc.sql("""
				UPDATE report_section SET content_doc = CAST(:doc AS jsonb), content_md = :md, version = version + 1
				 WHERE id = :s""")
				.param("doc", json.writeValueAsString(doc)).param("md", md).param("s", sectionId).update();
	}

	public record VersionRow(int version, Instant createdAt, UUID createdBy) {
	}

	/** Newest first, versions below {@code before} (null = from the newest); up to {@code limit + 1} rows. */
	public List<VersionRow> versions(UUID reportId, Integer before, int limit) {
		return jdbc.sql("""
				SELECT version, created_at, created_by FROM report_version
				 WHERE report_id = :r AND (CAST(:before AS int) IS NULL OR version < CAST(:before AS int))
				 ORDER BY version DESC LIMIT :limit""")
				.param("r", reportId).param("before", before).param("limit", limit + 1)
				.query((rs, n) -> new VersionRow(rs.getInt(1), rs.getTimestamp(2).toInstant(), rs.getObject(3, UUID.class)))
				.list();
	}

	public Optional<String> snapshotOf(UUID reportId, int version) {
		return jdbc.sql("SELECT snapshot::text FROM report_version WHERE report_id = :r AND version = :v")
				.param("r", reportId).param("v", version).query(String.class).optional();
	}

	public Optional<UUID> idForRun(UUID runId) {
		return jdbc.sql("SELECT id FROM report WHERE run_id = :r").param("r", runId).query(UUID.class).optional();
	}

	/** Internal (the caller already proved access, e.g. the child run of this report's owner). */
	public Optional<ReportRow> findById(UUID reportId) {
		return jdbc.sql("""
				SELECT id, run_id, owner_id, title, language, citation_style, created_at FROM report WHERE id = :id""")
				.param("id", reportId)
				.query((rs, n) -> new ReportRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
						rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6),
						rs.getTimestamp(7).toInstant()))
				.optional();
	}

	public Optional<ReportRow> findOwned(UUID ownerId, UUID reportId) {
		return jdbc.sql("""
				SELECT id, run_id, owner_id, title, language, citation_style, created_at
				  FROM report WHERE id = :id AND owner_id = :owner""")
				.param("id", reportId).param("owner", ownerId)
				.query((rs, n) -> new ReportRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
						rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6),
						rs.getTimestamp(7).toInstant()))
				.optional();
	}

	public List<SectionRow> sections(UUID reportId) {
		return jdbc.sql("""
				SELECT id, position, key, title, content_doc::text, content_md, version
				  FROM report_section WHERE report_id = :r ORDER BY position""")
				.param("r", reportId).query(this::section).list();
	}

	private SectionRow section(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new SectionRow(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4),
				json.readValue(rs.getString(5), MAP), rs.getString(6), rs.getInt(7));
	}

}
