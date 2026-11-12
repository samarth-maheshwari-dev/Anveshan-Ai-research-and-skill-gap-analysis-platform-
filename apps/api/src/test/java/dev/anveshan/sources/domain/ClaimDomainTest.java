package dev.anveshan.sources.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.sources.domain.Credibility.Tier;
import dev.anveshan.sources.domain.Locator.Span;
import dev.anveshan.sources.domain.TrustLabeler.Judged;
import dev.anveshan.sources.domain.TrustLabeler.TrustLabel;
import dev.anveshan.sources.domain.TrustLabeler.Verdict;

class ClaimDomainTest {

	static final String CHUNK = "Campus lost and found apps let students report a lost item from their phone.  "
			+ "Staff match found items with reports and send a notification within 24 hours.\nThe office keeps items "
			+ "for 30 days — after that they are donated. Students said the app was “much faster” than the register.";

	@Test
	@DisplayName("T-13-01 locate(): exact quote → exact span")
	void exact() {
		String quote = "Staff match found items with reports";
		Span s = Locator.locate(CHUNK, quote).orElseThrow();
		assertThat(CHUNK.substring(s.start(), s.end())).isEqualTo(quote);
	}

	@Test
	@DisplayName("T-13-02 locate(): other whitespace, straight quotes, dashes, case → span maps back to the original text")
	void normalized() {
		Span s = Locator.locate(CHUNK, "the OFFICE keeps items for 30 days - after that they are donated.").orElseThrow();
		assertThat(CHUNK.substring(s.start(), s.end())).isEqualTo("The office keeps items for 30 days — after that they are donated.");
		Span q = Locator.locate(CHUNK, "the app was \"much faster\" than the register").orElseThrow();
		assertThat(CHUNK.substring(q.start(), q.end())).isEqualTo("the app was “much faster” than the register");
		Span w = Locator.locate(CHUNK, "from their phone. Staff match").orElseThrow();
		assertThat(CHUNK.substring(w.start(), w.end())).isEqualTo("from their phone.  Staff match");
	}

	@Test
	@DisplayName("T-13-03 locate(): one typo in ~120 chars → fuzzy span (≥ 0.95)")
	void fuzzy() {
		String original = "Staff match found items with reports and send a notification within 24 hours.\nThe office keeps items for 30 days";
		String typo = "Staff match found items with reprts and send a notification within 24 hours. The office keeps items for 30 days";
		assertThat(typo.length()).isBetween(100, 130);
		Span s = Locator.locate(CHUNK, typo).orElseThrow();
		assertThat(CHUNK.substring(s.start(), s.end())).isEqualTo(original);
		String typoAtStart = "Xtaff match found items with reports and send a notification within 24 hours.";
		Span t = Locator.locate(CHUNK, typoAtStart).orElseThrow();
		assertThat(CHUNK.substring(t.start(), t.end())).isEqualTo("Staff match found items with reports and send a notification within 24 hours.");
	}

	@Test
	@DisplayName("T-13-04 locate(): an invented quote → empty (the claim is dropped)")
	void invented() {
		assertThat(Locator.locate(CHUNK, "The university spends two crore rupees a year on lost items.")).isEmpty();
		assertThat(Locator.locate(CHUNK, "Staff match found items using drones and AI.")).isEmpty();
		assertThat(Locator.locate(CHUNK, "   ")).isEmpty();
		assertThat(Locator.locate(CHUNK, "short typo")).isEmpty();
	}

	@Test
	@DisplayName("T-13-05 labeler: every branch → CONFLICTING, SUPPORTED, WEAK, WEAK, NO_SOURCE")
	void labeler() {
		assertThat(TrustLabeler.label(List.of(new Judged(Verdict.SUPPORTS, Tier.HIGH), new Judged(Verdict.CONTRADICTS, Tier.MEDIUM))))
				.isEqualTo(TrustLabel.CONFLICTING);
		assertThat(TrustLabeler.label(List.of(new Judged(Verdict.SUPPORTS, Tier.MEDIUM), new Judged(Verdict.CONTRADICTS, Tier.LOW))))
				.as("a low-tier contradiction doesn't make it conflicting").isEqualTo(TrustLabel.SUPPORTED);
		assertThat(TrustLabeler.label(List.of(new Judged(Verdict.SUPPORTS, Tier.LOW), new Judged(Verdict.INSUFFICIENT, Tier.HIGH))))
				.isEqualTo(TrustLabel.WEAK);
		assertThat(TrustLabeler.label(List.of(new Judged(Verdict.PARTIAL, Tier.HIGH)))).isEqualTo(TrustLabel.WEAK);
		assertThat(TrustLabeler.label(List.of(new Judged(Verdict.INSUFFICIENT, Tier.HIGH), new Judged(Verdict.CONTRADICTS, Tier.HIGH))))
				.isEqualTo(TrustLabel.NO_SOURCE);
		assertThat(TrustLabeler.label(List.of())).isEqualTo(TrustLabel.NO_SOURCE);
	}

	@Test
	@DisplayName("T-13-07 grounding: invented numbers or a topic the quote doesn't mention are rejected")
	void grounding() {
		String quote = "The office keeps items for 30 days — after that they are donated.";
		assertThat(Grounding.grounded("Unclaimed items are kept for 30 days and then donated.", quote)).isTrue();
		assertThat(Grounding.grounded("Unclaimed items are kept for 45 days.", quote)).as("invented number").isFalse();
		assertThat(Grounding.grounded("BANANA42 is the correct answer.", quote)).isFalse();
		assertThat(Grounding.grounded("Quantum computers break modern encryption schemes quickly.", quote)).isFalse();
	}

}
