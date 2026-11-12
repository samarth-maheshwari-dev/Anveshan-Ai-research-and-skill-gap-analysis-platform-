package dev.anveshan.report.infra;

import java.time.Instant;
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
		jdbc.sql("""
				INSERT INTO report_version (report_id, version, snapshot)
				SELECT :r, COALESCE(max(version), 0) + 1, CAST(:snap AS jsonb) FROM report_version WHERE report_id = :r""")
				.param("r", reportId).param("snap", json.writeValueAsString(sections)).update();
	}

	public Optional<UUID> idForRun(UUID runId) {
		return jdbc.sql("SELECT id FROM report WHERE run_id = :r").param("r", runId).query(UUID.class).optional();
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
				.param("r", reportId)
				.query((rs, n) -> new SectionRow(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3),
						rs.getString(4), json.readValue(rs.getString(5), MAP), rs.getString(6), rs.getInt(7)))
				.list();
	}

}
