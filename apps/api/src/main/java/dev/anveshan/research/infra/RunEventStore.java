package dev.anveshan.research.infra;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code run_event}: the ordered SSE replay log (E-FR-1). The bigserial id is the SSE {@code id:}. */
@Repository
public class RunEventStore {

	public record EventRow(long id, String type, String payloadJson) {
	}

	/** At most this many rows per read, so a reconnect after a long gap is streamed in pages. */
	static final int PAGE = 500;

	private final JdbcClient jdbc;

	RunEventStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Per-run lock held until the transaction ends. Event ids come from a sequence at INSERT time, but SSE streams
	 * only move forward ({@code id > lastSent}): if two transactions of one run interleave, the later-numbered one
	 * may commit first and the earlier ids would be skipped for ever. Serializing a run's event writers makes its
	 * ids appear in commit order. Anything that also locks the run row takes this lock first (no deadlock).
	 */
	static final String RUN_LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:run AS text), 0))";

	public long append(UUID runId, String type, String payloadJson, Instant now) {
		jdbc.sql(RUN_LOCK).param("run", runId).query().singleRow();
		return jdbc.sql("""
				INSERT INTO run_event (run_id, type, payload, created_at)
				VALUES (:run, :type, CAST(:payload AS jsonb), :now) RETURNING id""")
				.param("run", runId).param("type", type).param("payload", payloadJson).param("now", RunStore.ts(now))
				.query(Long.class).single();
	}

	/** Events after {@code lastId}, oldest first. */
	public List<EventRow> after(UUID runId, long lastId) {
		return jdbc.sql("""
				SELECT id, type, payload::text AS payload FROM run_event
				 WHERE run_id = :run AND id > :last ORDER BY id LIMIT :page""")
				.param("run", runId).param("last", lastId).param("page", PAGE)
				.query((rs, n) -> new EventRow(rs.getLong("id"), rs.getString("type"), rs.getString("payload"))).list();
	}

}
