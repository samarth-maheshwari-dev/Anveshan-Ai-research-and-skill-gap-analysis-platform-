package dev.anveshan.sources.domain;

import java.util.List;

import dev.anveshan.sources.domain.Credibility.Tier;

/**
 * The trust label of a claim in a run (PRD §14.3, TECHSPEC §12.8, invariant L2): a pure function of its verdicts and
 * the credibility tiers of the sources they came from. Never decided by an LLM (RULES §0.8).
 */
public final class TrustLabeler {

	public enum TrustLabel {
		SUPPORTED, WEAK, CONFLICTING, NO_SOURCE
	}

	public enum Verdict {
		SUPPORTS, CONTRADICTS, INSUFFICIENT, PARTIAL
	}

	/** One verdict with the tier of the passage's source. */
	public record Judged(Verdict verdict, Tier tier) {
	}

	private TrustLabeler() {
	}

	public static TrustLabel label(List<Judged> verdicts) {
		long supportsHiMed = verdicts.stream().filter(v -> v.verdict() == Verdict.SUPPORTS && v.tier() != Tier.LOW).count();
		long supportsLow = verdicts.stream().filter(v -> v.verdict() == Verdict.SUPPORTS && v.tier() == Tier.LOW).count();
		long contradictsHiMed = verdicts.stream()
				.filter(v -> v.verdict() == Verdict.CONTRADICTS && v.tier() != Tier.LOW).count();
		boolean partial = verdicts.stream().anyMatch(v -> v.verdict() == Verdict.PARTIAL);
		if (supportsHiMed >= 1 && contradictsHiMed >= 1) {
			return TrustLabel.CONFLICTING;
		}
		if (supportsHiMed >= 1) {
			return TrustLabel.SUPPORTED;
		}
		if (supportsLow >= 1 || partial) {
			return TrustLabel.WEAK;
		}
		return TrustLabel.NO_SOURCE;
	}

}
