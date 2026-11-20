package dev.anveshan.report.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.report.api.Reports.OpenQuestionDraft;

class OpenQuestionsTest {

	@Test
	@DisplayName("T-15-23 conflicts section lists NO_SOURCE claims as open questions, each cited, text escaped; none → unchanged")
	void openQuestions() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		String md = DefaultReports.conflicts(List.of(),
				List.of(new OpenQuestionDraft(a, "Most items are *phones*."), new OpenQuestionDraft(b, "Offices keep items [30 days].")),
				Map.of(), false);

		assertThat(md).startsWith("No conflicting evidence was found between the sources.");
		assertThat(md).contains("**Open questions**", "Treat them as unverified:");
		assertThat(md).contains("- Most items are phones . [c:" + a + "]");
		assertThat(md).contains("- Offices keep items 30 days . [c:" + b + "]");

		assertThat(DefaultReports.conflicts(List.of(), List.of(), Map.of(), false))
				.isEqualTo("No conflicting evidence was found between the sources.");
		assertThat(DefaultReports.conflicts(List.of(), List.of(new OpenQuestionDraft(a, "x")), Map.of(), true))
				.contains("**खुले प्रश्न**", "[c:" + a + "]");
	}

}
