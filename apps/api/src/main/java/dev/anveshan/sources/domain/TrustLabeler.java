package dev.anveshan.sources.domain;

import java.util.List;

import dev.anveshan.sources.domain.Credibility.Tier;

/**
 * The trust label of a claim in a run (PRD §14.3, TECHSPEC §12.8, invariant L2): a pure function of its verdicts, the
 * credibility tiers of the sources they came from, and whether each verdict came from a source <b>other than</b> the
 * one the claim was extracted from. Never decided by an LLM (RULES §0.8).
 * <p>
 * Independent-source rule (decision D-106, 2026-11-20): the claim's own passage always "supports" it — the claim was
 * taken from it — so that verdict alone can't make a claim SUPPORTED. SUPPORTED needs a High/Medium source that is
 * not the claim's own; own-source-only support is WEAK ("single source").
 *
 * <pre>
 * any Hi/Med support (own or other) + Hi/Med contradiction from another source → CONFLICTING
 * Hi/Med support from another source                                          → SUPPORTED
 * any other support (own source, low tier) or a partial                       → WEAK
 * nothing                                                                      → NO_SOURCE
 * </pre>
 */
public final class TrustLabeler {

	public enum TrustLabel {
		SUPPORTED, WEAK, CONFLICTING, NO_SOURCE
	}

	public enum Verdict {
		SUPPORTS, CONTRADICTS, INSUFFICIENT, PARTIAL
	}

	/**
	 * One verdict with the tier of the passage's source.
	 *
	 * @param independent true when the passage comes from a source other than the claim's own
	 */
	public record Judged(Verdict verdict, Tier tier, boolean independent) {

		/** A verdict from another source (the claim's own source is passed with {@code independent = false}). */
		public Judged(Verdict verdict, Tier tier) {
			this(verdict, tier, true);
		}

		boolean credible() {
			return tier != Tier.LOW;
		}

	}

	private TrustLabeler() {
	}

	public static TrustLabel label(List<Judged> verdicts) {
		boolean credibleSupport = verdicts.stream().anyMatch(v -> v.verdict() == Verdict.SUPPORTS && v.credible());
		boolean independentCredibleSupport = verdicts.stream()
				.anyMatch(v -> v.verdict() == Verdict.SUPPORTS && v.credible() && v.independent());
		boolean credibleContradiction = verdicts.stream()
				.anyMatch(v -> v.verdict() == Verdict.CONTRADICTS && v.credible() && v.independent());
		boolean anySupport = verdicts.stream()
				.anyMatch(v -> v.verdict() == Verdict.SUPPORTS || v.verdict() == Verdict.PARTIAL);
		if (credibleSupport && credibleContradiction) {
			return TrustLabel.CONFLICTING;
		}
		if (independentCredibleSupport) {
			return TrustLabel.SUPPORTED;
		}
		if (anySupport) {
			return TrustLabel.WEAK;
		}
		return TrustLabel.NO_SOURCE;
	}

	/** Supporting verdicts (Hi/Med or Low) that came from a source other than the claim's own. */
	public static long independentSupports(List<Judged> verdicts) {
		return verdicts.stream().filter(v -> v.verdict() == Verdict.SUPPORTS && v.independent()).count();
	}

}
