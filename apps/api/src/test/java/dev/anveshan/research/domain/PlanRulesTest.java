package dev.anveshan.research.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.research.domain.PlanRules.PlannedQuestion;
import dev.anveshan.research.domain.RunOptions.SourceType;

class PlanRulesTest {

	static PlannedQuestion q(String text) {
		return new PlannedQuestion(text, List.of(), List.of());
	}

	@Test
	@DisplayName("T-10-05 a plan has 1–8 sub-questions: 0 and 9 are refused (D-FR-3)")
	void count() {
		assertThatThrownBy(() -> PlanRules.normalize(List.of(), EnumSet.of(SourceType.WEB)))
				.hasMessageContaining("1–8");
		List<PlannedQuestion> nine = java.util.stream.IntStream.range(0, 9).mapToObj(i -> q("Question number " + i))
				.toList();
		assertThatThrownBy(() -> PlanRules.normalize(nine, EnumSet.of(SourceType.WEB))).hasMessageContaining("1–8");
		assertThat(PlanRules.normalize(nine.subList(0, 8), EnumSet.of(SourceType.WEB))).hasSize(8);
		assertThat(PlanRules.normalize(nine.subList(0, 1), EnumSet.of(SourceType.WEB))).hasSize(1);
	}

	@Test
	@DisplayName("T-10-05 text is trimmed and checked; duplicates, blanks and too-long questions are refused")
	void text() {
		var out = PlanRules.normalize(List.of(q("  Which apps   are used\non campuses?  ")), EnumSet.of(SourceType.WEB));
		assertThat(out.get(0).text()).isEqualTo("Which apps are used on campuses?");
		assertThatThrownBy(() -> PlanRules.normalize(List.of(q("hey")), EnumSet.of(SourceType.WEB)))
				.hasMessageContaining("5–300");
		assertThatThrownBy(() -> PlanRules.normalize(List.of(q("x".repeat(301))), EnumSet.of(SourceType.WEB)))
				.hasMessageContaining("5–300");
		assertThatThrownBy(() -> PlanRules.normalize(List.of(q("Same question"), q("same QUESTION")),
				EnumSet.of(SourceType.WEB))).hasMessageContaining("the same");
	}

	@Test
	@DisplayName("T-10-05 missing source types come from the form; missing queries fall back to the question")
	void defaults() {
		var out = PlanRules.normalize(List.of(q("What is campus lost and found?")),
				EnumSet.of(SourceType.GITHUB, SourceType.WEB));
		assertThat(out.get(0).sourceTypes()).containsExactly(SourceType.WEB, SourceType.GITHUB);
		assertThat(out.get(0).queries()).containsExactly("What is campus lost and found?");

		var explicit = PlanRules.normalize(List.of(new PlannedQuestion("What is campus lost and found?",
				List.of(SourceType.ACADEMIC), List.of(" a ", "a", "", "b", "c", "d", "e", "f"))), EnumSet.of(SourceType.WEB));
		assertThat(explicit.get(0).sourceTypes()).containsExactly(SourceType.ACADEMIC);
		assertThat(explicit.get(0).queries()).containsExactly("a", "b", "c", "d", "e"); // deduped, max 5
		assertThat(PlanRules.normalize(List.of(q("Nothing chosen at all")), Collections.emptySet()).get(0).sourceTypes())
				.containsExactly(SourceType.WEB);
	}

}
