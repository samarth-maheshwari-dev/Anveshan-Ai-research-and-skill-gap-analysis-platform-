package dev.anveshan.sources.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.anveshan.TestcontainersConfig;
import dev.anveshan.sources.FakeSearchApis;
import dev.anveshan.sources.api.SourceSearch;
import dev.anveshan.sources.api.SourceSearch.AdapterStatus;
import dev.anveshan.sources.api.SourceSearch.Candidate;
import dev.anveshan.sources.api.SourceSearch.CandidateType;
import dev.anveshan.sources.api.SourceSearch.SearchOutcome;
import dev.anveshan.sources.api.SourceSearch.SearchRequest;
import dev.anveshan.sources.api.SourceSearch.SourceKind;
import dev.anveshan.sources.app.SearchAdapter.AdapterQuery;

/** Every adapter against responses in its API's real shape (T-11-04), and the whole search pipeline. */
@SpringBootTest(properties = "management.server.port=0")
@Import(TestcontainersConfig.class)
class SearchAdaptersIT {

	static final FakeSearchApis FAKE = new FakeSearchApis();

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		FAKE.register(r);
	}

	@Autowired
	OpenAlexAdapter openAlex;

	@Autowired
	SemanticScholarAdapter semanticScholar;

	@Autowired
	ArxivAdapter arxiv;

	@Autowired
	CrossrefAdapter crossref;

	@Autowired
	WikipediaAdapter wikipedia;

	@Autowired
	GitHubAdapter github;

	@Autowired
	SearxngAdapter searxng;

	@Autowired
	SourceSearch search;

	@Autowired
	SearchResilience resilience;

	final UUID owner = UUID.randomUUID();

	@BeforeEach
	void reset() {
		FAKE.reset();
		resilience.reset();
	}

	AdapterQuery q(LocalDate since) {
		return new AdapterQuery(owner, "lost and found apps", since, 10, List.of(), List.of());
	}

	@Test
	@DisplayName("T-11-04 OpenAlex: title, DOI as URL, abstract rebuilt from the inverted index, date, type; filter + params sent")
	void openAlex() {
		List<Candidate> c = openAlex.search(q(LocalDate.of(2020, 1, 1)));
		assertThat(c).hasSize(2);
		assertThat(c.get(0).title()).isEqualTo("Lost and found management systems for university campuses");
		assertThat(c.get(0).url()).isEqualTo("https://doi.org/10.1000/LOSTFOUND.2023");
		assertThat(c.get(0).snippet()).isEqualTo("We compare campus lost and found apps.");
		assertThat(c.get(0).published()).isEqualTo(LocalDate.of(2023, 4, 2));
		assertThat(c.get(1).url()).as("no DOI → landing page").isEqualTo("https://repo.example/w2");
		assertThat(c.get(1).type()).isEqualTo(CandidateType.PREPRINT);
		String query = FAKE.requestsTo("openalex").get(0).uri().getQuery();
		assertThat(query).contains("search=lost and found apps", "filter=from_publication_date:2020-01-01", "per_page=10");
		assertThat(FAKE.requestsTo("openalex").get(0).headers().get("User-agent")).anyMatch(v -> v.startsWith("Anveshan/"));
	}

	@Test
	@DisplayName("T-11-04 Semantic Scholar: TL;DR as snippet, DOI or arXiv link, year filter")
	void semanticScholar() {
		List<Candidate> c = semanticScholar.search(q(LocalDate.of(2021, 6, 1)));
		assertThat(c).extracting(Candidate::url).containsExactly("https://doi.org/10.1000/lostfound.2023",
				"https://arxiv.org/abs/2201.00001");
		assertThat(c.get(0).snippet()).isEqualTo("Campus lost-and-found apps compared.");
		assertThat(c.get(1).type()).isEqualTo(CandidateType.PREPRINT);
		assertThat(c.get(1).published()).isEqualTo(LocalDate.of(2022, 1, 1));
		assertThat(FAKE.requestsTo("semanticscholar").get(0).uri().getQuery()).contains("year=2021-");
	}

	@Test
	@DisplayName("T-11-04 arXiv: Atom entries → https abs links, titles on one line, optional DOI")
	void arxiv() {
		List<Candidate> c = arxiv.search(q(null));
		assertThat(c).hasSize(2);
		assertThat(c.get(0).title()).isEqualTo("Item recovery with QR codes");
		assertThat(c.get(0).url()).isEqualTo("https://arxiv.org/abs/2201.00001v1");
		assertThat(c.get(1).doi()).isEqualTo("https://doi.org/10.2000/crowd.2023");
		assertThat(FAKE.requestsTo("arxiv").get(0).uri().getQuery()).contains("search_query=all:lost AND all:and AND all:found");
	}

	@Test
	@DisplayName("T-11-04 Crossref: JATS abstract cleaned, partial dates, posted-content = preprint")
	void crossref() {
		List<Candidate> c = crossref.search(q(LocalDate.of(2020, 1, 1)));
		assertThat(c.get(0).snippet()).isEqualTo("We compare campus apps.");
		assertThat(c.get(0).published()).isEqualTo(LocalDate.of(2023, 4, 1));
		assertThat(c.get(1).type()).isEqualTo(CandidateType.PREPRINT);
		assertThat(FAKE.requestsTo("crossref").get(0).uri().getQuery()).contains("filter=from-pub-date:2020-01-01");
	}

	@Test
	@DisplayName("T-11-04 Wikipedia, GitHub, SearXNG: links, cleaned snippets, dates")
	void webAndCode() {
		List<Candidate> w = wikipedia.search(q(null));
		assertThat(w.get(0).url()).endsWith("/wiki/Lost_and_found");
		assertThat(w.get(0).snippet()).isEqualTo("A lost and found is a place");
		assertThat(w.get(0).type()).isEqualTo(CandidateType.ENCYCLOPEDIA);

		List<Candidate> g = github.search(q(LocalDate.of(2024, 1, 1)));
		assertThat(g.get(0).title()).isEqualTo("college/lost-and-found");
		assertThat(g.get(0).snippet()).contains("42 stars");
		assertThat(FAKE.requestsTo("github").get(0).uri().getQuery()).contains("pushed:>=2024-01-01");

		List<Candidate> s = searxng.search(q(null));
		assertThat(s).hasSize(2);
		assertThat(s.get(0).published()).isEqualTo(LocalDate.of(2025, 6, 1));
	}

	@Test
	@DisplayName("T-11-03 whole search: same DOI from OpenAlex + S2 + Crossref → one; utm stripped; avoided site dropped; ranked")
	void pipeline() {
		SearchOutcome out = search.search(new SearchRequest(owner, "Which lost and found apps work best on campuses?",
				List.of("campus lost and found app", "lost item recovery university"),
				Set.of(SourceKind.ACADEMIC, SourceKind.WEB), null, List.of("blog.example"), List.of("quora.com"), 20));

		assertThat(out.adapters()).allMatch(a -> a.status() == AdapterStatus.OK);
		assertThat(out.adapters()).extracting(a -> a.adapter()).contains("openalex", "semanticscholar", "arxiv",
				"crossref", "wikipedia", "searxng").doesNotContain("github", "tavily");
		List<Candidate> c = out.candidates();
		assertThat(c.stream().filter(x -> "https://doi.org/10.1000/lostfound.2023".equals(x.doi()))).hasSize(1);
		Candidate merged = c.stream().filter(x -> "https://doi.org/10.1000/lostfound.2023".equals(x.doi())).findFirst()
				.orElseThrow();
		assertThat(merged.adapter()).contains("openalex", "semanticscholar", "crossref");
		assertThat(c).extracting(Candidate::url).contains("https://blog.example/lost-found-apps")
				.noneMatch(u -> u.contains("utm_")).noneMatch(u -> u.contains("quora.com"));
		assertThat(c).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
		assertThat(FAKE.requestsTo("openalex")).as("2 queries per API").hasSize(2);
	}

	@Test
	@DisplayName("T-11-03 time range: undated candidates stay, ones published before the start are dropped")
	void timeRange() {
		SearchOutcome out = search.search(new SearchRequest(owner, "lost and found apps", List.of("lost and found"),
				Set.of(SourceKind.ACADEMIC), LocalDate.of(2023, 1, 1), List.of(), List.of(), 20));
		assertThat(out.candidates()).allMatch(c -> c.published() == null || !c.published().isBefore(LocalDate.of(2023, 1, 1)));
		assertThat(out.candidates()).extracting(Candidate::title).doesNotContain("Mobile apps for student services");
	}

}
