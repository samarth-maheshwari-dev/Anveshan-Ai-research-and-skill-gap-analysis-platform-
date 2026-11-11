package dev.anveshan.research.infra;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code template}: report templates are data (C-FR-5) — public, the same for everyone. */
@Repository
public class TemplateStore {

	public record TemplateRow(UUID id, String code, String name, String formDefaultsJson, String sectionsJson,
			String writerInstructions, int version) {
	}

	private static final String COLUMNS = "id, code, name, form_defaults::text AS form_defaults, sections::text AS sections, "
			+ "writer_instructions, version";

	private final JdbcClient jdbc;

	TemplateStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<TemplateRow> all() {
		return jdbc.sql("SELECT " + COLUMNS + " FROM template ORDER BY name").query(TemplateStore::row).list();
	}

	public Optional<TemplateRow> byCode(String code) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM template WHERE code = :code").param("code", code)
				.query(TemplateStore::row).optional();
	}

	public Optional<TemplateRow> byId(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM template WHERE id = :id").param("id", id).query(TemplateStore::row)
				.optional();
	}

	private static TemplateRow row(ResultSet rs, int n) throws SQLException {
		return new TemplateRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
				rs.getString("form_defaults"), rs.getString("sections"), rs.getString("writer_instructions"),
				rs.getInt("version"));
	}

}
