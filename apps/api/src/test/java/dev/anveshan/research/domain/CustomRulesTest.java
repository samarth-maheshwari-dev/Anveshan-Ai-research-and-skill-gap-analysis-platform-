package dev.anveshan.research.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.research.domain.RunOptions.Depth;

class CustomRulesTest {

	@Test
	@DisplayName("T-10-09 websites become host names: scheme, www, path, port dropped; duplicates merged")
	void sitesNormalize() {
		assertThat(CustomRules.sites(List.of("https://www.IEEE.org/xplore?q=1", "ieee.org", " arxiv.org ", "docs.spring.io:443"),
				"Prefer websites")).containsExactly("ieee.org", "arxiv.org", "docs.spring.io");
		assertThat(CustomRules.sites(List.of(" ", ""), "x")).isNull();
		assertThat(CustomRules.sites(null, "x")).isNull();
	}

	@Test
	@DisplayName("T-10-09 not a website (IP, localhost, words, too many) → a message for the user")
	void sitesRejected() {
		for (String bad : List.of("127.0.0.1", "localhost", "hello world", "http://169.254.169.254/", "a.b")) {
			assertThatThrownBy(() -> CustomRules.sites(List.of(bad), "Prefer websites"))
					.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("isn't a website name");
		}
		List<String> eleven = java.util.stream.IntStream.range(0, 11).mapToObj(i -> "site" + i + ".com").toList();
		assertThatThrownBy(() -> CustomRules.sites(eleven, "Avoid websites")).hasMessageContaining("at most 10");
		assertThatThrownBy(() -> CustomRules.noOverlap(List.of("quora.com"), List.of("quora.com")))
				.hasMessageContaining("both");
	}

	@Test
	@DisplayName("T-10-09 own sections: 1–12 distinct titles of 2–80 characters, trimmed")
	void sections() {
		assertThat(CustomRules.sections(List.of("  Introduction ", "Cost   in India", "My pick"))).containsExactly(
				"Introduction", "Cost in India", "My pick");
		assertThat(CustomRules.sections(List.of())).isNull();
		assertThatThrownBy(() -> CustomRules.sections(List.of("Intro", "intro"))).hasMessageContaining("Two sections");
		assertThatThrownBy(() -> CustomRules.sections(List.of("A"))).hasMessageContaining("2–80");
		assertThatThrownBy(() -> CustomRules.sections(java.util.Collections.nCopies(13, "x").stream()
				.map(x -> x + Math.random()).toList())).hasMessageContaining("at most 12");
	}

	@Test
	@DisplayName("T-10-09 free text: blank → not set, too long → message; since-year 1990..this year")
	void textAndYear() {
		assertThat(CustomRules.text("  Hackathon   pitch ", "x", 120)).isEqualTo("Hackathon pitch");
		assertThat(CustomRules.text("   ", "x", 120)).isNull();
		assertThatThrownBy(() -> CustomRules.text("a".repeat(121), "The objective", 120))
				.hasMessage("The objective must be at most 120 characters.");
		assertThat(CustomRules.sinceYear(2020, 2026)).isEqualTo(2020);
		assertThatThrownBy(() -> CustomRules.sinceYear(1980, 2026)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CustomRules.sinceYear(2027, 2026)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CustomRules.sinceYear(null, 2026)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("T-10-10 custom depth: own sub-question and source counts drive the estimate; presets unchanged")
	void customSize() {
		ResearchSize size = ResearchSize.of(Depth.CUSTOM, 2, 10);
		assertThat(size.subQuestions()).isEqualTo(2);
		assertThat(size.chunksPerSource()).isEqualTo(3);
		Estimate e = Estimate.of(size, 2, 3, 0.0); // 20 sources
		assertThat(e.requests()).containsExactly(Map.entry("PLAN", 1), Map.entry("EXTRACT", 8), Map.entry("VERIFY", 5),
				Map.entry("WRITE", 3));
		assertThat(e.minutes()).isEqualTo(10);
		assertThat(Estimate.of(ResearchSize.of(Depth.STANDARD, 2, 10), 5, 5, 0.0))
				.isEqualTo(Estimate.of(Depth.STANDARD, 5, 5, 0.0)); // custom numbers ignored for a preset
		assertThatThrownBy(() -> ResearchSize.of(Depth.CUSTOM, 9, 6)).hasMessageContaining("1–8");
		assertThatThrownBy(() -> ResearchSize.of(Depth.CUSTOM, 3, 1)).hasMessageContaining("2–10");
	}

}
