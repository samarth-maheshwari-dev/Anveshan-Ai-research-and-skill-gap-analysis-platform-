package dev.anveshan.identity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A tiny stand-in for GitHub's OAuth + REST endpoints (JDK HttpServer, no extra libraries).
 * Tests set {@link #user} before each login; the server answers with that profile.
 */
final class FakeGitHub {

	record Account(long id, String login, String name, String email, boolean primary, boolean verified) {
	}

	static volatile Account user = new Account(1, "octo", "Octo Cat", "octo@example.com", true, true);

	private final HttpServer server;

	FakeGitHub() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		server.createContext("/login/oauth/access_token", ex -> json(ex, 200,
				"{\"access_token\":\"gho_fake_token\",\"token_type\":\"bearer\",\"scope\":\"read:user,user:email\"}"));
		server.createContext("/user/emails", ex -> {
			if (!authorized(ex)) {
				json(ex, 401, "{\"message\":\"Bad credentials\"}");
				return;
			}
			Account a = user;
			json(ex, 200, "[{\"email\":\"" + a.email() + "\",\"primary\":" + a.primary() + ",\"verified\":"
					+ a.verified() + ",\"visibility\":\"private\"}]");
		});
		server.createContext("/user", ex -> {
			if (!authorized(ex)) {
				json(ex, 401, "{\"message\":\"Bad credentials\"}");
				return;
			}
			Account a = user;
			json(ex, 200, "{\"id\":" + a.id() + ",\"login\":\"" + a.login() + "\",\"name\":\"" + a.name()
					+ "\",\"avatar_url\":\"https://avatars.example/" + a.id() + "\",\"email\":null}");
		});
		server.start();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private static boolean authorized(HttpExchange ex) {
		return "Bearer gho_fake_token".equals(ex.getRequestHeaders().getFirst("Authorization"));
	}

	private static void json(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(bytes);
		}
	}

}
