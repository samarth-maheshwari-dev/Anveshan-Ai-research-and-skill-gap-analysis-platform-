package dev.anveshan.sources.infra;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import dev.anveshan.sources.api.ClaimLibrary.Claim;
import dev.anveshan.sources.api.ClaimLibrary.Passage;

/** SQL for claims, run_claim and verdicts (TECHSPEC §7.6). Run-scoped reads honour the private-source rule. */
@Component
public class ClaimStore {

	/** One verdict of a run with the credibility tier of the passage's source. */
	public record VerdictRow(UUID claimId, UUID chunkId, UUID sourceId, String verdict, String tier) {
	}

	private static final String CLAIM_COLUMNS = "cl.id, cl.chunk_id, cl.source_id, cl.text, cl.quote, cl.quote_start, cl.quote_end";

	private final JdbcClient jdbc;

	ClaimStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Passage> passages(List<UUID> chunkIds) {
		if (chunkIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT c.id, c.source_id, c.text, d.title, d.credibility_tier
				  FROM source_chunk c JOIN source_document d ON d.id = c.source_id
				 WHERE c.id IN (:ids)""")
				.param("ids", chunkIds)
				.query((rs, n) -> new Passage(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
						rs.getString(4), rs.getString(5)))
				.list();
	}

	public List<Claim> cached(UUID chunkId, String promptVersion) {
		return jdbc.sql("SELECT " + CLAIM_COLUMNS + " FROM claim cl WHERE cl.chunk_id = :c AND cl.prompt_version = :v ORDER BY cl.created_at, cl.id")
				.param("c", chunkId).param("v", promptVersion).query(this::claim).list();
	}

	public UUID insertClaim(UUID chunkId, UUID sourceId, String text, String quote, int start, int end,
			String promptVersion, float[] embedding) {
		return jdbc.sql("""
				INSERT INTO claim (source_id, chunk_id, text, quote, quote_start, quote_end, prompt_version, embedding)
				VALUES (:s, :c, :t, :q, :qs, :qe, :v, CAST(:e AS vector)) RETURNING id""")
				.param("s", sourceId).param("c", chunkId).param("t", text).param("q", quote).param("qs", start)
				.param("qe", end).param("v", promptVersion).param("e", SourceStore.vector(embedding))
				.query(UUID.class).single();
	}

	public void link(UUID runId, UUID claimId, UUID subQuestionId) {
		jdbc.sql("""
				INSERT INTO run_claim (run_id, claim_id, sub_question_id) VALUES (:r, :c, :sq)
				ON CONFLICT (run_id, claim_id) DO NOTHING""")
				.param("r", runId).param("c", claimId).param("sq", subQuestionId).update();
	}

	public List<Claim> runClaims(UUID runId) {
		// (a text block would strip the space before FROM)
		return jdbc.sql("SELECT " + CLAIM_COLUMNS + " FROM run_claim rc JOIN claim cl ON cl.id = rc.claim_id"
				+ " WHERE rc.run_id = :r ORDER BY cl.created_at, cl.id").param("r", runId).query(this::claim).list();
	}

	public List<Claim> claims(List<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("SELECT " + CLAIM_COLUMNS + " FROM claim cl WHERE cl.id IN (:ids) ORDER BY cl.created_at, cl.id")
				.param("ids", ids).query(this::claim).list();
	}

	/** Most similar chunks of OTHER sources the run USES (private ones only if the run's owner owns them). */
	public List<Passage> similarOtherSources(UUID runId, UUID claimId, UUID claimSourceId, int k) {
		return jdbc.sql("""
				SELECT c.id, c.source_id, c.text, d.title, d.credibility_tier
				  FROM source_chunk c
				  JOIN run_source rs ON rs.source_id = c.source_id AND rs.run_id = :run AND rs.status = 'USED'
				  JOIN source_document d ON d.id = c.source_id
				  JOIN research_run r ON r.id = rs.run_id
				 WHERE c.source_id <> :src AND (d.visibility = 'PUBLIC' OR d.owner_id = r.owner_id)
				 ORDER BY c.embedding <=> (SELECT embedding FROM claim WHERE id = :claim)
				 LIMIT :k""")
				.param("run", runId).param("src", claimSourceId).param("claim", claimId).param("k", k)
				.query((rs, n) -> new Passage(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
						rs.getString(4), rs.getString(5)))
				.list();
	}

	public void saveVerdict(UUID runId, UUID claimId, UUID chunkId, String verdict, String reason) {
		jdbc.sql("""
				INSERT INTO verdict (run_id, claim_id, chunk_id, verdict, reason) VALUES (:r, :c, :k, :v, :why)
				ON CONFLICT (run_id, claim_id, chunk_id) DO UPDATE SET verdict = EXCLUDED.verdict, reason = EXCLUDED.reason""")
				.param("r", runId).param("c", claimId).param("k", chunkId).param("v", verdict).param("why", reason).update();
	}

	public List<VerdictRow> verdicts(UUID runId) {
		return jdbc.sql("""
				SELECT v.claim_id, v.chunk_id, c.source_id, v.verdict, d.credibility_tier
				  FROM verdict v JOIN source_chunk c ON c.id = v.chunk_id JOIN source_document d ON d.id = c.source_id
				 WHERE v.run_id = :r""")
				.param("r", runId)
				.query((rs, n) -> new VerdictRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
						rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5)))
				.list();
	}

	public List<UUID> runClaimIds(UUID runId) {
		return jdbc.sql("SELECT claim_id FROM run_claim WHERE run_id = :r").param("r", runId).query(UUID.class).list();
	}

	public void setLabel(UUID runId, UUID claimId, String label, String reasonJson) {
		jdbc.sql("UPDATE run_claim SET trust_label = :l, label_reason = CAST(:why AS jsonb) WHERE run_id = :r AND claim_id = :c")
				.param("l", label).param("why", reasonJson).param("r", runId).param("c", claimId).update();
	}

	private Claim claim(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Claim(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
				rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7));
	}

}
