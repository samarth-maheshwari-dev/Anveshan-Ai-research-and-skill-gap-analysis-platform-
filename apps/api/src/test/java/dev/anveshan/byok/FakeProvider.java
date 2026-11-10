package dev.anveshan.byok;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Stand-in for every AI provider (JDK HttpServer). Each preset gets a path prefix
 * ({@code /gemini/}, {@code /anthropic/} …). Responses are scripted per API key, so two connections
 * of the same provider can behave differently. Every request is recorded for assertions.
 */
final class FakeProvider {

	record Reply(int status, Map<String, String> headers, String body) {

		static Reply json(int status, String body) {
			return new Reply(status, Map.of(), body);
		}

		static Reply chat(String content) {
			return json(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + quote(content)
					+ "}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}");
		}

		static Reply anthropic(String text) {
			return json(200, "{\"content\":[{\"type\":\"text\",\"text\":" + quote(text)
					+ "}],\"usage\":{\"input_tokens\":13,\"output_tokens\":5}}");
		}

		static Reply rateLimited(int retryAfterSeconds) {
			return new Reply(429, Map.of("retry-after", String.valueOf(retryAfterSeconds)),
					"{\"error\":{\"message\":\"rate limited\"}}");
		}

	}

	record Request(String method, String path, Map<String, String> headers, String body) {
	}

	static final List<String> PROVIDERS = List.of("gemini", "groq", "openrouter", "openai", "anthropic", "tavily");

	private static final Pattern MODEL = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");

	private final HttpServer server;
	private final Map<String, Deque<Reply>> scripts = new ConcurrentHashMap<>();
	private final Map<String, Map<String, Reply>> modelReplies = new ConcurrentHashMap<>();
	final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

	FakeProvider() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		server.createContext("/", this::handle);
		server.start();
	}

	String baseUrl(String provider) {
		return "http://localhost:" + server.getAddress().getPort() + "/" + provider + "/";
	}

	/** Queue replies for requests made with {@code apiKey} (in order). */
	void script(String apiKey, Reply... replies) {
		scripts.computeIfAbsent(apiKey, k -> new ArrayDeque<>()).addAll(List.of(replies));
	}

	/** Every request with {@code apiKey} asking for {@code model} gets {@code reply} (not consumed; wins over the queue). */
	void whenModel(String apiKey, String model, Reply reply) {
		modelReplies.computeIfAbsent(apiKey, k -> new ConcurrentHashMap<>()).put(model, reply);
	}

	List<Request> requestsWithKey(String apiKey) {
		synchronized (requests) {
			return requests.stream().filter(r -> apiKey.equals(keyOf(r.headers()))).toList();
		}
	}

	/** Models asked for in chat/test calls made with {@code apiKey}, in order. */
	List<String> chatModels(String apiKey) {
		return requestsWithKey(apiKey).stream().map(r -> modelOf(r.body())).filter(m -> m != null).toList();
	}

	/** A Gemini-style {@code /models} reply ({@code models/} prefix, as Google sends it). */
	static Reply geminiModels(String... ids) {
		StringBuilder data = new StringBuilder();
		for (String id : ids) {
			data.append(data.isEmpty() ? "" : ",").append("{\"id\":\"models/").append(id)
					.append("\",\"object\":\"model\",\"owned_by\":\"google\"}");
		}
		return Reply.json(200, "{\"object\":\"list\",\"data\":[" + data + "]}");
	}

	/** What Gemini answers for a model this (new) project can't use. */
	static Reply retired(String model) {
		return Reply.json(404, "{\"error\":{\"code\":404,\"message\":\"models/" + model
				+ " is not found for API version v1beta, or is not supported for generateContent.\",\"status\":\"NOT_FOUND\"}}");
	}

	static String modelOf(String body) {
		Matcher m = MODEL.matcher(body == null ? "" : body);
		return m.find() ? m.group(1) : null;
	}

	private void handle(HttpExchange ex) throws IOException {
		Map<String, String> headers = new HashMap<>();
		ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.getFirst()));
		String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		String path = ex.getRequestURI().getPath();
		requests.add(new Request(ex.getRequestMethod(), path, headers, body));

		String key = keyOf(headers);
		String model = modelOf(body);
		Map<String, Reply> byModel = key == null ? null : modelReplies.get(key);
		Reply reply = byModel == null || model == null ? null : byModel.get(model);
		Deque<Reply> q = key == null ? null : scripts.get(key);
		if (reply == null && q != null) {
			reply = q.poll();
		}
		if (reply == null) {
			reply = defaultReply(path);
		}
		byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		reply.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
		ex.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static Reply defaultReply(String path) {
		if (path.endsWith("/models")) {
			return Reply.json(200, """
					{"data":[
					  {"id":"text-embedding-3","name":"Embedding"},
					  {"id":"chat-pro","name":"Chat Pro","pricing":{"prompt":"0.000001","completion":"0.000002"}},
					  {"id":"chat-free:free","name":"Chat Free","pricing":{"prompt":"0","completion":"0"}}
					]}""");
		}
		if (path.startsWith("/anthropic/")) {
			return Reply.anthropic("OK");
		}
		if (path.startsWith("/tavily/")) {
			return Reply.json(200, "{\"results\":[]}");
		}
		return Reply.chat("OK");
	}

	static String keyOf(Map<String, String> headers) {
		String auth = headers.get("authorization");
		if (auth != null && auth.startsWith("Bearer ")) {
			return auth.substring(7);
		}
		return headers.get("x-api-key");
	}

	static String quote(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

}
