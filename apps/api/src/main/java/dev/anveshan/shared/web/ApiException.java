package dev.anveshan.shared.web;

import org.springframework.http.HttpStatus;

/**
 * An expected API error with a stable {@code code} (TECHSPEC §8.4). The detail must be safe to show
 * to the user — never secrets, never provider response bodies.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final String title;

	public ApiException(HttpStatus status, String code, String title, String detail) {
		super(detail);
		this.status = status;
		this.code = code;
		this.title = title;
	}

	/** Missing or not owned — both look the same to the caller (no existence leak, IDOR rule). */
	public static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", "This item doesn't exist.");
	}

	public static ApiException unprocessable(String code, String title, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, title, detail);
	}

	public static ApiException badRequest(String detail) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Please check your input", detail);
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public String title() {
		return title;
	}

}
