package dev.anveshan.sources.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.anveshan.sources.domain.Chunker.Chunk;
import dev.anveshan.sources.domain.Credibility.Rating;
import dev.anveshan.sources.domain.Credibility.Rules;
import dev.anveshan.sources.domain.Credibility.Signals;
import dev.anveshan.sources.domain.Credibility.Tier;

class ChunkerCredibilityTest {

	/** ~5,000 characters: short and long paragraphs, one paragraph far over the limit, one huge "word". */
	static String document() {
		StringBuilder d = new StringBuilder();
		for (int p = 1; p <= 8; p++) {
			for (int s = 1; s <= 4 + p; s++) {
				d.append("Paragraph ").append(p).append(" sentence ").append(s)
						.append(" talks about campus lost and found apps in some detail. ");
			}
			d.append("\n\n");
		}
		d.append("x".repeat(1500)).append("\n\n");
		d.append("Short closing line.");
		return d.toString();
	}

	@Test
	@DisplayName("T-12-03 chunker on ~5,000 chars: every chunk ≤ 1,200 chars and == doc.substring(start, end)")
	void chunker() {
		String doc = document();
		assertThat(doc.length()).isGreaterThan(5000);
		List<Chunk> chunks = Chunker.chunk(doc);
		assertThat(chunks).hasSizeGreaterThan(4);
		for (Chunk c : chunks) {
			String text = c.text(doc);
			assertThat(text).isEqualTo(doc.substring(c.start(), c.end()));
			assertThat(text.length()).as("chunk %s", c.position()).isLessThanOrEqualTo(Chunker.MAX_CHARS);
			assertThat(text).isEqualTo(text.strip());
			assertThat(c.page()).isNull();
		}
		assertThat(chunks).extracting(Chunk::position).containsExactly(
				java.util.stream.IntStream.range(0, chunks.size()).boxed().toArray(Integer[]::new));
		// the whole text is covered, in order (overlap = only the previous chunk's last sentence)
		assertThat(chunks.get(0).start()).isZero();
		assertThat(chunks.get(chunks.size() - 1).end()).isEqualTo(doc.length());
		for (int i = 1; i < chunks.size(); i++) {
			assertThat(chunks.get(i).start()).isGreaterThan(chunks.get(i - 1).start());
			int gapFrom = Math.min(chunks.get(i - 1).end(), chunks.get(i).start());
			assertThat(doc.substring(gapFrom, Math.max(gapFrom, chunks.get(i).start()))).as("nothing skipped").isBlank();
		}
		assertThat(chunks).anyMatch(c -> c.start() < chunks.get(Math.max(0, c.position() - 1)).end() && c.position() > 0)
				.as("at least one chunk overlaps its predecessor by a sentence");
	}

	@Test
	@DisplayName("T-12-03 PDF pages: a chunk carries the page it starts on")
	void pages() {
		String page1 = "First page text. ".repeat(40).strip();
		String page2 = "Second page text. ".repeat(40).strip();
		String doc = page1 + "\n\n" + page2;
		List<Chunk> chunks = Chunker.chunk(doc, Chunker.MAX_CHARS, new int[] { 0, page1.length() + 2 });
		assertThat(chunks.get(0).page()).isEqualTo(1);
		assertThat(chunks.get(chunks.size() - 1).page()).isEqualTo(2);
		assertThat(Chunker.chunk("   ")).isEmpty();
	}

	@Test
	@DisplayName("T-12-03 content hash ignores layout, not words")
	void hash() {
		assertThat(Texts.contentHash("a  b\n\nc")).isEqualTo(Texts.contentHash("a b c"));
		assertThat(Texts.contentHash("a b c")).isNotEqualTo(Texts.contentHash("a b d"));
		assertThat(Texts.clean("  Line one   here \n\n\n\n Line two ")).isEqualTo("Line one here\n\nLine two");
	}

	static final Rules RULES = new Rules(List.of("docs.spring.io", "developer.mozilla.org"), List.of(".gov", ".gov.in"),
			List.of("w3.org"), List.of("arxiv.org"), List.of("thehindu.com"), List.of("wikipedia.org"), 100, 12);

	static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

	@ParameterizedTest(name = "{0}: {1} → {3}")
	@CsvSource(delimiter = '|', value = {
			"DOI + venue      | PAPER      | https://doi.org/10.1000/x                 | HIGH   | Peer-reviewed paper, 2023",
			"docs domain      | WEB_PAGE   | https://docs.spring.io/spring-boot/x     | HIGH   | Official documentation",
			".gov.in          | WEB_PAGE   | https://www.education.gov.in/policy      | HIGH   | Government / standards source",
			"arXiv            | PREPRINT   | https://arxiv.org/abs/2201.00001         | MEDIUM | Preprint (not peer-reviewed), 2023",
			"blog             | WEB_PAGE   | https://someone.blogspot.com/post        | LOW    | Personal blog / unknown author",
			"newspaper        | WEB_PAGE   | https://www.thehindu.com/sci-tech/x      | MEDIUM | Established publication",
			"encyclopedia     | ENCYCLOPEDIA | https://en.wikipedia.org/wiki/X        | MEDIUM | Encyclopedia (community-edited)" })
	@DisplayName("T-12-06 credibility: tier and reason text by rule")
	void credibility(String name, String type, String url, Tier tier, String reason) {
		String doi = name.startsWith("DOI") ? "https://doi.org/10.1000/x" : null;
		Rating r = Credibility.rate(new Signals(type, doi, url, LocalDate.of(2023, 4, 1), null, null), RULES, TODAY);
		assertThat(r).isEqualTo(new Rating(tier, reason));
	}

	@Test
	@DisplayName("T-12-06 repositories: ≥ 100 stars and a commit in the last 12 months → MEDIUM, else LOW")
	void repos() {
		var maintained = new Signals("REPOSITORY", null, "https://github.com/a/b", null, 250, TODAY.minusMonths(2));
		var stale = new Signals("REPOSITORY", null, "https://github.com/a/b", null, 250, TODAY.minusMonths(20));
		var small = new Signals("REPOSITORY", null, "https://github.com/a/b", null, 12, TODAY.minusMonths(1));
		assertThat(Credibility.rate(maintained, RULES, TODAY)).isEqualTo(new Rating(Tier.MEDIUM, "Maintained repository (250★)"));
		assertThat(Credibility.rate(stale, RULES, TODAY).tier()).isEqualTo(Tier.LOW);
		assertThat(Credibility.rate(small, RULES, TODAY).tier()).isEqualTo(Tier.LOW);
		assertThat(Credibility.documentType("WEB_PAGE", "https://docs.spring.io/x", RULES)).isEqualTo("DOCS");
		assertThat(Credibility.documentType("WEB_PAGE", "https://blog.example/x", RULES)).isEqualTo("BLOG");
		assertThat(Credibility.documentType("REPOSITORY", "https://github.com/a/b", RULES)).isEqualTo("REPO");
	}

}
