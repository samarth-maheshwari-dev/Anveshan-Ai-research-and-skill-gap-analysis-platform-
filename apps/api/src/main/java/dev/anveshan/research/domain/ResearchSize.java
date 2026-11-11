package dev.anveshan.research.domain;

import dev.anveshan.research.domain.RunOptions.Depth;

/**
 * How big a run is: a depth preset (PRD §14.4) or the user's own numbers (Depth CUSTOM). Drives the Planner's
 * sub-question count, the search fan-out (P11) and the estimate.
 *
 * @param minutesPerQuestion rough wall time per sub-question, for the estimate
 */
public record ResearchSize(int subQuestions, int sourcesPerQuestion, int chunksPerSource, double minutesPerQuestion) {

	public static final int MIN_SOURCES = 2;
	public static final int MAX_SOURCES = 10;

	/** About half a minute per source read (the presets work out to 0.42–0.5). */
	static final double MINUTES_PER_SOURCE = 0.5;

	/**
	 * @param customSubQuestions        used only for CUSTOM: 1–8 (null → the CUSTOM default)
	 * @param customSourcesPerQuestion  used only for CUSTOM: 2–10 (null → the CUSTOM default)
	 * @throws IllegalArgumentException custom numbers out of range (message is shown to the user)
	 */
	public static ResearchSize of(Depth depth, Integer customSubQuestions, Integer customSourcesPerQuestion) {
		if (depth != Depth.CUSTOM) {
			return new ResearchSize(depth.subQuestions(), depth.sourcesPerQuestion(), depth.chunksPerSource(),
					(double) depth.minutes() / depth.subQuestions());
		}
		int subQ = customSubQuestions == null ? depth.subQuestions() : customSubQuestions;
		int sources = customSourcesPerQuestion == null ? depth.sourcesPerQuestion() : customSourcesPerQuestion;
		if (subQ < PlanRules.MIN_SUB_QUESTIONS || subQ > PlanRules.MAX_SUB_QUESTIONS) {
			throw new IllegalArgumentException("Choose " + PlanRules.MIN_SUB_QUESTIONS + "–" + PlanRules.MAX_SUB_QUESTIONS
					+ " sub-questions.");
		}
		if (sources < MIN_SOURCES || sources > MAX_SOURCES) {
			throw new IllegalArgumentException("Choose " + MIN_SOURCES + "–" + MAX_SOURCES + " sources per sub-question.");
		}
		return new ResearchSize(subQ, sources, sources >= 8 ? 3 : 2, sources * MINUTES_PER_SOURCE);
	}

}
