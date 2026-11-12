package dev.anveshan.research.domain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.anveshan.research.domain.RunOptions.Depth;

/**
 * How many AI requests and minutes a plan will roughly take (TECHSPEC §12.3, PRD §15.6, D-FR-2). Pure, no LLM:
 * recomputed on every plan edit (D-FR-4). Shown to the user, never enforced (L7).
 *
 * @param requests per task slot, in the order PLAN, EXTRACT, VERIFY, WRITE
 */
public record Estimate(Map<String, Integer> requests, int minutes) {

	/** The order requests are listed in (and shown). */
	public static final Comparator<String> SLOT_ORDER = Comparator
			.comparingInt(List.of("PLAN", "EXTRACT", "VERIFY", "WRITE")::indexOf);

	public Estimate {
		requests = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(requests));
	}

	/**
	 * @param subQuestions  1–8 (D-FR-3)
	 * @param sections      report sections the writer fills (one WRITE call each), ≥ 1
	 * @param cacheHitRate  share of claims expected from the cross-user claim cache, 0–1 (default 0)
	 */
	public static Estimate of(Depth depth, int subQuestions, int sections, double cacheHitRate) {
		return of(ResearchSize.of(depth, null, null), subQuestions, sections, cacheHitRate);
	}

	/** The same with the run's own size (a preset or the user's custom numbers). */
	public static Estimate of(ResearchSize size, int subQuestions, int sections, double cacheHitRate) {
		if (subQuestions < 1 || subQuestions > PlanRules.MAX_SUB_QUESTIONS) {
			throw new IllegalArgumentException("subQuestions must be 1–" + PlanRules.MAX_SUB_QUESTIONS);
		}
		if (sections < 1) {
			throw new IllegalArgumentException("sections must be >= 1");
		}
		if (cacheHitRate < 0 || cacheHitRate > 1) {
			throw new IllegalArgumentException("cacheHitRate must be 0–1");
		}
		int sources = subQuestions * size.sourcesPerQuestion();
		int extract = (int) Math.ceil(Math.ceil(sources / 2.5) * (1 - cacheHitRate));
		int verify = (int) Math.ceil(sources * 2 / 9.0);
		Map<String, Integer> requests = new LinkedHashMap<>();
		requests.put("PLAN", 1);
		requests.put("EXTRACT", extract);
		requests.put("VERIFY", verify);
		requests.put("WRITE", sections + 1); // one per section + one for the conflicts' possible reasons (§12.9)
		// time scales with the number of sub-questions; never under 2 minutes
		int minutes = Math.max(2, (int) Math.round(size.minutesPerQuestion() * subQuestions));
		return new Estimate(requests, minutes);
	}

	public int totalRequests() {
		return requests.values().stream().mapToInt(Integer::intValue).sum();
	}

}
