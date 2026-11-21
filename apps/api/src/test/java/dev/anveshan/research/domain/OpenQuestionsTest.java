package dev.anveshan.research.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.research.domain.OpenQuestions.Candidate;

class OpenQuestionsTest {

	@Test
	@DisplayName("T-16-16 open questions: below the similarity threshold left out, most relevant first, capped")
	void picksRelevant() {
		List<Candidate> c = List.of(new Candidate("taxila", 0.71), new Candidate("a", 0.84), new Candidate("b", 0.91),
				new Candidate("c", 0.80), new Candidate("d", 0.88));
		assertThat(OpenQuestions.pick(c, 0.80, 3)).containsExactly("b", "d", "a");
		assertThat(OpenQuestions.pick(c, 0.80, 8)).containsExactly("b", "d", "a", "c");
		assertThat(OpenQuestions.pick(c, 0.95, 8)).isEmpty();
	}

}
