package dev.anveshan.llm.infra;

import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.anveshan.llm.api.CallContext;
import dev.anveshan.llm.api.TaskSlot;
import dev.anveshan.llm.domain.LlmEnums.LlmCallStatus;
import dev.anveshan.llm.domain.LlmCall;

/** One row per LLM attempt: counts and timings only, never prompts, outputs or keys (TECHSPEC §6.3). */
@Component
public class LlmCallLog {

	private final LlmCallRepository calls;

	LlmCallLog(LlmCallRepository calls) {
		this.calls = calls;
	}

	public void record(UUID ownerId, TaskSlot slot, String endpoint, String model, long latencyMs,
			LlmCallStatus status, Integer tokensIn, Integer tokensOut, String errorCode, CallContext context) {
		calls.save(new LlmCall(ownerId, slot, endpoint, model, (int) Math.min(latencyMs, Integer.MAX_VALUE), status)
				.withTokens(tokensIn, tokensOut)
				.withError(errorCode)
				.forRun(context.runId(), context.stepId()));
	}

}
