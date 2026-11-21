package dev.anveshan.sources.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.sources.api.SourceSearch.Candidate;
import dev.anveshan.sources.api.SourceSearch.CandidateType;

class VenueSiteTest {

	@Test
	@DisplayName("T-16-15 site: the venue (journal) when known, the host for other URLs, never doi.org")
	void site() {
		assertThat(Urls.site("  Journal of  Education ", "https://doi.org/10.1/x")).isEqualTo("Journal of Education");
		assertThat(Urls.site(null, "https://doi.org/10.1/x")).isNull();
		assertThat(Urls.site("", "https://dx.doi.org/10.1/x")).isNull();
		assertThat(Urls.site(null, "https://www.example.org/a")).isEqualTo("example.org");
	}

	@Test
	@DisplayName("T-16-15 dedupe keeps the venue one API reported")
	void dedupeKeepsVenue() {
		Candidate a = new Candidate("Skills gap", "https://doi.org/10.1/x", "https://doi.org/10.1/x", null, null,
				CandidateType.PAPER, "openalex", 0, null, false, null);
		Candidate b = new Candidate("Skills gap", "https://doi.org/10.1/x", "https://doi.org/10.1/x", null, null,
				CandidateType.PAPER, "crossref", 0, null, false, "Journal of Education");
		assertThat(Dedupe.merge(List.of(a, b))).singleElement()
				.satisfies(c -> assertThat(c.venue()).isEqualTo("Journal of Education"));
	}

}
