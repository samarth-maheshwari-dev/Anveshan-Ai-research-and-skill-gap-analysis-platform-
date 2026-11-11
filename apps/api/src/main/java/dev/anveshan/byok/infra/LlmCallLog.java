package dev.anveshan.byok.infra;

import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.anveshan.byok.api.CallContext;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.byok.domain.ByokEnums.LlmCallStatus;
import dev.anveshan.byok.domain.LlmCall;

/** One row per LLM attempt: counts and timings only, never prompts, outputs or keys (TECHSPEC §6.3). */
@Component
public class LlmCallLog {

	private final LlmCallRepository calls;

	LlmCallLog(LlmCallRepository calls) {
		this.calls = calls;
	}

	public void record(UUID ownerId, TaskSlot slot, UUID connectionId, String model, long latencyMs,
			LlmCallStatus status, Integer tokensIn, Integer tokensOut, String errorCode, CallContext context) {
		calls.save(new LlmCall(ownerId, slot, connectionId, model, (int) Math.min(latencyMs, Integer.MAX_VALUE), status)
				.withTokens(tokensIn, tokensOut)
				.withError(errorCode)
				.forRun(context.runId(), context.stepId()));
	}

}
