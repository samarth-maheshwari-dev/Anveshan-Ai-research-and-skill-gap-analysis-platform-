package dev.anveshan.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * SafeHttp against a real local server. "public.test" resolves to the server (loopback is treated
 * as public only in this test); "rebind.test" resolves to a private IP, like a DNS-rebinding attack.
 */
class SafeHttpIT {

	static HttpServer server;
	static int port;
	static SafeHttp http;
	static final AtomicInteger hits = new AtomicInteger();

	/** Test DNS: made-up names → chosen IPs. */
	static final DnsResolver TEST_DNS = new DnsResolver() {
		final Map<String, String> table = Map.of("public.test", "127.0.0.1", "rebind.test", "10.1.2.3",
				"metadata.test", "169.254.169.254");

		@Override
		public InetAddress[] resolve(String host) throws UnknownHostException {
			String ip = table.get(host);
			if (ip == null) {
				throw new UnknownHostException(host);
			}
			return new InetAddress[] { InetAddress.getByName(ip) };
		}

		@Override
		public String resolveCanonicalHostname(String host) {
			return host;
		}
	};

	/** Production rules, except the test server's loopback address counts as public. */
	static final OutboundUrlGuard GUARD = new OutboundUrlGuard(a -> !a.isLoopbackAddress() && OutboundUrlGuard.isBlocked(a));

	@BeforeAll
	static void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		port = server.getAddress().getPort();
		server.createContext("/", ex -> {
			hits.incrementAndGet();
			String path = ex.getRequestURI().getPath();
			switch (path) {
				case "/ok" -> send(ex, 200, "hello");
				case "/to-metadata" -> redirect(ex, "http://169.254.169.254/latest/meta-data/");
				case "/to-public-then-metadata" -> redirect(ex, "http://public.test:" + port + "/to-metadata");
				case "/to-rebind" -> redirect(ex, "http://rebind.test:" + port + "/ok");
				case "/loop" -> redirect(ex, "/loop");
				case "/limited" -> {
					ex.getResponseHeaders().add("Retry-After", "30");
					send(ex, 429, "slow down");
				}
				case "/big-chunked" -> sendBig(ex, 3 * 1024 * 1024, false);
				case "/big-declared" -> sendBig(ex, 3 * 1024 * 1024, true);
				default -> send(ex, 404, "nope");
			}
		});
		server.start();
		http = new SafeHttp(GUARD, new SafeDnsResolver(TEST_DNS, GUARD));
	}

	@AfterAll
	static void stop() throws IOException {
		http.close();
		server.stop(0);
	}

	@BeforeEach
	void reset() {
		hits.set(0);
	}

	static URI url(String path) {
		return URI.create("http://public.test:" + port + path);
	}

	static OutboundUrlGuard.Policy policy() {
		return OutboundUrlGuard.WEB_PAGE.withExtraPort(port);
	}

	@Test
	@DisplayName("T-06-04 happy path: public URL is fetched")
	void ok() throws IOException {
		var res = http.get(url("/ok"), policy(), SafeHttp.PAGE_LIMIT_BYTES);

		assertThat(res.status()).isEqualTo(200);
		assertThat(new String(res.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
	}

	@Test
	@DisplayName("T-06-04 redirect chain public → public → 169.254.169.254 is aborted at the private hop")
	void redirectToPrivateAborted() {
		assertThatThrownBy(() -> http.get(url("/to-public-then-metadata"), policy(), SafeHttp.PAGE_LIMIT_BYTES))
				.isInstanceOf(UnsafeUrlException.class);
		assertThat(hits.get()).as("only the two public hops reached a server").isEqualTo(2);
	}

	@Test
	@DisplayName("T-06-04 redirect to a host name that resolves privately is aborted before connecting")
	void redirectToRebindingHostAborted() {
		assertThatThrownBy(() -> http.get(url("/to-rebind"), policy(), SafeHttp.PAGE_LIMIT_BYTES))
				.isInstanceOf(SafeDnsResolver.BlockedAddressException.class);
		assertThat(hits.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("T-06-04 more than 3 redirects → aborted")
	void redirectLoop() {
		assertThatThrownBy(() -> http.get(url("/loop"), policy(), SafeHttp.PAGE_LIMIT_BYTES))
				.isInstanceOf(UnsafeUrlException.class).hasMessageContaining("Too many redirects");
		assertThat(hits.get()).isEqualTo(SafeHttp.MAX_REDIRECTS + 1);
	}

	@Test
	@DisplayName("T-06-05 DNS name resolving to a private IP is blocked at connect time — no request is sent")
	void dnsRebindingBlocked() {
		assertThatThrownBy(() -> http.get(URI.create("http://rebind.test:" + port + "/ok"), policy(),
				SafeHttp.PAGE_LIMIT_BYTES)).isInstanceOf(SafeDnsResolver.BlockedAddressException.class);
		assertThatThrownBy(() -> http.get(URI.create("http://metadata.test/latest"), OutboundUrlGuard.WEB_PAGE,
				SafeHttp.PAGE_LIMIT_BYTES)).isInstanceOf(SafeDnsResolver.BlockedAddressException.class);
		assertThat(hits.get()).isZero();
	}

	@Test
	@DisplayName("T-06-04 a 429 with Retry-After is returned at once — never slept on and resent")
	void noAutomaticRetry() throws IOException {
		long t0 = System.nanoTime();
		var res = http.get(url("/limited"), policy(), SafeHttp.PAGE_LIMIT_BYTES);

		assertThat(res.status()).isEqualTo(429);
		assertThat(hits.get()).isEqualTo(1);
		assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(5_000);
	}

	@Test
	@DisplayName("T-06-06 body over 2 MB is cut off (chunked and declared length)")
	void bodyLimit() {
		assertThatThrownBy(() -> http.get(url("/big-chunked"), policy(), SafeHttp.PAGE_LIMIT_BYTES))
				.isInstanceOf(SafeHttp.BodyTooLargeException.class);
		assertThatThrownBy(() -> http.get(url("/big-declared"), policy(), SafeHttp.PAGE_LIMIT_BYTES))
				.isInstanceOf(SafeHttp.BodyTooLargeException.class);
	}

	private static void send(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "text/plain");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void redirect(HttpExchange ex, String location) throws IOException {
		ex.getResponseHeaders().add("Location", location);
		ex.sendResponseHeaders(302, -1);
		ex.close();
	}

	private static void sendBig(HttpExchange ex, int size, boolean declareLength) throws IOException {
		ex.getResponseHeaders().add("Content-Type", "text/html");
		ex.sendResponseHeaders(200, declareLength ? size : 0);
		byte[] chunk = new byte[64 * 1024];
		try (OutputStream out = ex.getResponseBody()) {
			for (int written = 0; written < size; written += chunk.length) {
				out.write(chunk);
			}
		}
		catch (IOException clientWentAway) {
			// expected: SafeHttp stops reading once the limit is hit
		}
	}

}
