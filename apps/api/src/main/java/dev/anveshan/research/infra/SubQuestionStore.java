package dev.anveshan.research.infra;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.anveshan.research.domain.PlanRules.PlannedQuestion;
import dev.anveshan.research.domain.RunOptions.SourceType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code sub_question}: the plan (Module D). Only ever replaced as a whole while the run awaits approval (L6) —
 * no step references a sub-question before approval, so delete + insert is safe. Array columns are filled from JSON
 * so no user text is ever spliced into SQL.
 */
@Repository
public class SubQuestionStore {

	public record SubQuestionRow(UUID id, int position, String text, List<SourceType> sourceTypes, List<String> queries) {
	}

	private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
	};

	private final JdbcClient jdbc;
	private final JsonMapper json;

	SubQuestionStore(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * Replaces the run's plan. {@code ids} keeps the id of a card the user only edited (same size as the plan; a null
	 * entry gets a new id). Call inside the transaction that checked the run's status and version.
	 */
	public void replace(UUID runId, List<PlannedQuestion> plan, List<UUID> ids) {
		jdbc.sql("DELETE FROM sub_question WHERE run_id = :run").param("run", runId).update();
		for (int i = 0; i < plan.size(); i++) {
			PlannedQuestion q = plan.get(i);
			UUID id = ids != null && i < ids.size() && ids.get(i) != null ? ids.get(i) : UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO sub_question (id, run_id, position, text, source_types, queries)
					VALUES (:id, :run, :pos, :text,
					        ARRAY(SELECT jsonb_array_elements_text(CAST(:types AS jsonb))),
					        ARRAY(SELECT jsonb_array_elements_text(CAST(:queries AS jsonb))))""")
					.param("id", id).param("run", runId).param("pos", i).param("text", q.text())
					.param("types", json.writeValueAsString(q.sourceTypes().stream().map(Enum::name).toList()))
					.param("queries", json.writeValueAsString(q.queries())).update();
		}
	}

	public List<SubQuestionRow> forRun(UUID runId) {
		return jdbc.sql("""
				SELECT id, position, text, array_to_json(source_types)::text AS types, array_to_json(queries)::text AS queries
				  FROM sub_question WHERE run_id = :run ORDER BY position""")
				.param("run", runId).query((rs, n) -> {
					List<SourceType> types = new ArrayList<>();
					for (String t : json.readValue(rs.getString("types"), STRINGS)) {
						types.add(SourceType.valueOf(t));
					}
					return new SubQuestionRow(rs.getObject("id", UUID.class), rs.getInt("position"), rs.getString("text"),
							List.copyOf(types), json.readValue(rs.getString("queries"), STRINGS));
				}).list();
	}

	public int count(UUID runId) {
		return jdbc.sql("SELECT count(*) FROM sub_question WHERE run_id = :run").param("run", runId)
				.query(Integer.class).single();
	}

}
