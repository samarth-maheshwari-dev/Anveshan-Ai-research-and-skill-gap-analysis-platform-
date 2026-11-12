package dev.anveshan.sources.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.anveshan.sources.api.SourceSearch.Candidate;
import dev.anveshan.sources.api.SourceSearch.CandidateType;

class SearchDomainTest {

	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource(delimiter = '|', value = {
			"HTTPS://Example.com/a?utm_source=x#top | https://example.com/a",
			"https://example.com:443/a/b?id=7&utm_medium=mail&fbclid=z | https://example.com/a/b?id=7",
			"http://EXAMPLE.com:80/ | http://example.com",
			"https://example.com:8443/x | https://example.com:8443/x",
			"https://example.com/search?q=a%20b | https://example.com/search?q=a%20b" })
	@DisplayName("T-11-01 canonical URL: lower-case scheme/host, no default port, fragment or tracking parameters")
	void canonical(String in, String out) {
		assertThat(Urls.canonical(in)).isEqualTo(out);
	}

	@ParameterizedTest
	@ValueSource(strings = { "ftp://example.com/a", "javascript:alert(1)", "not a url", "" })
	@DisplayName("T-11-01 anything that isn't an http(s) URL → null")
	void notHttp(String in) {
		assertThat(Urls.canonical(in)).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "10.1145/3292500.3330701", "doi:10.1145/3292500.3330701", "DOI: 10.1145/3292500.3330701",
			"https://doi.org/10.1145/3292500.3330701", "http://dx.doi.org/10.1145/3292500.3330701",
			"https://doi.org/10.1145%2F3292500.3330701", "10.1145/3292500.3330701.", "(10.1145/3292500.3330701)" })
	@DisplayName("T-11-02 every DOI spelling → https://doi.org/<lower-case doi>")
	void doi(String in) {
		assertThat(Doi.normalize(in)).isEqualTo("https://doi.org/10.1145/3292500.3330701");
		assertThat(Doi.normalize("10.1000/ABC-Def")).isEqualTo("https://doi.org/10.1000/abc-def");
		assertThat(Doi.normalize("no doi here")).isNull();
		assertThat(Doi.normalize("50% off")).isNull();
	}

	@Test
	@DisplayName("T-11-03 same DOI from OpenAlex + Crossref → one; titles ≥ 90 % alike → one; different papers stay")
	void dedupe() {
		String doi = "https://doi.org/10.1000/xyz";
		var openalex = c("Lost and found apps for campuses", "https://doi.org/10.1000/xyz", doi, null, "openalex");
		var crossref = c("Lost-and-found apps for campuses", "https://doi.org/10.1000/xyz", doi, "Abstract text", "crossref");
		var title1 = c("Transformers in medical imaging: a survey", "https://a.example/1", null, null, "semanticscholar");
		var title2 = c("Transformers in Medical Imaging: A Survey.", "https://b.example/2", null, "longer snippet", "arxiv");
		var other = c("Graph neural networks for drug discovery", "https://c.example/3", null, null, "openalex");
		assertThat(Dedupe.similarity(title1.title(), title2.title())).isGreaterThanOrEqualTo(0.9);
		assertThat(Dedupe.similarity("Transformers in medical imaging: a survey", "Transformers in medical imaging"))
				.as("a shorter, different title is not the same work").isLessThan(0.9);

		List<Candidate> merged = Dedupe.merge(List.of(openalex, title1, crossref, other, title2));

		assertThat(merged).hasSize(3);
		assertThat(merged.get(0).adapter()).isEqualTo("openalex+crossref");
		assertThat(merged.get(0).snippet()).as("gaps filled from the duplicate").isEqualTo("Abstract text");
		assertThat(merged.get(1).adapter()).isEqualTo("semanticscholar+arxiv");
		assertThat(merged.get(1).url()).as("first appearance wins").isEqualTo("https://a.example/1");
		assertThat(merged.get(2).title()).startsWith("Graph");
	}

	@Test
	@DisplayName("T-11-03 ranking: relevance dominates; boosts only break near-ties")
	void ranking() {
		LocalDate since = LocalDate.of(2024, 1, 1);
		double paper = Ranking.score(0.80, CandidateType.PAPER, LocalDate.of(2025, 1, 1), since, false);
		double page = Ranking.score(0.80, CandidateType.WEB_PAGE, LocalDate.of(2025, 1, 1), since, false);
		double old = Ranking.score(0.80, CandidateType.WEB_PAGE, LocalDate.of(2020, 1, 1), since, false);
		double preferred = Ranking.score(0.80, CandidateType.WEB_PAGE, null, null, true);
		double moreRelevant = Ranking.score(0.95, CandidateType.WEB_PAGE, null, null, false);
		assertThat(paper).isGreaterThan(page);
		assertThat(page).isGreaterThan(old);
		assertThat(preferred).isGreaterThan(Ranking.score(0.80, CandidateType.WEB_PAGE, null, null, false));
		assertThat(moreRelevant).as("a clearly more relevant page beats every boost").isGreaterThan(paper);
	}

	private static Candidate c(String title, String url, String doi, String snippet, String adapter) {
		return new Candidate(title, url, doi, snippet, null, CandidateType.PAPER, adapter, 0);
	}

}
