package dev.anveshan.llm.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.anveshan.llm.api.AllRoutesExhaustedException;
import dev.anveshan.llm.api.DailyLimitException;
import dev.anveshan.llm.api.InvalidLlmOutputException;
import dev.anveshan.llm.api.NoModelForSlotException;
import dev.anveshan.llm.api.ProviderUnavailableException;
import dev.anveshan.shared.web.ProblemResponses;

/** Gateway errors as Problem Details for any controller that calls an LLM (TECHSPEC §8.4). */
@RestControllerAdvice
class LlmExceptionHandler {

	@ExceptionHandler(NoModelForSlotException.class)
	ProblemDetail noModel(NoModelForSlotException e) {
		ProblemDetail p = ProblemResponses.of(HttpStatus.UNPROCESSABLE_CONTENT, "NO_MODEL_FOR_SLOT", "AI service not set up",
				e.getMessage());
		p.setProperty("slot", e.slot().name());
		return p;
	}

	@ExceptionHandler(AllRoutesExhaustedException.class)
	ProblemDetail exhausted(AllRoutesExhaustedException e) {
		ProblemDetail p = ProblemResponses.of(HttpStatus.TOO_MANY_REQUESTS, "AI_LIMIT_REACHED", "AI limit reached",
				"The AI service is busy right now. Try again after " + e.resumeAt() + ".");
		p.setProperty("resumeAt", e.resumeAt().toString());
		return p;
	}

	@ExceptionHandler(DailyLimitException.class)
	ProblemDetail dailyLimit(DailyLimitException e) {
		ProblemDetail p = ProblemResponses.of(HttpStatus.TOO_MANY_REQUESTS, "LLM_DAILY_LIMIT", "Daily research limit reached",
				e.getMessage());
		p.setProperty("limit", e.limit());
		return p;
	}

	@ExceptionHandler(InvalidLlmOutputException.class)
	ProblemDetail invalid(InvalidLlmOutputException e) {
		return ProblemResponses.of(HttpStatus.BAD_GATEWAY, "INVALID_LLM_OUTPUT", "The AI answer was unusable",
				"The AI model didn't return a usable answer. Please try again.");
	}

	@ExceptionHandler(ProviderUnavailableException.class)
	ProblemDetail unavailable(ProviderUnavailableException e) {
		return ProblemResponses.of(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", "AI provider unavailable",
				"The AI service did not answer. Please try again in a minute.");
	}

}
