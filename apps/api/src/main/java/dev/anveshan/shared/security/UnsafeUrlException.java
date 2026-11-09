package dev.anveshan.shared.security;

/**
 * A URL or address the server must not contact (SSRF guard). Maps to HTTP 422 {@code UNSAFE_URL}.
 * The message never contains the URL itself (it may carry tokens in its query string).
 */
public class UnsafeUrlException extends RuntimeException {

	public static final String CODE = "UNSAFE_URL";

	public UnsafeUrlException(String reason) {
		super(reason);
	}

}
