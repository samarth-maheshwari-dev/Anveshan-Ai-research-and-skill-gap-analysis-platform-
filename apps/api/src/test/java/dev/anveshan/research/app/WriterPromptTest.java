package dev.anveshan.research.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.llm.api.Prompt;
import dev.anveshan.research.infra.PromptFiles;

class WriterPromptTest {

	@Test
	@DisplayName("T-16-17 writer v3: in use; structure rules per section kind; citation and evidence rules kept from v2")
	void writerV3() {
		assertThat(WriteHandler.PROMPT_VERSION).isEqualTo(3);
		Map<String, String> v = new HashMap<>();
		for (String k : new String[] { "language", "topic", "objective", "format", "templateGuidance", "userInstructions",
				"sectionTitle", "sectionPurpose", "otherSections", "claims" }) {
			v.put(k, "x");
		}
		PromptFiles files = new PromptFiles();
		Prompt v3 = files.load(WriteHandler.PROMPT, 3, v, 0.4, 4000);
		Prompt v2 = files.load(WriteHandler.PROMPT, 2, v, 0.4, 4000);
		assertThat(v3.system()).contains("topic sentence", "takeaway", "Synthesize across sources",
				"Never \"However, a second study…\"", "how the report is organized", "study designs", "limitations",
				"answer the question directly", "confidence").doesNotContain("{{");
		String evidence = v2.system().substring(0, v2.system().indexOf("Writing rules"));
		assertThat(v3.system()).startsWith(evidence);
		assertThat(v3.user()).isEqualTo(v2.user());
	}

}
