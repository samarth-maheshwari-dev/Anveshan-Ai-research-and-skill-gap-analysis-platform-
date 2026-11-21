package dev.anveshan.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Stand-in for the gateway endpoints (JDK HttpServer, no real providers). Each endpoint gets a path prefix
 * ({@code /freellmapi/}, {@code /gemini/} …) and one scripted reply that repeats until changed. Every request is
 * recorded with its path prefix and headers.
 */
public final class FakeGateway implements AutoCloseable {

	public record Reply(int status, Map<String, String> headers, String body, long delayMs) {

		public static Reply chat(String content) {
			return new Reply(200, Map.of(), "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
					+ quote(content) + "}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}", 0);
		}

		public static Reply status(int status) {
			return new Reply(status, Map.of(), "{\"error\":{\"message\":\"echo sk-secret\"}}", 0);
		}

		public static Reply rateLimited(int retryAfterSeconds) {
			return new Reply(429, Map.of("retry-after", String.valueOf(retryAfterSeconds)),
					"{\"error\":{\"message\":\"rate limited\"}}", 0);
		}

		public static Reply slow(long delayMs) {
			return new Reply(200, Map.of(), "{}", delayMs);
		}

	}

	public record Request(String endpoint, Map<String, String> headers, String body) {
	}

	private final HttpServer server;
	private final Map<String, Reply> replies = new ConcurrentHashMap<>();
	private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

	public FakeGateway() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
			var t = new Thread(r, "fake-gateway");
			t.setDaemon(true); // a leaked fake must never keep the test JVM alive
			return t;
		}));
		server.createContext("/", this::handle);
		server.start();
	}

	/** Base URL of one endpoint, without a trailing slash (like {@code http://localhost:3001/v1}). */
	public String baseUrl(String endpoint) {
		return "http://localhost:" + server.getAddress().getPort() + "/" + endpoint + "/v1";
	}

	public void reply(String endpoint, Reply reply) {
		replies.put(endpoint, reply);
	}

	public List<Request> requestsTo(String endpoint) {
		synchronized (requests) {
			return requests.stream().filter(r -> r.endpoint().equals(endpoint)).toList();
		}
	}

	public void reset() {
		replies.clear();
		requests.clear();
	}

	private void handle(HttpExchange ex) throws IOException {
		String endpoint = ex.getRequestURI().getPath().split("/")[1];
		Map<String, String> headers = new ConcurrentHashMap<>();
		ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v.getFirst()));
		requests.add(new Request(endpoint, headers, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
		Reply r = replies.getOrDefault(endpoint, Reply.status(503));
		if (r.delayMs() > 0) {
			try {
				Thread.sleep(r.delayMs());
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
		try {
			ex.sendResponseHeaders(r.status(), bytes.length);
			try (OutputStream out = ex.getResponseBody()) {
				out.write(bytes);
			}
		}
		catch (IOException e) {
			// the client gave up (timeout test)
		}
	}

	private static String quote(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	@Override
	public void close() {
		server.stop(0);
	}

}
