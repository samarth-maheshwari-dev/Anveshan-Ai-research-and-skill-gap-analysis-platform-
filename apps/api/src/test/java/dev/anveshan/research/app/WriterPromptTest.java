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
	@DisplayName("T-16-17 writer v3: structure rules per section kind; citation and evidence rules kept from v2")
	void writerV3() {
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


	@Test
	@DisplayName("T-17-02 writer v4: in use; keeps every v3 rule, adds concrete-detail, sub-heading, key-point callout and plain-language rules")
	void writerV4() {
		assertThat(WriteHandler.PROMPT_VERSION).isEqualTo(4);
		Map<String, String> v = new HashMap<>();
		for (String k : new String[] { "language", "topic", "objective", "format", "templateGuidance", "userInstructions",
				"sectionTitle", "sectionPurpose", "otherSections", "claims" }) {
			v.put(k, "x");
		}
		PromptFiles files = new PromptFiles();
		Prompt v4 = files.load(WriteHandler.PROMPT, 4, v, 0.4, 4000);
		Prompt v3 = files.load(WriteHandler.PROMPT, 3, v, 0.4, 4000);
		String kept = v3.system().substring(0, v3.system().indexOf("- Markdown only"));
		assertThat(v4.system()).startsWith(kept);
		assertThat(v4.system()).contains("> **Key point:**", "### ", "Banned filler", "smart 15-year-old", "no tables")
				.doesNotContain("{{");
		assertThat(v4.user()).isEqualTo(v3.user());
	}

}
