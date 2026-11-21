package dev.anveshan.research.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Report tunables ({@code anveshan.research.report.*}). All required (RULES §4.7).
 *
 * @param openQuestionMinSimilarity an unconfirmed claim is listed as an open question only when its cosine similarity
 *                                  to the run question or a sub-question is at least this (T-16-16)
 * @param maxOpenQuestions          at most this many open questions, most relevant first
 */
@ConfigurationProperties("anveshan.research.report")
public record ReportQualityProperties(double openQuestionMinSimilarity, int maxOpenQuestions) {

	public ReportQualityProperties {
		if (openQuestionMinSimilarity < -1 || openQuestionMinSimilarity > 1 || maxOpenQuestions < 0) {
			throw new IllegalStateException("anveshan.research.report.open-question-min-similarity (-1..1) and "
					+ "max-open-questions (>= 0) are required");
		}
	}

}
