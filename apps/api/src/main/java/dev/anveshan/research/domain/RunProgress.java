package dev.anveshan.research.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Run progress (0–100) as a pure function of where the run is in the stage graph (TECHSPEC §12.1). Later stages
 * are only materialized when they open, so "done steps / steps so far" would jump to 100 % after every stage.
 * Instead each of the research stages weighs the same, and the open stage counts by its share of finished steps.
 * Planning (stage 0) isn't research; 100 is reserved for COMPLETED.
 */
public final class RunProgress {

	/** Research stages after PLAN: SEARCH, FETCH, RELEVANCE, EXTRACT, VERIFY, LABEL, WRITE_SECTION, ASSEMBLE. */
	public static final int RESEARCH_STAGES = 8;

	static final BigDecimal RUNNING_CAP = BigDecimal.valueOf(99);

	private RunProgress() {
	}

	/**
	 * @param stage    the current (highest materialized) stage
	 * @param finished steps of that stage that are DONE or SKIPPED
	 * @param total    steps in that stage
	 */
	public static BigDecimal of(int stage, int finished, int total) {
		if (stage < 1 || total <= 0) {
			return BigDecimal.ZERO.setScale(2);
		}
		double share = Math.min(1.0, Math.max(0.0, (double) finished / total));
		double stagesDone = Math.min(RESEARCH_STAGES, stage - 1 + share);
		BigDecimal pct = BigDecimal.valueOf(100.0 * stagesDone / RESEARCH_STAGES).setScale(2, RoundingMode.DOWN);
		return pct.min(RUNNING_CAP.setScale(2));
	}

}
