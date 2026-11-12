package dev.anveshan.sources.infra;

import java.sql.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import dev.anveshan.sources.api.SourceLibrary.RelevantChunk;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL for sources (TECHSPEC §7.6). Every read that can return private data is scoped in the WHERE clause (RULES
 * §0.6): public lookups match PUBLIC rows only; linking and relevance check the run's owner.
 */
@Component
public class SourceStore {

	public record StoredSource(UUID id, String title, String url, String tier, String tierReason, int chunks) {
	}

	public record NewDocument(String visibility, UUID ownerId, String canonicalUrl, String doi, UUID uploadId,
			byte[] contentHash, String title, List<String> authors, String site, java.time.LocalDate publishedAt,
			String type, String tier, String tierReason, java.util.Map<String, Object> metadata, String text) {
	}

	public record NewChunk(int position, String text, int start, int end, Integer page, float[] embedding) {
	}

	private final JdbcClient jdbc;
	private final JsonMapper json;

	SourceStore(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** A PUBLIC source with this canonical URL or DOI (never a private one). */
	public Optional<StoredSource> findPublic(String canonicalUrl, String doi) {
		return jdbc.sql("""
				SELECT d.id, d.title, d.canonical_url, d.credibility_tier, d.credibility_reason,
				       (SELECT count(*) FROM source_chunk c WHERE c.source_id = d.id) AS chunks
				  FROM source_document d
				 WHERE d.visibility = 'PUBLIC'
				   AND ((CAST(:url AS text) IS NOT NULL AND d.canonical_url = :url)
				     OR (CAST(:doi AS text) IS NOT NULL AND lower(d.doi) = lower(:doi)))
				 LIMIT 1""")
				.param("url", canonicalUrl).param("doi", doi)
				.query((rs, n) -> new StoredSource(rs.getObject("id", UUID.class), rs.getString("title"),
						rs.getString("canonical_url"), rs.getString("credibility_tier"), rs.getString("credibility_reason"),
						rs.getInt("chunks")))
				.optional();
	}

	/** Inserts the document and its chunks; call inside one transaction. */
	public UUID insert(NewDocument d, List<NewChunk> chunks) {
		UUID id = jdbc.sql("""
				INSERT INTO source_document (visibility, owner_id, canonical_url, doi, upload_id, content_hash, title,
				       authors, site, published_at, type, credibility_tier, credibility_reason, metadata, text)
				VALUES (:visibility, :owner, :url, :doi, :upload, :hash, :title,
				       ARRAY(SELECT jsonb_array_elements_text(CAST(:authors AS jsonb))), :site, :published, :type,
				       :tier, :reason, CAST(:metadata AS jsonb), :text)
				RETURNING id""")
				.param("visibility", d.visibility()).param("owner", d.ownerId()).param("url", d.canonicalUrl())
				.param("doi", d.doi()).param("upload", d.uploadId()).param("hash", d.contentHash())
				.param("title", d.title()).param("authors", json.writeValueAsString(d.authors()))
				.param("site", d.site()).param("published", d.publishedAt() == null ? null : Date.valueOf(d.publishedAt()))
				.param("type", d.type()).param("tier", d.tier()).param("reason", d.tierReason())
				.param("metadata", json.writeValueAsString(d.metadata())).param("text", d.text())
				.query(UUID.class).single();
		for (NewChunk c : chunks) {
			jdbc.sql("""
					INSERT INTO source_chunk (source_id, position, text, char_start, char_end, page, embedding)
					VALUES (:source, :position, :text, :start, :end, :page, CAST(:embedding AS vector))""")
					.param("source", id).param("position", c.position()).param("text", c.text())
					.param("start", c.start()).param("end", c.end()).param("page", c.page())
					.param("embedding", vector(c.embedding())).update();
		}
		return id;
	}

	public UUID insertUpload(UUID owner, String filename, String contentType, long size, byte[] sha256, String purpose) {
		return jdbc.sql("""
				INSERT INTO upload (owner_id, object_key, filename, content_type, size_bytes, sha256, purpose)
				VALUES (:owner, :key, :filename, :type, :size, :sha, :purpose) RETURNING id""")
				.param("owner", owner).param("key", "text-only/" + UUID.randomUUID()).param("filename", filename)
				.param("type", contentType).param("size", size).param("sha", sha256).param("purpose", purpose)
				.query(UUID.class).single();
	}

	/**
	 * Links a source to a run of {@code owner}: the run must be theirs, and a PRIVATE source must be theirs too.
	 * Already linked → no change.
	 */
	public boolean link(UUID owner, UUID runId, UUID sourceId, UUID subQuestionId, int rank) {
		return jdbc.sql("""
				INSERT INTO run_source (run_id, source_id, sub_question_id, status, rank)
				SELECT :run, d.id, :sq, 'USED', :rank
				  FROM source_document d
				 WHERE d.id = :source AND (d.visibility = 'PUBLIC' OR d.owner_id = :owner)
				   AND EXISTS (SELECT 1 FROM research_run r WHERE r.id = :run AND r.owner_id = :owner)
				ON CONFLICT (run_id, source_id) DO NOTHING""")
				.param("run", runId).param("sq", subQuestionId).param("rank", (short) Math.min(rank, Short.MAX_VALUE))
				.param("source", sourceId).param("owner", owner).update() == 1;
	}

	/**
	 * Nearest chunks of the run's USED sources (cosine). Private sources count only when they belong to the run's
	 * owner — a second guard behind {@link #link}.
	 */
	public List<RelevantChunk> topChunks(UUID runId, float[] query, int k) {
		return jdbc.sql("""
				SELECT c.id, c.source_id, c.position, c.page, 1 - (c.embedding <=> CAST(:q AS vector)) AS similarity
				  FROM source_chunk c
				  JOIN run_source rs ON rs.source_id = c.source_id AND rs.run_id = :run AND rs.status = 'USED'
				  JOIN source_document d ON d.id = c.source_id
				  JOIN research_run r ON r.id = rs.run_id
				 WHERE d.visibility = 'PUBLIC' OR d.owner_id = r.owner_id
				 ORDER BY c.embedding <=> CAST(:q AS vector)
				 LIMIT :k""")
				.param("q", vector(query)).param("run", runId).param("k", k)
				.query((rs, n) -> new RelevantChunk(rs.getObject("id", UUID.class), rs.getObject("source_id", UUID.class),
						rs.getInt("position"), (Integer) rs.getObject("page"),
						Math.round(rs.getDouble("similarity") * 10_000) / 10_000.0))
				.list();
	}

	public List<dev.anveshan.sources.api.SourceLibrary.SourceInfo> describe(List<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT id, title, array_to_json(authors)::text, site, published_at, canonical_url, doi, type,
				       credibility_tier, credibility_reason
				  FROM source_document WHERE id IN (:ids)""")
				.param("ids", ids)
				.query((rs, n) -> new dev.anveshan.sources.api.SourceLibrary.SourceInfo(rs.getObject(1, UUID.class),
						rs.getString(2), List.of(json.readValue(rs.getString(3), String[].class)), rs.getString(4),
						rs.getObject(5, java.time.LocalDate.class), rs.getString(6), rs.getString(7), rs.getString(8),
						rs.getString(9), rs.getString(10)))
				.list();
	}

	public List<dev.anveshan.sources.api.SourceLibrary.RunSource> runSources(UUID runId) {
		return jdbc.sql("""
				SELECT d.id, d.title, d.site, d.canonical_url, d.type, d.credibility_tier, d.credibility_reason,
				       rs.status, rs.rank, rs.sub_question_id,
				       (SELECT count(*) FROM run_claim rc JOIN claim cl ON cl.id = rc.claim_id
				         WHERE rc.run_id = rs.run_id AND cl.source_id = d.id) AS claims
				  FROM run_source rs JOIN source_document d ON d.id = rs.source_id
				  JOIN research_run r ON r.id = rs.run_id
				 WHERE rs.run_id = :run AND (d.visibility = 'PUBLIC' OR d.owner_id = r.owner_id)
				 ORDER BY rs.rank NULLS LAST, d.title""")
				.param("run", runId)
				.query((rs, n) -> new dev.anveshan.sources.api.SourceLibrary.RunSource(rs.getObject(1, UUID.class),
						rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
						rs.getString(7), rs.getString(8), (Integer) rs.getObject(9) == null ? null : rs.getInt(9),
						rs.getObject(10, UUID.class), rs.getInt(11)))
				.list();
	}

	/** USED → REJECTED_BY_USER for one source of one run. */
	public boolean skip(UUID runId, UUID sourceId) {
		return jdbc.sql("UPDATE run_source SET status = 'REJECTED_BY_USER' WHERE run_id = :r AND source_id = :s AND status = 'USED'")
				.param("r", runId).param("s", sourceId).update() == 1;
	}

	static String vector(float[] v) {
		StringBuilder b = new StringBuilder(v.length * 10).append('[');
		for (int i = 0; i < v.length; i++) {
			if (i > 0) {
				b.append(',');
			}
			b.append(v[i]);
		}
		return b.append(']').toString();
	}

}
