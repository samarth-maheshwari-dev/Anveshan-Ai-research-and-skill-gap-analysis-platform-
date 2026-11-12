package dev.anveshan.research.infra;

import static dev.anveshan.research.infra.RunStore.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.domain.ResearchEnums.StepStatus;

/**
 * {@code research_step} via SQL (TECHSPEC §12.2). A step is claimed with one atomic UPDATE, so two workers can't
 * both run it. Every later write names the attempt it belongs to: a worker that lost its claim (crash recovery
 * re-queued the step) can no longer complete, release or fail it — a DONE step is never overwritten (L4).
 */
@Repository
public class StepStore {

	public record StepRow(UUID id, UUID runId, int seq, int stage, StepKind kind, UUID subQuestionId,
			String inputJson, String outputJson, StepStatus status, int attempts, String lastError, Instant startedAt,
			Instant finishedAt) {
	}

	public record StageCounts(int total, int open, int failed) {

		/** Every step is DONE or SKIPPED. */
		public boolean closed() {
			return total > 0 && open == 0 && failed == 0;
		}

	}

	public record Recovered(UUID stepId, UUID runId) {
	}

	private static final String COLUMNS = "id, run_id, seq, stage, kind, sub_question_id, input::text AS input, "
			+ "output::text AS output, status, attempts, last_error, started_at, finished_at";

	private final JdbcClient jdbc;

	StepStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Appends a step after the run's last one; {@code idempotency_key} = run + kind + seq. */
	public UUID insert(UUID runId, int stage, StepKind kind, UUID subQuestionId, String inputJson) {
		return jdbc.sql("""
				INSERT INTO research_step (run_id, seq, stage, kind, sub_question_id, input, idempotency_key)
				SELECT :run, n.seq, :stage, :kind, :sq, CAST(:input AS jsonb), :run || ':' || :kind || ':' || n.seq
				  FROM (SELECT COALESCE(MAX(seq), -1) + 1 AS seq FROM research_step WHERE run_id = :run) n
				RETURNING id""")
				.param("run", runId).param("stage", stage).param("kind", kind.name()).param("sq", subQuestionId)
				.param("input", inputJson).query(UUID.class).single();
	}

	/** PENDING → RUNNING for exactly one caller; empty if someone else has it or it's DONE/FAILED/SKIPPED. */
	public Optional<StepRow> claim(UUID stepId, Instant now) {
		return jdbc.sql("""
				UPDATE research_step SET status = 'RUNNING', attempts = attempts + 1, started_at = :now
				 WHERE id = :id AND status = 'PENDING'
				RETURNING\s""" + COLUMNS)
				.param("now", ts(now)).param("id", stepId).query(StepStore::row).optional();
	}

	/**
	 * The claim a worker holds: attempt number + claim time. Attempts alone isn't unique — "retry failed step"
	 * starts counting again — so a worker that lost its claim long ago can never match a newer one.
	 */
	private static final String OWNED = " AND status = 'RUNNING' AND attempts = :attempt AND started_at = :claimedAt";

	/** RUNNING (this claim) → DONE with its checkpoint. */
	public boolean complete(StepRow claim, String outputJson, Instant now) {
		return owned(jdbc.sql("""
				UPDATE research_step SET status = 'DONE', output = CAST(:out AS jsonb), finished_at = :now, last_error = NULL
				 WHERE id = :id""" + OWNED), claim).param("out", outputJson).param("now", ts(now)).update() == 1;
	}

	/** Gives the step back without spending an attempt (the run was paused/cancelled, or the AI limit hit). */
	public boolean release(StepRow claim) {
		return owned(jdbc.sql("""
				UPDATE research_step SET status = 'PENDING', attempts = attempts - 1, started_at = NULL
				 WHERE id = :id""" + OWNED), claim).update() == 1;
	}

	/** Failed but has attempts left: back to PENDING with the reason; the caller re-queues it after a backoff. */
	/** {@code finished_at} records when the attempt ended, so the periodic re-queue leaves the backoff alone. */
	public boolean retryLater(StepRow claim, String safeError, Instant now) {
		return owned(jdbc.sql("""
				UPDATE research_step SET status = 'PENDING', started_at = NULL, last_error = :err, finished_at = :now
				 WHERE id = :id""" + OWNED), claim).param("err", safeError).param("now", ts(now)).update() == 1;
	}

	public boolean failFinal(StepRow claim, String safeError, Instant now) {
		return owned(jdbc.sql("""
				UPDATE research_step SET status = 'FAILED', last_error = :err, finished_at = :now
				 WHERE id = :id""" + OWNED), claim).param("err", safeError).param("now", ts(now)).update() == 1;
	}

	private static JdbcClient.StatementSpec owned(JdbcClient.StatementSpec sql, StepRow claim) {
		return sql.param("id", claim.id()).param("attempt", claim.attempts()).param("claimedAt", ts(claim.startedAt()));
	}

	/** "Retry failed step" (E-FR-4): FAILED → PENDING with a fresh set of attempts. */
	public boolean resetFailed(UUID runId, UUID stepId) {
		return jdbc.sql("""
				UPDATE research_step SET status = 'PENDING', attempts = 0, started_at = NULL, finished_at = NULL
				 WHERE id = :id AND run_id = :run AND status = 'FAILED'""")
				.param("id", stepId).param("run", runId).update() == 1;
	}

	/**
	 * Crash recovery (AC-E1): RUNNING steps that started before {@code cutoff} go back to PENDING. Their attempt
	 * counter is kept, so the crashed worker's attempt token no longer matches if it ever wakes up.
	 */
	public List<Recovered> recoverStale(Instant cutoff) {
		return jdbc.sql("""
				UPDATE research_step SET status = 'PENDING', started_at = NULL
				 WHERE status = 'RUNNING' AND started_at < :cutoff
				RETURNING id, run_id""")
				.param("cutoff", ts(cutoff))
				.query((rs, n) -> new Recovered(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class)))
				.list();
	}

	/**
	 * Runs that execute steps but have nothing in flight: the current stage has no PENDING/RUNNING step. Either
	 * the stage closed and nobody advanced it (a crash between saving DONE and advancing), or a step failed for
	 * good (e.g. during a pause). The sweep advances these.
	 */
	public List<UUID> idleRuns() {
		return jdbc.sql("""
				SELECT r.id FROM research_run r
				  JOIN LATERAL (SELECT MAX(stage) AS stage FROM research_step WHERE run_id = r.id) cur ON cur.stage IS NOT NULL
				 WHERE r.status IN ('PLANNING','RUNNING')
				   AND NOT EXISTS (SELECT 1 FROM research_step s WHERE s.run_id = r.id AND s.stage = cur.stage
				                      AND s.status IN ('PENDING','RUNNING'))""")
				.query(UUID.class).list();
	}

	/**
	 * PENDING steps of runs that execute steps and aren't meant to wait: not a run waiting for an AI-limit reset
	 * ({@code resume_at} ahead), not a step whose last attempt failed after {@code settledBefore} (its retry is
	 * scheduled with a backoff). Re-queued in case a crash or a DB error lost their job.
	 */
	public List<UUID> pendingOfActiveRuns(Instant now, Instant settledBefore) {
		return jdbc.sql("""
				SELECT s.id FROM research_step s JOIN research_run r ON r.id = s.run_id
				 WHERE r.status IN ('PLANNING','RUNNING') AND s.status = 'PENDING'
				   AND (r.resume_at IS NULL OR r.resume_at <= :now)
				   AND (s.finished_at IS NULL OR s.finished_at < :settled)
				 ORDER BY s.run_id, s.seq""")
				.param("now", ts(now)).param("settled", ts(settledBefore)).query(UUID.class).list();
	}

	public Optional<StepRow> find(UUID stepId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM research_step WHERE id = :id").param("id", stepId)
				.query(StepStore::row).optional();
	}

	public List<StepRow> forRun(UUID runId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM research_step WHERE run_id = :run ORDER BY seq")
				.param("run", runId).query(StepStore::row).list();
	}

	/** Highest materialized stage, or -1 if the run has no steps yet. */
	public int currentStage(UUID runId) {
		return jdbc.sql("SELECT COALESCE(MAX(stage), -1) FROM research_step WHERE run_id = :run").param("run", runId)
				.query(Integer.class).single();
	}

	public StageCounts counts(UUID runId, int stage) {
		return jdbc.sql("""
				SELECT count(*) AS total,
				       count(*) FILTER (WHERE status IN ('PENDING','RUNNING')) AS open,
				       count(*) FILTER (WHERE status = 'FAILED') AS failed
				  FROM research_step WHERE run_id = :run AND stage = :stage""")
				.param("run", runId).param("stage", stage)
				.query((rs, n) -> new StageCounts(rs.getInt("total"), rs.getInt("open"), rs.getInt("failed"))).single();
	}

	public List<UUID> pendingIn(UUID runId, int stage) {
		return jdbc.sql("""
				SELECT id FROM research_step WHERE run_id = :run AND stage = :stage AND status = 'PENDING' ORDER BY seq""")
				.param("run", runId).param("stage", stage).query(UUID.class).list();
	}

	/** The checkpoint of the run's DONE step of {@code kind} for one sub-question (e.g. SEARCH → FETCH reads it). */
	public Optional<String> doneOutput(UUID runId, StepKind kind, UUID subQuestionId) {
		return jdbc.sql("""
				SELECT output::text FROM research_step
				 WHERE run_id = :run AND kind = :kind AND sub_question_id = :sq AND status = 'DONE'
				 ORDER BY seq DESC LIMIT 1""")
				.param("run", runId).param("kind", kind.name()).param("sq", subQuestionId).query(String.class).optional();
	}

	public Optional<UUID> runOf(UUID stepId) {
		return jdbc.sql("SELECT run_id FROM research_step WHERE id = :id").param("id", stepId).query(UUID.class)
				.optional();
	}

	private static Instant instant(ResultSet rs, String col) throws SQLException {
		Timestamp t = rs.getTimestamp(col);
		return t == null ? null : t.toInstant();
	}

	private static StepRow row(ResultSet rs, int n) throws SQLException {
		return new StepRow(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class), rs.getInt("seq"),
				rs.getInt("stage"), StepKind.valueOf(rs.getString("kind")), rs.getObject("sub_question_id", UUID.class),
				rs.getString("input"), rs.getString("output"), StepStatus.valueOf(rs.getString("status")),
				rs.getInt("attempts"), rs.getString("last_error"), instant(rs, "started_at"),
				instant(rs, "finished_at"));
	}

}
