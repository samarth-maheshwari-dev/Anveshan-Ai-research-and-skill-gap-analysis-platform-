package dev.anveshan.sources.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.anveshan.sources.api.SourceSearch.Candidate;
import dev.anveshan.sources.api.SourceSearch.CandidateType;

class RetractionTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"RETRACTED: Ileal-lymphoid-nodular hyperplasia, non-specific colitis, and pervasive developmental disorder",
			"Retracted: Deep learning for crop yield", "[Retracted] A study of things", "(WITHDRAWN) Old preprint",
			"Retracted article: Graph networks", "WITHDRAWN - duplicate submission" })
	@DisplayName("T-15-35 a title marked RETRACTED / WITHDRAWN says the paper was taken back")
	void retractedTitles(String title) {
		assertThat(Retraction.titleSaysRetracted(title)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "Gingival retraction techniques in prosthodontics", "Withdrawn patients and depression",
			"Retraction Watch and research integrity", "Why retracted papers keep being cited",
			"Withdrawn-hand reflex in infants", "Withdrawal: opioid tapering in primary care", "" })
	@DisplayName("T-15-35 ordinary titles that only mention the word are not retracted")
	void ordinaryTitles(String title) {
		assertThat(Retraction.titleSaysRetracted(title)).isFalse();
		assertThat(Retraction.isNotice(title)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "Retraction notice: Downregulation of long noncoding RNA LINC01419",
			"Retraction: Neural rendering at scale", "Notice of Retraction: Smart campus IoT",
			"Retraction note: Machine learning for X", "Retraction of: An old result",
			"Retraction Note to: Deep nets for crops", "Retraction notice to \"Graph learning\" [J. Sci. 12 (2020) 1]",
			"Withdrawal notice: Duplicate publication" })
	@DisplayName("T-15-35 a retraction notice is a notice, not evidence")
	void notices(String title) {
		assertThat(Retraction.isNotice(title)).isTrue();
	}

	@Test
	@DisplayName("T-15-35 Crossref update types: retraction, withdrawal and removal take a paper back; corrections don't")
	void updateTypes() {
		assertThat(List.of("retraction", "Retraction", "withdrawal", "removal")).allMatch(Retraction::takesBack);
		assertThat(List.of("correction", "erratum", "expression_of_concern", "")).noneMatch(Retraction::takesBack);
		assertThat(Retraction.takesBack(null)).isFalse();
	}

	@Test
	@DisplayName("T-15-35 merging duplicates: retracted if any API said so")
	void mergeKeepsFlag() {
		Candidate flagged = new Candidate("Lost and found at scale", "https://doi.org/10.1/x", "https://doi.org/10.1/x",
				null, null, CandidateType.PAPER, "openalex", 0, null, true);
		Candidate plain = new Candidate("Lost and found at scale", "https://doi.org/10.1/x", "https://doi.org/10.1/x",
				"longer snippet", null, CandidateType.PAPER, "semanticscholar", 0);
		assertThat(Dedupe.merge(List.of(plain, flagged))).singleElement().satisfies(c -> {
			assertThat(c.retracted()).isTrue();
			assertThat(c.adapter()).isEqualTo("semanticscholar+openalex");
		});
		assertThat(Dedupe.merge(List.of(plain, plain)).get(0).retracted()).isFalse();
	}

}
