package dev.anveshan.shared.security;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Set;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * The one HTTP client for user-influenced URLs (TECHSPEC §6.4): every hop is checked by
 * {@link OutboundUrlGuard}, every connection goes through {@link SafeDnsResolver}, redirects are
 * followed manually (max 3, each re-checked), cookies are off, and bodies are size-capped while
 * streaming.
 */
public final class SafeHttp implements AutoCloseable {

	public static final int MAX_REDIRECTS = 3;
	public static final long PAGE_LIMIT_BYTES = 2L * 1024 * 1024;
	public static final long PDF_LIMIT_BYTES = 10L * 1024 * 1024;

	private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

	/** A fetched response. {@code body} is complete and within the limit. */
	public record Fetched(int status, URI finalUri, String contentType, byte[] body) {
	}

	public static final class BodyTooLargeException extends IOException {

		BodyTooLargeException(long limit) {
			super("Response body exceeds " + limit + " bytes");
		}

	}

	private final OutboundUrlGuard guard;
	private final CloseableHttpClient client;

	public SafeHttp(OutboundUrlGuard guard, SafeDnsResolver resolver) {
		this.guard = guard;
		var cm = PoolingHttpClientConnectionManagerBuilder.create()
				.setDnsResolver(resolver)
				.setDefaultConnectionConfig(ConnectionConfig.custom()
						.setConnectTimeout(Timeout.ofSeconds(5))
						.setSocketTimeout(Timeout.ofSeconds(60))
						.build())
				.setMaxConnTotal(100)
				.setMaxConnPerRoute(10)
				.build();
		this.client = HttpClients.custom()
				.setConnectionManager(cm)
				.setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(60)).build())
				.disableRedirectHandling()
				.disableCookieManagement()
				.disableAuthCaching()
				// No useSystemProperties(): a system proxy would resolve DNS itself and bypass SafeDnsResolver.
				.build();
	}

	/** The underlying client (already DNS-guarded, no redirects) for building a RestClient. */
	public CloseableHttpClient client() {
		return client;
	}

	public Fetched get(URI uri, OutboundUrlGuard.Policy policy, long maxBytes) throws IOException {
		URI current = guard.requireSafe(uri, policy);
		for (int hop = 0; ; hop++) {
			var request = new HttpGet(current);
			request.setHeader("User-Agent", "AnveshanBot/1.0 (+research assistant)");
			URI requested = current;
			Hop result = client.execute(request, response -> readHop(requested, response, maxBytes));
			if (result.redirect() == null) {
				return result.fetched();
			}
			if (hop >= MAX_REDIRECTS) {
				throw new UnsafeUrlException("Too many redirects");
			}
			current = guard.requireSafe(result.redirect(), policy);
		}
	}

	private record Hop(Fetched fetched, URI redirect) {
	}

	private static Hop readHop(URI requested, ClassicHttpResponse response, long maxBytes) throws IOException {
		int status = response.getCode();
		if (REDIRECTS.contains(status)) {
			Header location = response.getFirstHeader("Location");
			if (location == null) {
				throw new IOException("Redirect without Location");
			}
			return new Hop(null, requested.resolve(location.getValue().trim()));
		}
		HttpEntity entity = response.getEntity();
		byte[] body = entity == null ? new byte[0] : readLimited(entity, maxBytes);
		String contentType = entity == null ? null : entity.getContentType();
		return new Hop(new Fetched(status, requested, contentType, body), null);
	}

	static byte[] readLimited(HttpEntity entity, long maxBytes) throws IOException {
		if (entity.getContentLength() > maxBytes) {
			throw new BodyTooLargeException(maxBytes);
		}
		try (InputStream in = entity.getContent()) {
			var out = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			long total = 0;
			int n;
			while ((n = in.read(buf)) != -1) {
				total += n;
				if (total > maxBytes) {
					throw new BodyTooLargeException(maxBytes); // stream cut: rest is never read
				}
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	@Override
	public void close() throws IOException {
		client.close();
	}

}
