package dev.anveshan.sources.infra;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import dev.anveshan.sources.infra.SearchResilience.UpstreamException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET for the search APIs: public APIs go through the SSRF-guarded client (TECHSPEC §6.4); only the internal SearXNG
 * instance (a platform setting, never user input) uses a plain client. Bodies are capped; 5xx/429/network errors
 * are {@link UpstreamException} (retried), other non-2xx are {@link SearchApiException}. Error bodies are never
 * logged or returned.
 */
@Component
public class SearchHttp {

	static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

	/** A non-retryable API error (4xx except 429), e.g. a bad request or a rejected platform key. */
	public static class SearchApiException extends RuntimeException {

		public SearchApiException(String message) {
			super(message);
		}

	}

	private final RestClient guarded;
	private final RestClient internal;
	private final JsonMapper json;
	private final SearchProperties props;

	SearchHttp(@Qualifier("safeRestClient") RestClient guarded, JsonMapper json, SearchProperties props) {
		this.guarded = guarded;
		var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(java.time.Duration.ofSeconds(5));
		factory.setReadTimeout(java.time.Duration.ofSeconds(30));
		this.internal = RestClient.builder().requestFactory(factory).build();
		this.json = json;
		this.props = props;
	}

	public JsonNode getJson(URI uri, Map<String, String> headers) {
		return json.readTree(get(guarded, uri, headers, "application/json"));
	}

	public String getText(URI uri, Map<String, String> headers, String accept) {
		return get(guarded, uri, headers, accept);
	}

	/** Only for the configured SearXNG instance on the internal network. */
	public JsonNode getInternalJson(URI uri) {
		return json.readTree(get(internal, uri, Map.of(), "application/json"));
	}

	private String get(RestClient client, URI uri, Map<String, String> headers, String accept) {
		try {
			return client.get().uri(uri).headers(h -> {
				h.set("User-Agent", props.userAgent());
				h.set("Accept", accept);
				headers.forEach(h::set);
			}).exchange((req, resp) -> {
				HttpStatusCode s = resp.getStatusCode();
				if (s.is5xxServerError() || s.value() == 429) {
					throw new UpstreamException("HTTP " + s.value());
				}
				if (!s.is2xxSuccessful()) {
					throw new SearchApiException("HTTP " + s.value());
				}
				return read(resp.getBody());
			}, true);
		}
		catch (ResourceAccessException e) {
			throw new UpstreamException("unreachable");
		}
	}

	private static String read(InputStream in) throws IOException {
		byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			throw new SearchApiException("response too large");
		}
		return new String(body, StandardCharsets.UTF_8);
	}

}
