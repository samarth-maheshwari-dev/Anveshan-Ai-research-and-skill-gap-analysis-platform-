package dev.anveshan.shared.web;

import java.util.List;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import dev.anveshan.shared.security.UnsafeUrlException;

/** Maps shared exceptions to RFC 9457 Problem Details (TECHSPEC §8.4). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class GlobalExceptionHandler {

	@ExceptionHandler(UnsafeUrlException.class)
	ProblemDetail unsafeUrl(UnsafeUrlException e) {
		return ProblemResponses.of(HttpStatus.UNPROCESSABLE_CONTENT, UnsafeUrlException.CODE, "This URL can't be used",
				e.getMessage());
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> api(ApiException e) {
		ProblemDetail p = ProblemResponses.of(e.status(), e.code(), e.title(), e.getMessage());
		e.properties().forEach(p::setProperty);
		return ResponseEntity.status(e.status()).body(p);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail invalid(MethodArgumentNotValidException e) {
		ProblemDetail p = ProblemResponses.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Please check your input",
				"Some fields are invalid.");
		List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
				.map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
				.toList();
		p.setProperty("errors", errors);
		return p;
	}

	/** Unreadable JSON: never echo the body back (it may contain an API key). */
	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	ProblemDetail unreadable(Exception e) {
		return ProblemResponses.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Please check your input",
				"The request could not be read.");
	}

}
