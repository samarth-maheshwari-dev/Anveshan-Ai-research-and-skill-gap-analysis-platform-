package dev.anveshan.identity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Minimal browser: keeps cookies, never follows redirects, so every hop can be checked. */
final class Browser {

	private final String base;
	private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	final Map<String, String> cookies = new LinkedHashMap<>();
	final Map<String, String> rawSetCookie = new LinkedHashMap<>();

	Browser(String base) {
		this.base = base;
	}

	HttpResponse<String> get(String path, String... headers) {
		return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), headers);
	}

	HttpResponse<String> post(String path, String... headers) {
		return send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.noBody()), headers);
	}

	HttpResponse<String> options(String path, String... headers) {
		return send(HttpRequest.newBuilder(URI.create(base + path)).method("OPTIONS", HttpRequest.BodyPublishers.noBody()),
				headers);
	}

	private HttpResponse<String> send(HttpRequest.Builder b, String... headers) {
		for (int i = 0; i < headers.length; i += 2) {
			b.header(headers[i], headers[i + 1]);
		}
		if (!cookies.isEmpty()) {
			b.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
					.collect(Collectors.joining("; ")));
		}
		try {
			HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
			for (String sc : res.headers().allValues("Set-Cookie")) {
				String pair = sc.split(";", 2)[0];
				String name = pair.substring(0, pair.indexOf('='));
				String value = pair.substring(pair.indexOf('=') + 1);
				rawSetCookie.put(name, sc);
				if (value.isEmpty() || sc.toLowerCase().contains("max-age=0")) {
					cookies.remove(name);
				}
				else {
					cookies.put(name, value);
				}
			}
			return res;
		}
		catch (IOException | InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	static String location(HttpResponse<?> res) {
		return res.headers().firstValue("Location").orElseThrow(() -> new AssertionError("no Location header"));
	}

	static List<String> header(HttpResponse<?> res, String name) {
		return res.headers().allValues(name);
	}

}
