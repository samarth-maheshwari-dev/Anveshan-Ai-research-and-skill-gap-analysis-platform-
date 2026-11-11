package dev.anveshan.research.domain;

/** The New Research form's closed choices (PRD Module C). Names are what the API and {@code config} JSON carry. */
public final class RunOptions {

	private RunOptions() {
	}

	public enum Objective {
		PROJECT_REPORT, COMPARISON, LEARNING, DECISION,
		/** The user's own words in {@code objectiveText}. */
		OTHER
	}

	/** PRD §14.4: sub-questions, sources per sub-question, chunks per source, rough minutes. */
	public enum Depth {

		QUICK(3, 4, 2, 5), STANDARD(5, 6, 2, 15), DEEP(6, 10, 3, 30),
		/** The user's own numbers ({@link ResearchSize}); these are only the starting values. */
		CUSTOM(5, 6, 2, 15);

		private final int subQuestions;
		private final int sourcesPerQuestion;
		private final int chunksPerSource;
		private final int minutes;

		Depth(int subQuestions, int sourcesPerQuestion, int chunksPerSource, int minutes) {
			this.subQuestions = subQuestions;
			this.sourcesPerQuestion = sourcesPerQuestion;
			this.chunksPerSource = chunksPerSource;
			this.minutes = minutes;
		}

		public int subQuestions() {
			return subQuestions;
		}

		public int sourcesPerQuestion() {
			return sourcesPerQuestion;
		}

		public int chunksPerSource() {
			return chunksPerSource;
		}

		public int minutes() {
			return minutes;
		}

	}

	/** YouTube is out of scope in v1 (PRD Module C). */
	public enum SourceType {
		WEB, ACADEMIC, GITHUB, BLOGS, DOCS
	}

	public enum TimeRange {
		ANY, LAST_1_YEAR, LAST_2_YEARS, LAST_5_YEARS,
		/** From {@code sinceYear} until today. */
		SINCE_YEAR
	}

	public enum OutputStyle {
		PROJECT_REPORT, COMPARISON_TABLE, EXECUTIVE_SUMMARY, STUDY_NOTES,
		/** The user's own format in {@code outputStyleText}. */
		CUSTOM
	}

	/** Output language; sources may still be English. */
	public enum Language {
		en, hi
	}

}
