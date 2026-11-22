package dev.anveshan.research.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExtractBudgetTest {

	@Test
	@DisplayName("T-16-18 extract budget grows with the batch so the JSON is not cut off")
	void budgetScalesWithPassages() {
		assertThat(ExtractHandler.maxTokens(1)).isEqualTo(3000);
		assertThat(ExtractHandler.maxTokens(12)).isGreaterThanOrEqualTo(12 * 4 * 150);
		assertThat(ExtractHandler.maxTokens(100)).isEqualTo(16_000);
	}

}
