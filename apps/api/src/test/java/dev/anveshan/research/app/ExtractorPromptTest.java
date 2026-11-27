package dev.anveshan.research.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.llm.api.Prompt;
import dev.anveshan.research.infra.PromptFiles;

class ExtractorPromptTest {

	@Test
	@DisplayName("T-17-03 extractor v2: in use, small batches; keeps every v1 safety rule and asks for each fact in a passage")
	void extractorV2() {
		assertThat(ExtractHandler.PROMPT_VERSION).isEqualTo(2);
		assertThat(ExtractHandler.BATCH).isBetween(3, 6);
		PromptFiles files = new PromptFiles();
		Map<String, String> v = Map.of("questions", "q", "passages", "p");
		Prompt v1 = files.load(ExtractHandler.PROMPT, 1, v, 0.1, 3000);
		Prompt v2 = files.load(ExtractHandler.PROMPT, 2, v, 0.1, 3000);
		String safety = v1.system().substring(0, v1.system().indexOf("- At most 4 claims"));
		assertThat(v2.system()).startsWith(safety).contains("Be thorough", "At most 4 claims per passage",
				"Reply with JSON only");
		assertThat(v2.user()).isEqualTo(v1.user());
	}

}
