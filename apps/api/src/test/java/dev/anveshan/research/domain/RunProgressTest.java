package dev.anveshan.research.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunProgressTest {

	@Test
	@DisplayName("T-09-22 progress: 0 while planning, each research stage weighs 1/8, the open stage by its share, "
			+ "never 100 before COMPLETED, never above 99")
	void weightedByStage() {
		assertThat(RunProgress.of(0, 1, 1)).isEqualByComparingTo("0"); // PLAN done ≠ research progress
		assertThat(RunProgress.of(1, 0, 2)).isEqualByComparingTo("0");
		assertThat(RunProgress.of(1, 1, 2)).isEqualByComparingTo("6.25"); // half of stage 1 of 8
		assertThat(RunProgress.of(1, 2, 2)).isEqualByComparingTo("12.5"); // stage 1 closed — not 100 %
		assertThat(RunProgress.of(4, 1, 3)).isEqualByComparingTo("41.66"); // 3 stages + 1/3
		assertThat(RunProgress.of(8, 2, 2)).isEqualByComparingTo("99"); // everything done but not COMPLETED yet
		assertThat(RunProgress.of(12, 1, 1)).isEqualByComparingTo("99"); // more stages than planned: still capped
		assertThat(RunProgress.of(3, 0, 0)).isEqualByComparingTo("0"); // empty stage
		assertThat(RunProgress.of(2, 5, 2)).isEqualByComparingTo("25"); // bad counts are clamped
		assertThat(RunProgress.of(2, -1, 2)).isEqualByComparingTo("12.5");
	}

}
