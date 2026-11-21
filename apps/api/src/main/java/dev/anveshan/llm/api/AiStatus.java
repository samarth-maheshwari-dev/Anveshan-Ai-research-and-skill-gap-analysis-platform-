package dev.anveshan.llm.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the signed-in user sees about the shared AI service: whether it can answer now and how many research runs
 * they have left today (UTC day).
 *
 * @param state ONLINE = an endpoint can take calls; BUSY = every configured endpoint is cooling down after failures;
 *              OFFLINE = no endpoint is configured on this server
 */
public record AiStatus(
		@Schema(allowableValues = { "ONLINE", "BUSY", "OFFLINE" }, requiredMode = Schema.RequiredMode.REQUIRED) String state,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int runsUsedToday,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int runsLimit) {

	public static final String ONLINE = "ONLINE";
	public static final String BUSY = "BUSY";
	public static final String OFFLINE = "OFFLINE";

}
