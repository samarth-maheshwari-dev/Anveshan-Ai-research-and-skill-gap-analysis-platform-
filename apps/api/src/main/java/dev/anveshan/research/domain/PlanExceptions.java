package dev.anveshan.research.domain;

/** Plan edit / approve refusals (Module D, TECHSPEC §8.1). Mapped to Problem Details in research.web. */
public final class PlanExceptions {

	private PlanExceptions() {
	}

	/** {@code If-Match} names an older version: someone (another tab, a teammate) changed the run → 409. */
	public static class VersionConflictException extends RuntimeException {

		private final int currentVersion;

		public VersionConflictException(int currentVersion) {
			super("This plan was changed since you opened it. Reload to see the latest version.");
			this.currentVersion = currentVersion;
		}

		public int currentVersion() {
			return currentVersion;
		}

	}

	/** The plan is editable only while the run waits for approval (L6) → 409 RUN_INVALID_STATE. */
	public static class PlanLockedException extends RuntimeException {

		public PlanLockedException() {
			super("The plan can only be changed while it waits for your approval.");
		}

	}

	/** TECHSPEC §12.2: at most this many runs RUNNING or PAUSED per user → 429 RATE_LIMITED. */
	public static class TooManyRunsException extends RuntimeException {

		public TooManyRunsException(int max) {
			super("You already have " + max + " researches running. Wait for one to finish or stop one first.");
		}

	}

}
