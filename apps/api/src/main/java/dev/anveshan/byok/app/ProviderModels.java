package dev.anveshan.byok.app;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import dev.anveshan.byok.domain.ByokEnums.AiProvider;
import dev.anveshan.byok.domain.ProviderPreset;
import dev.anveshan.shared.security.Secret;

/** Value types shared by the gateway, router and transports. */
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

	/** A decrypted connection, alive only for one provider call. {@code secret} prints as ****. */
	public record ResolvedConnection(UUID id, UUID ownerId, AiProvider provider, ProviderPreset preset, URI baseUrl,
			boolean custom, Secret secret) {
	}

	/** One usable route: connection + model, at a fallback position. */
	public record ResolvedRoute(ResolvedConnection connection, String model, int position) {
	}

	public record RawCompletion(String text, Integer tokensIn, Integer tokensOut, long latencyMs,
			Integer remainingRequests, Integer remainingTokens) {
	}

	/** {@code chat}: can answer a chat request (false for embeddings, speech, images …), set by the ranking rules. */
	public record ModelInfo(String id, String displayName, boolean free, boolean chat) {

		/** As listed by a transport; whether it is a chat model is decided later by {@code ModelRanking}. */
		public ModelInfo(String id, String displayName, boolean free) {
			this(id, displayName, free, true);
		}

		ModelInfo withChat(boolean isChat) {
			return new ModelInfo(id, displayName, free, isChat);
		}

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
	 * The provider refused this request as too big for the model or for the key's per-minute token budget (HTTP 413,
	 * or a 400 saying the context is too long). The same request will never pass; a smaller one may.
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
