package dev.anveshan.byok.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.anveshan.byok.api.AllRoutesExhaustedException;
import dev.anveshan.byok.api.InvalidLlmOutputException;
import dev.anveshan.byok.api.NoModelForSlotException;
import dev.anveshan.byok.api.ProviderUnavailableException;
import dev.anveshan.shared.web.ProblemResponses;

/** Gateway errors as Problem Details for any controller that calls an LLM (TECHSPEC §8.4). */
@RestControllerAdvice
class ByokExceptionHandler {

	@ExceptionHandler(NoModelForSlotException.class)
	ProblemDetail noModel(NoModelForSlotException e) {
		ProblemDetail p = ProblemResponses.of(HttpStatus.UNPROCESSABLE_CONTENT, "NO_MODEL_FOR_SLOT", "No AI model set up",
				e.getMessage());
		p.setProperty("slot", e.slot().name());
		return p;
	}

	@ExceptionHandler(AllRoutesExhaustedException.class)
	ProblemDetail exhausted(AllRoutesExhaustedException e) {
		ProblemDetail p = ProblemResponses.of(HttpStatus.TOO_MANY_REQUESTS, "AI_LIMIT_REACHED", "AI limit reached",
				"Your AI keys are rate-limited right now. Try again after " + e.resumeAt() + ".");
		p.setProperty("resumeAt", e.resumeAt().toString());
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
				"None of your AI connections answered. Check them in Settings → AI connections.");
	}

}
