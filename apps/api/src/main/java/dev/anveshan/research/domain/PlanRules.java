package dev.anveshan.research.domain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.anveshan.research.domain.RunOptions.SourceType;

/**
 * What a plan may contain (D-FR-1/3, invariant L6): 1–8 sub-questions, each with readable text, at least one
 * source type and a few search queries. Pure; used for both the planner's output and the user's edits.
 */
public final class PlanRules {

	public static final int MIN_SUB_QUESTIONS = 1;
	public static final int MAX_SUB_QUESTIONS = 8;
	public static final int MIN_TEXT = 5;
	public static final int MAX_TEXT = 300;
	public static final int MAX_QUERIES = 5;
	public static final int MAX_QUERY = 200;

	/** One sub-question as it will be saved: trimmed, de-duplicated, never without a query. */
	public record PlannedQuestion(String text, List<SourceType> sourceTypes, List<String> queries) {
	}

	private PlanRules() {
	}

	/**
	 * Normalizes and checks a plan. Source types fall back to the run's chosen sources; missing queries fall back
	 * to the question itself (the searcher needs something to search for).
	 *
	 * @throws IllegalArgumentException with a plain-language reason the user can act on
	 */
	public static List<PlannedQuestion> normalize(List<PlannedQuestion> plan, Set<SourceType> defaultSources) {
		if (plan == null || plan.size() < MIN_SUB_QUESTIONS || plan.size() > MAX_SUB_QUESTIONS) {
			throw new IllegalArgumentException("A plan needs " + MIN_SUB_QUESTIONS + "–" + MAX_SUB_QUESTIONS
					+ " sub-questions.");
		}
		List<PlannedQuestion> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (PlannedQuestion q : plan) {
			String text = q.text() == null ? "" : q.text().strip().replaceAll("\\s+", " ");
			int chars = text.codePointCount(0, text.length());
			if (chars < MIN_TEXT || chars > MAX_TEXT) {
				throw new IllegalArgumentException("Each sub-question must be " + MIN_TEXT + "–" + MAX_TEXT
						+ " characters.");
			}
			if (!seen.add(text.toLowerCase(java.util.Locale.ROOT))) {
				throw new IllegalArgumentException("Two sub-questions are the same: \"" + text + "\".");
			}
			Set<SourceType> types = q.sourceTypes() == null || q.sourceTypes().isEmpty() ? defaultSources
					: EnumSet.copyOf(q.sourceTypes());
			if (types.isEmpty()) {
				types = EnumSet.of(SourceType.WEB);
			}
			List<String> queries = new ArrayList<>();
			if (q.queries() != null) {
				for (String query : q.queries()) {
					String s = query == null ? "" : query.strip();
					if (!s.isEmpty() && queries.size() < MAX_QUERIES && !queries.contains(s)) {
						queries.add(s.length() > MAX_QUERY ? s.substring(0, MAX_QUERY) : s);
					}
				}
			}
			if (queries.isEmpty()) {
				queries.add(text.length() > MAX_QUERY ? text.substring(0, MAX_QUERY) : text);
			}
			out.add(new PlannedQuestion(text, List.copyOf(types.stream().sorted().toList()), List.copyOf(queries)));
		}
		return List.copyOf(out);
	}

}
