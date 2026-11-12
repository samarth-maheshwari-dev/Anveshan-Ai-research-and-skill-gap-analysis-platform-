package dev.anveshan.sources.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.anveshan.TestcontainersConfig;
import dev.anveshan.sources.FakeSite;
import dev.anveshan.sources.api.SourceLibrary;
import dev.anveshan.sources.api.SourceLibrary.IngestResult;
import dev.anveshan.sources.api.SourceLibrary.Outcome;
import dev.anveshan.sources.api.SourceLibrary.RelevantChunk;
import dev.anveshan.sources.api.SourceLibrary.UploadPurpose;
import dev.anveshan.sources.api.SourceSearch.Candidate;
import dev.anveshan.sources.api.SourceSearch.CandidateType;

/** P12 fetch → clean → chunk → embed → store, against a local fake website and a real pgvector database. */
@SpringBootTest(properties = "management.server.port=0")
@Import(TestcontainersConfig.class)
class SourceLibraryIT {

	static final FakeSite SITE = new FakeSite();

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		SITE.register(r);
	}

	@Autowired
	SourceLibrary library;

	@Autowired
	JdbcClient jdbc;

	UUID alice;

	UUID bob;

	@BeforeEach
	void setUp() {
		alice = user("alice");
		bob = user("bob");
	}

	Candidate web(String path) {
		return new Candidate("Lost and found apps compared", SITE.url(path), null, "A comparison.", LocalDate.of(2025, 6, 1),
				CandidateType.WEB_PAGE, "searxng", 0.8);
	}

	@Test
	@DisplayName("T-12-01 HTML article: main text kept; nav, header, footer, sidebar and scripts removed; metadata read")
	void html() {
		IngestResult r = library.ingest(web("/article.html"));
		assertThat(r.outcome()).isEqualTo(Outcome.NEW);
		assertThat(r.chunks()).isPositive();
		String text = text(r.sourceId());
		assertThat(text).contains(FakeSite.ARTICLE_TEXT, "The office keeps items for 30 days.");
		assertThat(text).doesNotContain("NAVIGATION MENU", "SITE HEADER", "FOOTER COPYRIGHT", "SIDEBAR ADVERT",
				"SCRIPT TEXT", "console.log", "color:red");
		var row = jdbc.sql("SELECT visibility, owner_id, type, credibility_tier, credibility_reason, site, authors FROM source_document WHERE id = :id")
				.param("id", r.sourceId()).query((rs, n) -> List.of(rs.getString(1), String.valueOf(rs.getObject(2)),
						rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)))
				.single();
		assertThat(row).containsExactly("PUBLIC", "null", "OTHER", "LOW", "Personal blog / unknown author", "127.0.0.1",
				"{\"R. Sharma\"}");
		// every chunk is an exact slice of the stored text (L1 groundwork)
		assertThat(jdbc.sql("""
				SELECT count(*) FROM source_chunk c JOIN source_document d ON d.id = c.source_id
				 WHERE d.id = :id AND substring(d.text FROM c.char_start + 1 FOR c.char_end - c.char_start) <> c.text""")
				.param("id", r.sourceId()).query(Long.class).single()).isZero();
	}

	@Test
	@DisplayName("T-12-02 3-page PDF: text from every page, page numbers on the chunks")
	void pdf() {
		Candidate c = new Candidate("QR code recovery", SITE.url("/paper.pdf"), "https://doi.org/10.5555/qr.2024",
				null, LocalDate.of(2024, 3, 1), CandidateType.PAPER, "openalex", 0.7);
		IngestResult r = library.ingest(c);
		assertThat(r.outcome()).as(r.reason()).isEqualTo(Outcome.NEW);
		assertThat(r.tier()).isEqualTo("HIGH");
		assertThat(r.tierReason()).isEqualTo("Peer-reviewed paper, 2024");
		assertThat(text(r.sourceId())).contains("Page 1 line 1", "Page 2 line 12", "Page 3 line 12");
		List<Integer> pages = jdbc.sql("SELECT page FROM source_chunk WHERE source_id = :id ORDER BY position")
				.param("id", r.sourceId()).query(Integer.class).list();
		assertThat(pages).first().isEqualTo(1);
		assertThat(pages).contains(3).isSorted();
		String chunkOnPage3 = jdbc.sql("SELECT text FROM source_chunk WHERE source_id = :id AND page = 3 ORDER BY position LIMIT 1")
				.param("id", r.sourceId()).query(String.class).single();
		assertThat(chunkOnPage3).contains("Page 3");
	}

	@Test
	@DisplayName("T-12-04 robots.txt disallows the path → UNAVAILABLE, the page itself is never requested")
	void robots() {
		IngestResult r = library.ingest(web("/private/secret.html"));
		assertThat(r.outcome()).isEqualTo(Outcome.UNAVAILABLE);
		assertThat(r.reason()).contains("robots.txt");
		assertThat(SITE.hits("/private/secret.html")).isZero();
		assertThat(SITE.hits("/robots.txt")).as("cached per host").isLessThanOrEqualTo(1);
		IngestResult empty = library.ingest(web("/empty.html"));
		assertThat(empty.outcome()).isEqualTo(Outcome.UNAVAILABLE);
		assertThat(empty.reason()).contains("almost no readable text");
		assertThat(library.ingest(web("/missing.html")).reason()).isEqualTo("The page couldn't be opened (HTTP 404).");
	}

	@Test
	@DisplayName("T-12-05 two runs read the same public URL → one source_document, two run_source rows, no second fetch/embedding")
	void reuse() {
		UUID runA = run(alice);
		UUID runB = run(bob);
		IngestResult first = library.ingest(web("/article-2.html"));
		long fetches = SITE.hits("/article-2.html");
		int chunks = chunkCount(first.sourceId());
		IngestResult second = library.ingest(web("/article-2.html"));

		assertThat(second.outcome()).isEqualTo(Outcome.REUSED);
		assertThat(second.sourceId()).isEqualTo(first.sourceId());
		assertThat(SITE.hits("/article-2.html")).as("not fetched again").isEqualTo(fetches);
		assertThat(chunkCount(first.sourceId())).as("not embedded again").isEqualTo(chunks);
		assertThat(library.useInRun(alice, runA, first.sourceId(), null, 1)).isTrue();
		assertThat(library.useInRun(bob, runB, second.sourceId(), null, 1)).isTrue();
		assertThat(library.useInRun(bob, runB, second.sourceId(), null, 2)).as("already linked").isFalse();
		assertThat(jdbc.sql("SELECT count(*) FROM source_document WHERE canonical_url = :u").param("u", SITE.url("/article-2.html"))
				.query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM run_source WHERE source_id = :s").param("s", first.sourceId())
				.query(Long.class).single()).isEqualTo(2);
		assertThat(library.useInRun(alice, runB, first.sourceId(), null, 1)).as("not alice's run").isFalse();
	}

	@Test
	@DisplayName("T-12-07 RELEVANCE returns only chunks of the run's USED sources, most similar first")
	void relevance() {
		UUID runA = run(alice);
		UUID runB = run(alice);
		UUID article = library.ingest(web("/article.html")).sourceId();
		UUID paper = library.ingest(new Candidate("QR code recovery", SITE.url("/paper.pdf"), "https://doi.org/10.5555/qr.2024",
				null, null, CandidateType.PAPER, "openalex", 0.7)).sourceId();
		library.useInRun(alice, runA, article, null, 1);
		library.useInRun(alice, runA, paper, null, 2);
		library.useInRun(alice, runB, paper, null, 1);

		List<RelevantChunk> a = library.topChunks(runA, "How do students report a lost item from their phone?", 5);
		assertThat(a).isNotEmpty().allMatch(c -> c.sourceId().equals(article) || c.sourceId().equals(paper));
		assertThat(a.get(0).sourceId()).as("the article answers this best").isEqualTo(article);
		assertThat(a).isSortedAccordingTo((x, y) -> Double.compare(y.similarity(), x.similarity()));

		List<RelevantChunk> b = library.topChunks(runB, "How do students report a lost item from their phone?", 50);
		assertThat(b).isNotEmpty().allMatch(c -> c.sourceId().equals(paper));

		jdbc.sql("UPDATE run_source SET status = 'REJECTED_BY_USER' WHERE run_id = :r AND source_id = :s")
				.param("r", runA).param("s", article).update();
		assertThat(library.topChunks(runA, "lost item from their phone", 50)).noneMatch(c -> c.sourceId().equals(article));
	}

	@Test
	@DisplayName("T-12-08 private upload: PRIVATE + owner; never linked to or found by another user's run")
	void privateUpload() {
		byte[] notes = ("My own notes on campus lost and found. Our college uses a WhatsApp group and a register.\n\n"
				+ "The register is checked every evening by the security office. ".repeat(6)).getBytes();
		IngestResult r = library.ingestUpload(alice, "notes.md", "text/markdown", notes, UploadPurpose.RUN_ATTACHMENT);
		assertThat(r.outcome()).isEqualTo(Outcome.NEW);
		var row = jdbc.sql("SELECT visibility, owner_id, type, upload_id IS NOT NULL FROM source_document WHERE id = :id")
				.param("id", r.sourceId()).query((rs, n) -> List.of(rs.getString(1), rs.getObject(2), rs.getString(3), rs.getBoolean(4)))
				.single();
		assertThat(row).containsExactly("PRIVATE", alice, "UPLOAD", true);

		UUID aliceRun = run(alice);
		UUID bobRun = run(bob);
		assertThat(library.useInRun(alice, aliceRun, r.sourceId(), null, 1)).isTrue();
		assertThat(library.useInRun(bob, bobRun, r.sourceId(), null, 1)).as("bob can't attach alice's file").isFalse();
		// even a forged link row is ignored by relevance
		jdbc.sql("INSERT INTO run_source (run_id, source_id, status) VALUES (:r, :s, 'USED')").param("r", bobRun)
				.param("s", r.sourceId()).update();
		assertThat(library.topChunks(bobRun, "security office register", 50)).isEmpty();
		assertThat(library.topChunks(aliceRun, "security office register", 50)).isNotEmpty();
		assertThat(library.ingestUpload(alice, "x.exe", "application/octet-stream", new byte[] { 1, 2 }, UploadPurpose.LIBRARY)
				.outcome()).isEqualTo(Outcome.UNAVAILABLE);
	}

	// --- helpers ---

	UUID user(String name) {
		return jdbc.sql("INSERT INTO app_user (email, email_verified) VALUES (:e, true) RETURNING id")
				.param("e", name + "-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
	}

	UUID run(UUID owner) {
		return jdbc.sql("INSERT INTO research_run (owner_id, topic, config) VALUES (:o, 'Lost and found apps for campuses', '{}') RETURNING id")
				.param("o", owner).query(UUID.class).single();
	}

	String text(UUID source) {
		return jdbc.sql("SELECT text FROM source_document WHERE id = :id").param("id", source).query(String.class).single();
	}

	int chunkCount(UUID source) {
		return jdbc.sql("SELECT count(*) FROM source_chunk WHERE source_id = :id").param("id", source).query(Integer.class)
				.single();
	}

}
