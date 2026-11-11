package dev.anveshan.research.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.anveshan.research.app.RunEngine.StepNotRetryableException;
import dev.anveshan.research.domain.PlanExceptions.PlanLockedException;
import dev.anveshan.research.domain.PlanExceptions.TooManyRunsException;
import dev.anveshan.research.domain.PlanExceptions.VersionConflictException;
import dev.anveshan.research.domain.RunInvalidStateException;
import dev.anveshan.shared.web.ProblemResponses;

/** Run state errors as RFC 9457 Problem Details (TECHSPEC §8.4): {@code 409 RUN_INVALID_STATE}. */
@RestControllerAdvice
class ResearchExceptionHandler {

	@ExceptionHandler(RunInvalidStateException.class)
	ProblemDetail invalidState(RunInvalidStateException e) {
		ProblemDetail p = ProblemResponses.of(HttpStatus.CONFLICT, e.code(), "This can't be done right now",
				e.getMessage());
		p.setProperty("currentStatus", e.from().name()); // "status" is the Problem's HTTP status field
		p.setProperty("requested", e.to().name());
		return p;
	}

	@ExceptionHandler(VersionConflictException.class)
	ProblemDetail versionConflict(VersionConflictException e) {
		ProblemDetail p = ProblemResponses.of(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Changed meanwhile", e.getMessage());
		p.setProperty("currentVersion", e.currentVersion());
		return p;
	}

	@ExceptionHandler(PlanLockedException.class)
	ProblemDetail planLocked(PlanLockedException e) {
		return ProblemResponses.of(HttpStatus.CONFLICT, "RUN_INVALID_STATE", "This can't be done right now", e.getMessage());
	}

	@ExceptionHandler(TooManyRunsException.class)
	ProblemDetail tooMany(TooManyRunsException e) {
		return ProblemResponses.of(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many researches running",
				e.getMessage());
	}

	@ExceptionHandler(StepNotRetryableException.class)
	ProblemDetail notRetryable(StepNotRetryableException e) {
		return ProblemResponses.of(HttpStatus.CONFLICT, "RUN_INVALID_STATE", "This can't be done right now",
				e.getMessage());
	}

}
