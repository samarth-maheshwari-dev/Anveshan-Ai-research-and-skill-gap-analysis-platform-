package dev.anveshan.llm.api;

/** The user started their allowed number of research runs today (HTTP 429 {@code LLM_DAILY_LIMIT}). */
public class DailyLimitException extends RuntimeException {

	private final int limit;

	public DailyLimitException(int limit) {
		super("You can start " + limit + " research runs a day. Try again tomorrow.");
		this.limit = limit;
	}

	public int limit() {
		return limit;
	}

}
