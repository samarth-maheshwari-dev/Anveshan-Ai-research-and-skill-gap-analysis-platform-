package dev.anveshan.sources.domain;

import java.time.LocalDate;

import dev.anveshan.sources.api.SourceSearch.CandidateType;

/**
 * Ranking score (TECHSPEC §11.2 step 3): meaning-similarity to the sub-question (cosine of local embeddings) plus
 * small boosts — scholarly types, recent work when the user asked for a time range, and the user's preferred
 * websites. The boosts only break near-ties; relevance dominates. Pure.
 */
public final class Ranking {

	static final double PAPER_BOOST = 0.05;
	static final double PREPRINT_BOOST = 0.03;
	static final double ENCYCLOPEDIA_BOOST = 0.02;
	static final double RECENT_BOOST = 0.03;
	static final double PREFERRED_SITE_BOOST = 0.05;

	private Ranking() {
	}

	/**
	 * @param similarity    cosine similarity of question and candidate text, −1…1
	 * @param since         the run's time range start (null = any time)
	 * @param preferredSite the candidate is on one of the user's preferred websites
	 */
	public static double score(double similarity, CandidateType type, LocalDate published, LocalDate since,
			boolean preferredSite) {
		double s = similarity;
		if (type == CandidateType.PAPER) {
			s += PAPER_BOOST;
		}
		else if (type == CandidateType.PREPRINT) {
			s += PREPRINT_BOOST;
		}
		else if (type == CandidateType.ENCYCLOPEDIA) {
			s += ENCYCLOPEDIA_BOOST;
		}
		if (since != null && published != null && !published.isBefore(since)) {
			s += RECENT_BOOST;
		}
		if (preferredSite) {
			s += PREFERRED_SITE_BOOST;
		}
		return Math.round(s * 10_000) / 10_000.0;
	}

}
