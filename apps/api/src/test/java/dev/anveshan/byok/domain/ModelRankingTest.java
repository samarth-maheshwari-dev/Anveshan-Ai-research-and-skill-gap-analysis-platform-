package dev.anveshan.byok.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-08-09 model ranking: pure function, every branch (gate G5). */
class ModelRankingTest {

	/** Same shape as the production rules in application.yml. */
	static final ModelRanking.Rules GEMINI = ModelRanking.Rules.of(
			List.of("embed", "tts", "audio", "image", "vision", "aqa", "(^|[-_/.:])live([-_/.:]|$)"),
			List.of("(^|[-_/.:])(preview|exp|experimental|beta|alpha)([-_/.:]|\\d|$)"),
			List.of("^gemini-\\d+(\\.\\d+)?-flash$", "^gemini-\\d+(\\.\\d+)?-flash-lite$", "^gemini-flash-latest$",
					"^gemini-"));

	@Test
	@DisplayName("T-08-09 Samarth's Gemini list: non-chat dropped, newest stable flash first, retired 1.x last in its group")
	void realGeminiList() {
		var ranked = ModelRanking.rank(List.of("embedding-gecko-001", "gemini-1.0-pro-vision-latest", "gemini-1.5-flash",
				"gemini-2.0-flash", "aqa", "gemini-2.5-flash-preview-tts", "gemini-2.5-flash", "gemini-2.5-pro",
				"gemini-2.5-flash-lite", "gemini-flash-latest", "gemini-live-2.5-flash-preview", "gemma-3-27b-it"), GEMINI);

		assertThat(ranked).containsExactly("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash",
				"gemini-2.5-flash-lite", "gemini-flash-latest", "gemini-2.5-pro", "gemma-3-27b-it");
	}

	@Test
	@DisplayName("T-08-09 blank, null and duplicate ids are dropped; ids are trimmed")
	void cleansInput() {
		assertThat(ModelRanking.rank(Arrays.asList(null, "", "   ", " gemini-2.5-flash ", "gemini-2.5-flash"), GEMINI))
				.containsExactly("gemini-2.5-flash");
		assertThat(ModelRanking.rank(List.of(), GEMINI)).isEmpty();
	}

	@Test
	@DisplayName("T-08-09 preferred patterns win over a newer version; stable beats preview in the same group")
	void preferredThenStable() {
		var rules = ModelRanking.Rules.of(List.of(), List.of("preview"), List.of("versatile", "instant"));

		assertThat(ModelRanking.rank(List.of("llama-4-instant", "llama-3.3-70b-versatile", "llama-5-preview-versatile",
				"zeta-9"), rules))
				.containsExactly("llama-3.3-70b-versatile", "llama-5-preview-versatile", "llama-4-instant", "zeta-9");
	}

	@Test
	@DisplayName("T-08-09 newest version first: 3 > 2.5 > 2 > none; equal versions → shorter id → alphabetical")
	void versionsAndTies() {
		var none = ModelRanking.Rules.of(null, null, null);

		assertThat(ModelRanking.rank(List.of("m-2", "m-2.5", "m-3", "m-latest"), none))
				.containsExactly("m-3", "m-2.5", "m-2", "m-latest");
		assertThat(ModelRanking.rank(List.of("m-2-long", "m-2-b", "m-2-a"), none))
				.containsExactly("m-2-a", "m-2-b", "m-2-long");
		assertThat(ModelRanking.rank(List.of("m-10", "m-9"), none)).as("numeric, not text, compare")
				.containsExactly("m-10", "m-9");
	}

	@Test
	@DisplayName("T-08-09 version parsing: sizes like 7b/70b are not versions; v-prefix and leading zeros handled")
	void versionParsing() {
		assertThat(ModelRanking.version("llama-3.3-70b-versatile")).containsExactly("3", "3");
		assertThat(ModelRanking.version("mistral-7b-instruct")).isEmpty();
		assertThat(ModelRanking.version("deepseek-v3")).containsExactly("3");
		assertThat(ModelRanking.version("model-007")).containsExactly("7");
		assertThat(ModelRanking.version("claude-haiku-4-5-20251001")).containsExactly("4");
		assertThat(ModelRanking.version("gpt-4o-mini")).isEmpty();
		assertThat(ModelRanking.newestFirst(List.of("2", "5"), List.of("2"))).isNegative();
		assertThat(ModelRanking.newestFirst(List.of("2"), List.of("2"))).isZero();
	}

	@Test
	@DisplayName("T-08-24 date/build stamps are not versions: gpt-4o-mini-2024-07-18, deepseek-r1-0528 don't outrank real versions")
	void dateStampsAreNotVersions() {
		assertThat(ModelRanking.version("gpt-4o-mini-2024-07-18")).isEmpty();
		assertThat(ModelRanking.version("deepseek-r1-0528")).isEmpty();
		var rules = ModelRanking.Rules.of(List.of(), List.of(), List.of());
		assertThat(ModelRanking.rank(List.of("deepseek-r1-0528", "llama-3.3-instruct", "gpt-4o-mini-2024-07-18"), rules))
				.first().isEqualTo("llama-3.3-instruct");
	}

	@Test
	@DisplayName("T-08-09 isExcluded: speech/embedding/live models are not chat models; normal ids are")
	void excluded() {
		assertThat(ModelRanking.isExcluded("text-embedding-004", GEMINI)).isTrue();
		assertThat(ModelRanking.isExcluded("gemini-2.0-flash-live-001", GEMINI)).isTrue();
		assertThat(ModelRanking.isExcluded("gemini-2.5-flash", GEMINI)).isFalse();
		assertThat(ModelRanking.isExcluded("deliver-1", GEMINI)).as("'live' only as its own word").isFalse();
	}

}
