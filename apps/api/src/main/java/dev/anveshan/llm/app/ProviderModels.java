package dev.anveshan.llm.app;

import java.time.Instant;

/** Value types shared by the gateway, the endpoint chain and the transports. */
public final class ProviderModels {

	private ProviderModels() {
	}

	public record ChatMessage(String role, String content) {

		public static ChatMessage system(String c) {
			return new ChatMessage("system", c);
		}

		public static ChatMessage user(String c) {
			return new ChatMessage("user", c);
		}

		public static ChatMessage assistant(String c) {
			return new ChatMessage("assistant", c);
		}

	}

	public record CompletionOptions(double temperature, int maxTokens, boolean jsonMode) {
	}

	public record RawCompletion(String text, Integer tokensIn, Integer tokensOut, long latencyMs) {
	}

	/** Base for provider failures. Messages are fixed, human-readable texts — never provider bodies. */
	public abstract static class ProviderException extends RuntimeException {

		private final long latencyMs;

		ProviderException(String message, long latencyMs) {
			super(message);
			this.latencyMs = latencyMs;
		}

		public long latencyMs() {
			return latencyMs;
		}

		public abstract String code();

	}

	public static final class ProviderRateLimitException extends ProviderException {

		private final Instant retryAt;

		public ProviderRateLimitException(Instant retryAt, long latencyMs) {
			super("Rate-limited until " + retryAt, latencyMs);
			this.retryAt = retryAt;
		}

		public Instant retryAt() {
			return retryAt;
		}

		@Override
		public String code() {
			return "RATE_LIMITED";
		}

	}

	public static final class ProviderAuthException extends ProviderException {

		private final String code;

		public ProviderAuthException(String code, String message, long latencyMs) {
			super(message, latencyMs);
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

	public static final class ProviderModelNotFoundException extends ProviderException {

		public ProviderModelNotFoundException(long latencyMs) {
			super("Model not found", latencyMs);
		}

		@Override
		public String code() {
			return "MODEL_NOT_FOUND";
		}

	}

	/**
	 * The provider refused this request as too big for the model (HTTP 413, or a 400 saying the context is too long).
	 * The same request will never pass; a smaller one may.
	 */
	public static final class ProviderRequestTooLargeException extends ProviderException {

		public ProviderRequestTooLargeException(long latencyMs) {
			super("The request is too large for this model", latencyMs);
		}

		@Override
		public String code() {
			return "REQUEST_TOO_LARGE";
		}

	}

	public static final class ProviderCallException extends ProviderException {

		private final String code;

		public ProviderCallException(String code, String message, long latencyMs) {
			super(message, latencyMs);
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

}
