package dev.anveshan.llm.api;

import java.util.UUID;

/**
 * The ONLY way other modules call an LLM (RULES §8, TECHSPEC §10.2).
 *
 * <p>Sends the call down the developer-configured endpoint chain ({@code anveshan.llm.endpoints}), moving to the
 * next endpoint on 429 / 5xx / timeouts, asks for JSON matching {@code outputType}'s schema, validates it and
 * retries up to 3 times. LLM output is treated as untrusted until it parses and validates.
 *
 * @throws NoModelForSlotException     no endpoint is configured on this server
 * @throws AllRoutesExhaustedException every endpoint failed or is cooling down; retry at {@code resumeAt()}
 * @throws InvalidLlmOutputException   3 attempts produced no valid output
 */
public interface LlmGateway {

	/** A call outside a research run. */
	default <T> LlmResult<T> call(UUID ownerId, TaskSlot slot, Prompt prompt, Class<T> outputType) {
		return call(ownerId, slot, prompt, outputType, CallContext.NONE);
	}

	/** {@code context} ties every attempt in the usage log to its research run and step. */
	<T> LlmResult<T> call(UUID ownerId, TaskSlot slot, Prompt prompt, Class<T> outputType, CallContext context);

}
