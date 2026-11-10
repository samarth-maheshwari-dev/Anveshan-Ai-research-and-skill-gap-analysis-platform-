package dev.anveshan.byok.infra;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import dev.anveshan.byok.app.ProviderModels.ProviderAuthException;
import dev.anveshan.byok.app.ProviderModels.ProviderCallException;
import dev.anveshan.byok.app.ProviderModels.ProviderException;
import dev.anveshan.byok.app.ProviderModels.ProviderModelNotFoundException;
import dev.anveshan.byok.app.ProviderModels.ProviderRateLimitException;
import dev.anveshan.byok.app.ProviderModels.ResolvedConnection;
import dev.anveshan.byok.domain.RateLimitHeaders;
import dev.anveshan.shared.security.OutboundUrlGuard;

/**
 * HTTP for provider calls: the SSRF-guarded client (TECHSPEC §6.4), bounded response bodies, and
 * one place that turns HTTP statuses into provider exceptions. Provider error bodies are never
 * logged or returned (they can echo parts of the key).
 */
@Component
class ProviderHttp {

	static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

	record Reply(int status, HttpHeaders headers, String body, long latencyMs) {

		boolean ok() {
			return status >= 200 && status < 300;
		}

	}

	private final RestClient http;
	private final OutboundUrlGuard guard;
	private final Clock clock;

	ProviderHttp(RestClient safeRestClient, OutboundUrlGuard guard, Clock clock) {
		this.http = safeRestClient;
		this.guard = guard;
		this.clock = clock;
	}

	/** Custom (user-supplied) base URLs are re-checked on every call; presets are trusted config. */
	URI endpoint(ResolvedConnection c, String path) {
		URI uri = c.baseUrl().resolve(path);
		return c.custom() ? guard.requireSafe(uri, OutboundUrlGuard.HTTPS_ONLY) : uri;
	}

	Reply post(URI uri, Map<String, String> headers, String json) {
		long t0 = System.nanoTime();
		try {
			return http.post().uri(uri)
					.headers(h -> headers.forEach(h::set))
					.contentType(MediaType.APPLICATION_JSON)
					.accept(MediaType.APPLICATION_JSON)
					.body(json)
					.exchange((req, resp) -> new Reply(resp.getStatusCode().value(), copy(resp.getHeaders()),
							read(resp.getBody()), ms(t0)), true);
		}
		catch (ResourceAccessException e) {
			throw new ProviderCallException("UNREACHABLE", "Could not reach the provider", ms(t0));
		}
	}

	Reply get(URI uri, Map<String, String> headers) {
		long t0 = System.nanoTime();
		try {
			return http.get().uri(uri)
					.headers(h -> headers.forEach(h::set))
					.accept(MediaType.APPLICATION_JSON)
					.exchange((req, resp) -> new Reply(resp.getStatusCode().value(), copy(resp.getHeaders()),
							read(resp.getBody()), ms(t0)), true);
		}
		catch (ResourceAccessException e) {
			throw new ProviderCallException("UNREACHABLE", "Could not reach the provider", ms(t0));
		}
	}

	/** Gemini answers a wrong key with HTTP 400 (not 401); others use these phrases in their 400s. */
	static final Pattern INVALID_KEY = Pattern
			.compile("api_key_invalid|api key not valid|invalid api key|invalid_api_key|incorrect api key");

	/**
	 * Explicit "this model can't be used" signals in a 400 body. Deliberately narrow: a 400 that only
	 * mentions a model ("max_tokens is not supported with this model") is a request problem, not a missing model.
	 */
	static final Pattern MODEL_NOT_FOUND = Pattern.compile("model_not_found|model_decommissioned|decommissioned"
			+ "|unexpected model name|not a valid model"
			+ "|model[^.]{0,80}(does not exist|not found|is not supported for generatecontent)");

	/**
	 * Non-2xx → the matching provider exception. The body is read only to classify the error; it is
	 * never logged, returned or put into an exception message (providers echo key fragments).
	 */
	ProviderException error(Reply r) {
		int s = r.status();
		if (s == 429) {
			return new ProviderRateLimitException(RateLimitHeaders.retryAt(r.headers()::getFirst, clock.instant()),
					r.latencyMs());
		}
		// 400 and 403 bodies are read (classification only): OpenAI answers a model the project can't use
		// with 403 model_not_found, Groq a retired model with 400 model_decommissioned.
		String body = s == 400 || s == 403 ? r.body().toLowerCase(Locale.ROOT) : "";
		if (s == 403 && MODEL_NOT_FOUND.matcher(body).find()) {
			return new ProviderModelNotFoundException(r.latencyMs());
		}
		if (s == 401 || s == 403) {
			return new ProviderAuthException("AUTH_ERROR", "Key invalid", r.latencyMs());
		}
		if (s == 402) {
			return new ProviderAuthException("PAYMENT_REQUIRED", "The provider account has no credit left", r.latencyMs());
		}
		if (INVALID_KEY.matcher(body).find()) {
			return new ProviderAuthException("AUTH_ERROR", "Key invalid", r.latencyMs());
		}
		if (s == 404 || MODEL_NOT_FOUND.matcher(body).find()) {
			return new ProviderModelNotFoundException(r.latencyMs());
		}
		return new ProviderCallException(s >= 500 ? "PROVIDER_UNAVAILABLE" : "PROVIDER_ERROR",
				"The provider returned an error (HTTP " + s + ")", r.latencyMs());
	}

	private static HttpHeaders copy(HttpHeaders h) {
		HttpHeaders out = new HttpHeaders();
		h.forEach(out::addAll);
		return out;
	}

	private static String read(InputStream in) throws IOException {
		if (in == null) {
			return "";
		}
		byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
		if (bytes.length > MAX_BODY_BYTES) {
			throw new IOException("Provider response too large");
		}
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static long ms(long t0) {
		return (System.nanoTime() - t0) / 1_000_000;
	}

}
