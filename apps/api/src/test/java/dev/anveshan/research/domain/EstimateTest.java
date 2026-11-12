package dev.anveshan.research.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.anveshan.research.domain.RunOptions.Depth;

class EstimateTest {

	/**
	 * TECHSPEC §12.3 worked by hand (sections = 5, no cache hits):
	 * extract = ceil(subQ × sourcesPerQ / 2.5), verify = ceil(subQ × sourcesPerQ × 2 / 9), write = sections + 1
	 * (the conflicts' one-line reasons, §12.9), plan = 1.
	 */
	@ParameterizedTest(name = "{0} × {1} sub-questions → extract {2}, verify {3}, total {4}, ~{5} min")
	@CsvSource({
			// depth, subQ, extract, verify, total, minutes
			"QUICK,    1,  2,  1,  10,  2", // 1×4=4 → ceil(1.6)=2, ceil(8/9)=1; minutes 5×1/3 → 2 (floor 2)
			"QUICK,    5,  8,  5, 20,  8", // 20 → ceil(8)=8, ceil(40/9)=5; 5×5/3=8.3 → 8
			"QUICK,    8, 13,  8, 28, 13", // 32 → ceil(12.8)=13, ceil(64/9)=8; 13.3 → 13
			"STANDARD, 1,  3,  2, 12,  3", // 6 → ceil(2.4)=3, ceil(12/9)=2; 15×1/5=3
			"STANDARD, 5, 12,  7, 26, 15", // 30 → 12, ceil(60/9)=7; 15
			"STANDARD, 8, 20, 11, 38, 24", // 48 → ceil(19.2)=20, ceil(96/9)=11; 24
			"DEEP,     1,  4,  3, 14,  5", // 10 → 4, ceil(20/9)=3; 30×1/6=5
			"DEEP,     5, 20, 12, 39, 25", // 50 → 20, ceil(100/9)=12; 25
			"DEEP,     8, 32, 18, 57, 40" // 80 → 32, ceil(160/9)=18; 40
	})
	@DisplayName("T-10-01 estimate: Quick/Standard/Deep × 1/5/8 sub-questions matches TECHSPEC §12.3")
	void formula(Depth depth, int subQ, int extract, int verify, int total, int minutes) {
		Estimate e = Estimate.of(depth, subQ, 5, 0.0);
		assertThat(e.requests()).containsExactly(Map.entry("PLAN", 1), Map.entry("EXTRACT", extract),
				Map.entry("VERIFY", verify), Map.entry("WRITE", 6));
		assertThat(e.totalRequests()).isEqualTo(total);
		assertThat(e.minutes()).isEqualTo(minutes);
	}

	@Test
	@DisplayName("T-10-01 cached claims lower the extract calls; write calls follow the template's sections")
	void cacheAndSections() {
		assertThat(Estimate.of(Depth.STANDARD, 5, 5, 0.5).requests().get("EXTRACT")).isEqualTo(6); // ceil(12 × 0.5)
		assertThat(Estimate.of(Depth.STANDARD, 5, 3, 0.0).requests().get("WRITE")).isEqualTo(4);
		assertThat(Estimate.of(Depth.STANDARD, 5, 5, 1.0).requests().get("EXTRACT")).isZero();
	}

	@Test
	@DisplayName("T-10-01 the estimate refuses impossible input instead of guessing")
	void rejectsBadInput() {
		assertThatThrownBy(() -> Estimate.of(Depth.QUICK, 0, 5, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Estimate.of(Depth.QUICK, 9, 5, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Estimate.of(Depth.QUICK, 3, 0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Estimate.of(Depth.QUICK, 3, 5, 1.5)).isInstanceOf(IllegalArgumentException.class);
	}

}
