package dev.anveshan.byok.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the test result says when the routed model was skipped and another one answered. */
class SkippedNoteTest {

	@Test
	@DisplayName("T-08-30 rate-limited routed model → a heads-up, no 'change Task routing'; a gone model → change it")
	void noteDependsOnWhyTheRoutedModelWasSkipped() {
		assertThat(ConnectionService.skippedNote("gemini-2.5-flash", "gemini-2.0-flash", true))
				.isEqualTo("gemini-2.5-flash is rate-limited right now; gemini-2.0-flash answered")
				.doesNotContain("Task routing");
		assertThat(ConnectionService.skippedNote("gemini-2.5-flash", "gemini-2.0-flash", false))
				.contains("gemini-2.5-flash didn't answer")
				.contains("Research still uses gemini-2.5-flash")
				.contains("Task routing");
	}
}
