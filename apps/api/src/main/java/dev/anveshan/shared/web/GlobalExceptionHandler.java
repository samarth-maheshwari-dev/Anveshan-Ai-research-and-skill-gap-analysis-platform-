package dev.anveshan.shared.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.anveshan.shared.security.UnsafeUrlException;

/** Maps shared exceptions to RFC 9457 Problem Details (TECHSPEC §8.4). Modules add their own. */
@RestControllerAdvice
class GlobalExceptionHandler {

	@ExceptionHandler(UnsafeUrlException.class)
	ProblemDetail unsafeUrl(UnsafeUrlException e) {
		return ProblemResponses.of(HttpStatus.UNPROCESSABLE_CONTENT, UnsafeUrlException.CODE, "This URL can't be used",
				e.getMessage());
	}

}
