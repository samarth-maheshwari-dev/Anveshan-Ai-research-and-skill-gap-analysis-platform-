package dev.anveshan.research.infra;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.anveshan.research.domain.ResearchEnums.PauseReason;
import dev.anveshan.research.domain.ResearchEnums.RunStatus;
import dev.anveshan.research.domain.RunStateMachine;

/** {@code research_run} via SQL. Status changes are atomic and guarded by the state machine in the WHERE clause. */
@Repository
public class RunStore {

	public record RunRow(UUID id, UUID ownerId, String topic, String configJson, RunStatus status,
			PauseReason pauseReason, Instant resumeAt, BigDecimal progress, String estimateJson, int version,
			Instant createdAt, Instant updatedAt, Instant completedAt) {
	}

	private static final String COLUMNS = "id, owner_id, topic, config::text AS config, status, pause_reason, resume_at, "
			+ "progress, estimate::text AS estimate, version, created_at, updated_at, completed_at";

	private final JdbcClient jdbc;

	RunStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(UUID id, UUID owner, String topic, String configJson, String idempotencyKey, Instant now) {
		jdbc.sql("""
				INSERT INTO research_run (id, owner_id, topic, config, idempotency_key, created_at, updated_at)
				VALUES (:id, :owner, :topic, CAST(:config AS jsonb), :key, :now, :now)""")
				.param("id", id).param("owner", owner).param("topic", topic).param("config", configJson)
				.param("key", idempotencyKey).param("now", ts(now)).update();
	}

	public Optional<UUID> findByIdempotencyKey(UUID owner, String key) {
		return jdbc.sql("SELECT id FROM research_run WHERE owner_id = :owner AND idempotency_key = :key")
				.param("owner", owner).param("key", key).query(UUID.class).optional();
	}

	/** A key is only remembered for a while (TECHSPEC §8.1: 24 h); after that it may start a new run. */
	public void forgetIdempotencyKeyOlderThan(UUID owner, String key, Instant cutoff) {
		jdbc.sql("""
				UPDATE research_run SET idempotency_key = NULL
				 WHERE owner_id = :owner AND idempotency_key = :key AND created_at < :cutoff""")
				.param("owner", owner).param("key", key).param("cutoff", ts(cutoff)).update();
	}

	/** Only the run's event lock (see {@link RunEventStore#RUN_LOCK}) — taken first by every engine transaction. */
	public void lockEvents(UUID id) {
		jdbc.sql(RunEventStore.RUN_LOCK).param("run", id).query().singleRow();
	}

	/** Scoped by owner in SQL (RULES rule 6): someone else's run is simply not found. */
	public Optional<RunRow> findOwned(UUID owner, UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM research_run WHERE id = :id AND owner_id = :owner")
				.param("id", id).param("owner", owner).query(RunStore::row).optional();
	}

	/** Engine-internal read by id (the run was already proven to exist / be the caller's). */
	public Optional<RunRow> find(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM research_run WHERE id = :id").param("id", id).query(RunStore::row)
				.optional();
	}

	/**
	 * Row lock for the rest of the current transaction: serializes stage advancement per run. Takes the run's event
	 * lock first ({@link RunEventStore#RUN_LOCK}) so the lock order is always event lock → row lock.
	 */
	public Optional<RunRow> lock(UUID id) {
		jdbc.sql(RunEventStore.RUN_LOCK).param("run", id).query().singleRow();
		return jdbc.sql("SELECT " + COLUMNS + " FROM research_run WHERE id = :id FOR NO KEY UPDATE").param("id", id)
				.query(RunStore::row).optional();
	}

	/**
	 * Moves the run to {@code to} only if its current status may move there (L5). Pause reason / resume time are
	 * set for PAUSED and cleared otherwise. Returns false if the run was in a status that can't move.
	 */
	public boolean move(UUID id, RunStatus to, PauseReason reason, Instant resumeAt, Instant now) {
		List<String> from = RunStateMachine.sources(to).stream().map(Enum::name).toList();
		return jdbc.sql("""
				UPDATE research_run
				   SET status = :to, pause_reason = :reason, resume_at = :resumeAt, version = version + 1,
				       updated_at = :now,
				       completed_at = CASE WHEN :to = 'COMPLETED' THEN :now ELSE completed_at END,
				       progress = CASE WHEN :to = 'COMPLETED' THEN 100 ELSE progress END
				 WHERE id = :id AND status IN (:from)""")
				.param("to", to.name()).param("reason", to == RunStatus.PAUSED && reason != null ? reason.name() : null)
				.param("resumeAt", to == RunStatus.PAUSED ? ts(resumeAt) : null).param("now", ts(now))
				.param("id", id).param("from", from).update() == 1;
	}

	/** Planning has no PAUSED state: an AI limit there only records when to try again (cleared by the next move). */
	public void waitUntil(UUID id, Instant resumeAt) {
		jdbc.sql("UPDATE research_run SET resume_at = :at WHERE id = :id AND status = 'PLANNING'").param("at", ts(resumeAt))
				.param("id", id).update();
	}

	/** Progress never goes backwards (later stages add steps, so done/total can dip). */
	public BigDecimal raiseProgress(UUID id, BigDecimal pct) {
		return jdbc.sql("""
				UPDATE research_run SET progress = GREATEST(progress, :pct) WHERE id = :id RETURNING progress""")
				.param("pct", pct).param("id", id).query(BigDecimal.class).single();
	}

	/** PAUSED(AI_LIMIT_REACHED) runs whose provider reset time has passed (AC-E2 auto-resume). */
	public List<UUID> dueForResume(Instant now) {
		return jdbc.sql("""
				SELECT id FROM research_run
				 WHERE status = 'PAUSED' AND pause_reason = 'AI_LIMIT_REACHED' AND resume_at IS NOT NULL
				   AND resume_at <= :now""")
				.param("now", ts(now)).query(UUID.class).list();
	}

	static Timestamp ts(Instant i) {
		return i == null ? null : Timestamp.from(i);
	}

	private static Instant instant(ResultSet rs, String col) throws SQLException {
		Timestamp t = rs.getTimestamp(col);
		return t == null ? null : t.toInstant();
	}

	private static RunRow row(ResultSet rs, int n) throws SQLException {
		String reason = rs.getString("pause_reason");
		return new RunRow(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getString("topic"),
				rs.getString("config"), RunStatus.valueOf(rs.getString("status")),
				reason == null ? null : PauseReason.valueOf(reason), instant(rs, "resume_at"),
				rs.getBigDecimal("progress"), rs.getString("estimate"), rs.getInt("version"),
				instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "completed_at"));
	}

}
