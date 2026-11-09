package dev.anveshan.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.anveshan.TestcontainersConfig;

/**
 * P05 end to end: real HTTP, real Spring Security, real Postgres sessions; GitHub is faked.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class LoginFlowIT {

	static final String APP = "http://localhost:3000";
	static final FakeGitHub GITHUB = new FakeGitHub();

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("management.server.port", () -> "0");
		r.add("anveshan.app-origin", () -> APP);
		r.add("anveshan.login-rate-per-minute", () -> "10000");
		r.add("anveshan.github-api-base", GITHUB::baseUrl);
		r.add("spring.security.oauth2.client.registration.github.client-id", () -> "test-client");
		r.add("spring.security.oauth2.client.registration.github.client-secret", () -> "test-secret");
		r.add("spring.security.oauth2.client.provider.github.authorization-uri", () -> GITHUB.baseUrl() + "/login/oauth/authorize");
		r.add("spring.security.oauth2.client.provider.github.token-uri", () -> GITHUB.baseUrl() + "/login/oauth/access_token");
		r.add("spring.security.oauth2.client.provider.github.user-info-uri", () -> GITHUB.baseUrl() + "/user");
	}

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	Browser browser() {
		return new Browser("http://localhost:" + port);
	}

	static long nextGithubId = 1000;

	static FakeGitHub.Account account(String email, boolean verified) {
		long id = ++nextGithubId;
		return new FakeGitHub.Account(id, "user" + id, "User " + id, email, true, verified);
	}

	/** Runs the OAuth dance like a browser would; returns the final redirect target. */
	String loginWithGitHub(Browser b, FakeGitHub.Account as, String next) {
		FakeGitHub.user = as;
		String q = next == null ? "" : "?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8);
		var start = b.get("/oauth2/authorization/github" + q);
		assertThat(start.statusCode()).isEqualTo(302);
		String authorize = Browser.location(start);
		assertThat(authorize).startsWith(GITHUB.baseUrl() + "/login/oauth/authorize");
		Matcher m = Pattern.compile("[?&]state=([^&]+)").matcher(authorize);
		assertThat(m.find()).isTrue();

		var callback = b.get("/login/oauth2/code/github?code=test-code&state=" + m.group(1));
		assertThat(callback.statusCode()).isEqualTo(302);
		return Browser.location(callback);
	}

	static UUID idOf(HttpResponse<String> me) {
		Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(me.body());
		assertThat(m.find()).as(me.body()).isTrue();
		return UUID.fromString(m.group(1));
	}

	long sessionsOf(UUID user) {
		return jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = :p")
				.param("p", user.toString()).query(Long.class).single();
	}

	UUID insertGoogleUser(String email) {
		UUID id = jdbc.sql("INSERT INTO app_user (email, email_verified, name) VALUES (:e, true, 'Google User') RETURNING id")
				.param("e", email).query(UUID.class).single();
		jdbc.sql("INSERT INTO user_identity (user_id, provider, provider_subject) VALUES (:u, 'GOOGLE', :s)")
				.param("u", id).param("s", UUID.randomUUID().toString()).update();
		return id;
	}

	@Test
	@DisplayName("T-05-01 new GitHub user → app_user + user_identity + session row; safe ?next honoured; secure cookies")
	void newUser() {
		var b = browser();
		var as = account("new" + UUID.randomUUID() + "@example.com", true);

		String target = loginWithGitHub(b, as, "/reports/abc-123");
		String sessionBefore = b.rawSetCookie.get("ANV_SESSION");

		assertThat(target).isEqualTo(APP + "/reports/abc-123");
		var me = b.get("/api/v1/me");
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(me.body()).contains("\"email\":\"" + as.email() + "\"", "\"identities\":[\"GITHUB\"]",
				"\"hasVerifiedConnection\":false");
		UUID id = idOf(me);
		assertThat(jdbc.sql("SELECT count(*) FROM user_identity WHERE user_id = :u AND provider = 'GITHUB' AND provider_subject = :s")
				.param("u", id).param("s", String.valueOf(as.id())).query(Long.class).single()).isEqualTo(1);
		assertThat(sessionsOf(id)).isEqualTo(1);

		String session = b.rawSetCookie.get("ANV_SESSION");
		assertThat(session).isNotNull().containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");
		assertThat(sessionBefore).isNotNull();
		assertThat(b.rawSetCookie.get("XSRF-TOKEN")).isNotNull().doesNotContainIgnoringCase("HttpOnly");
	}

	@Test
	@DisplayName("T-05-01 provider access token is not stored in the session table")
	void noProviderTokenInSession() {
		var b = browser();
		loginWithGitHub(b, account("tok" + UUID.randomUUID() + "@example.com", true), null);
		UUID id = idOf(b.get("/api/v1/me"));

		long leaks = jdbc.sql("""
				SELECT count(*) FROM spring_session s JOIN spring_session_attributes a ON a.session_primary_id = s.primary_id
				WHERE s.principal_name = :p AND position(convert_to('gho_fake_token', 'UTF8') in a.attribute_bytes) > 0
				""").param("p", id.toString()).query(Long.class).single();
		assertThat(leaks).isZero();
	}

	@Test
	@DisplayName("T-05-02 second login with the same provider_subject → same user, no duplicate")
	void sameSubjectSameUser() {
		var as = account("again" + UUID.randomUUID() + "@example.com", true);
		var first = browser();
		loginWithGitHub(first, as, null);
		UUID id1 = idOf(first.get("/api/v1/me"));

		var second = browser();
		loginWithGitHub(second, as, null);
		UUID id2 = idOf(second.get("/api/v1/me"));

		assertThat(id2).isEqualTo(id1);
		assertThat(jdbc.sql("SELECT count(*) FROM user_identity WHERE provider = 'GITHUB' AND provider_subject = :s")
				.param("s", String.valueOf(as.id())).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	@DisplayName("T-05-03 GitHub login with a VERIFIED email of an existing Google user → linked to that user")
	void verifiedEmailLinks() {
		String email = "ananya" + UUID.randomUUID() + "@example.com";
		UUID googleUser = insertGoogleUser(email);

		var b = browser();
		loginWithGitHub(b, account(email.toUpperCase(), true), null);
		var me = b.get("/api/v1/me");

		assertThat(idOf(me)).isEqualTo(googleUser);
		assertThat(me.body()).contains("\"identities\":[\"GOOGLE\",\"GITHUB\"]");
	}

	@Test
	@DisplayName("T-05-04 same email but UNVERIFIED → new separate user (no account takeover)")
	void unverifiedEmailDoesNotLink() {
		String email = "victim" + UUID.randomUUID() + "@example.com";
		UUID googleUser = insertGoogleUser(email);

		var b = browser();
		loginWithGitHub(b, account(email, false), null);
		var me = b.get("/api/v1/me");

		assertThat(idOf(me)).isNotEqualTo(googleUser);
		assertThat(me.body()).contains("@unverified.invalid").doesNotContain(email);
		assertThat(jdbc.sql("SELECT count(*) FROM user_identity WHERE user_id = :u").param("u", googleUser)
				.query(Long.class).single()).isEqualTo(1);
	}

	@Test
	@DisplayName("T-05-05 GET /me without a cookie → 401 Problem Details, no HTML")
	void meWithoutCookie() {
		var res = browser().get("/api/v1/me");

		assertThat(res.statusCode()).isEqualTo(401);
		assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(
				ct -> assertThat(ct).startsWith("application/problem+json"));
		assertThat(res.body()).contains("\"code\":\"NOT_AUTHENTICATED\"").doesNotContainIgnoringCase("<html");
	}

	@Test
	@DisplayName("T-05-06 POST without X-XSRF-TOKEN → 403, session stays valid")
	void postWithoutCsrf() {
		var b = browser();
		loginWithGitHub(b, account("csrf" + UUID.randomUUID() + "@example.com", true), null);

		var res = b.post("/api/v1/auth/logout");

		assertThat(res.statusCode()).isEqualTo(403);
		assertThat(res.body()).contains("\"code\":\"FORBIDDEN\"");
		assertThat(b.get("/api/v1/me").statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("T-05-07 Origin https://evil.example → no Access-Control-Allow-Origin; app origin → allowed")
	void cors() {
		var b = browser();
		var evil = b.get("/api/v1/me", "Origin", "https://evil.example");
		var evilPreflight = b.options("/api/v1/me", "Origin", "https://evil.example", "Access-Control-Request-Method", "GET");
		var good = b.options("/api/v1/me", "Origin", APP, "Access-Control-Request-Method", "GET");

		assertThat(Browser.header(evil, "Access-Control-Allow-Origin")).isEmpty();
		assertThat(Browser.header(evilPreflight, "Access-Control-Allow-Origin")).isEmpty();
		assertThat(Browser.header(good, "Access-Control-Allow-Origin")).containsExactly(APP);
		assertThat(Browser.header(good, "Access-Control-Allow-Credentials")).containsExactly("true");
	}

	@Test
	@DisplayName("T-05-08 ?next=https://evil.example → redirect to /dashboard on our own origin")
	void openRedirectBlocked() {
		String target = loginWithGitHub(browser(), account("next" + UUID.randomUUID() + "@example.com", true),
				"https://evil.example");

		assertThat(target).isEqualTo(APP + "/dashboard");
	}

	@Test
	@DisplayName("T-05-09 logout → 204; next GET /me → 401; session row deleted")
	void logout() {
		var b = browser();
		loginWithGitHub(b, account("bye" + UUID.randomUUID() + "@example.com", true), null);
		UUID id = idOf(b.get("/api/v1/me"));
		String xsrf = b.cookies.get("XSRF-TOKEN");
		assertThat(xsrf).isNotBlank();
		assertThat(sessionsOf(id)).isEqualTo(1);

		var res = b.post("/api/v1/auth/logout", "X-XSRF-TOKEN", xsrf);

		assertThat(res.statusCode()).isEqualTo(204);
		assertThat(b.get("/api/v1/me").statusCode()).isEqualTo(401);
		assertThat(sessionsOf(id)).isZero();
	}

	@Test
	@DisplayName("T-05-09 a session whose account was deleted → /me 401 and the session is ended")
	void deletedAccountSessionEnds() {
		var b = browser();
		loginWithGitHub(b, account("gone" + UUID.randomUUID() + "@example.com", true), null);
		UUID id = idOf(b.get("/api/v1/me"));

		jdbc.sql("UPDATE app_user SET deleted_at = now() WHERE id = :u").param("u", id).update();

		assertThat(b.get("/api/v1/me").statusCode()).isEqualTo(401);
		assertThat(sessionsOf(id)).isZero();
	}

}
