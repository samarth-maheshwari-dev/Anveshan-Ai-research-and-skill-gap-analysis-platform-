package dev.anveshan.research.domain;

import java.util.Comparator;
import java.util.List;

/**
 * Which unconfirmed claims the report lists as open questions (T-16-16). Pure: only claims at least
 * {@code minSimilarity} close in meaning to the run question or a sub-question, most relevant first, at most
 * {@code max}. Off-topic statements a source happened to contain (e.g. history in a job-readiness report) stay out.
 */
public final class OpenQuestions {

	/** A candidate and its highest cosine similarity to the question or a sub-question. */
	public record Candidate(String id, double similarity) {
	}

	private OpenQuestions() {
	}

	public static List<String> pick(List<Candidate> candidates, double minSimilarity, int max) {
		return candidates.stream().filter(c -> c.similarity() >= minSimilarity)
				.sorted(Comparator.comparingDouble(Candidate::similarity).reversed())
				.limit(Math.max(0, max)).map(Candidate::id).toList();
	}

}
