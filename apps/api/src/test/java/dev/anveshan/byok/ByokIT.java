package dev.anveshan.byok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.anveshan.TestcontainersConfig;
import dev.anveshan.byok.FakeProvider.Reply;
import dev.anveshan.byok.api.AllRoutesExhaustedException;
import dev.anveshan.byok.api.InvalidLlmOutputException;
import dev.anveshan.byok.api.LlmGateway;
import dev.anveshan.byok.api.NoModelForSlotException;
import dev.anveshan.byok.api.Prompt;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.identity.infra.security.SessionUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** P07 BYOK backend against a fake provider: real security, real Postgres, real encryption. */
@SpringBootTest(properties = "management.server.port=0")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class ByokIT {

	static final FakeProvider FAKE = new FakeProvider();

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("anveshan.outbound.allow-loopback", () -> "true");
		for (String p : FakeProvider.PROVIDERS) {
			r.add("anveshan.providers." + p.toUpperCase() + ".base-url", () -> FAKE.baseUrl(p));
		}
	}

	/** What the gateway tests ask the model to return. */
	record Answer(@NotBlank String answer, @Min(0) @Max(10) int score) {
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	@Autowired
	LlmGateway gateway;

	UUID alice;
	UUID bob;

	@BeforeEach
	void users() {
		alice = newUser("alice");
		bob = newUser("bob");
	}

	UUID newUser(String name) {
		return jdbc.sql("INSERT INTO app_user (email, email_verified) VALUES (:e, true) RETURNING id")
				.param("e", name + "-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
	}

	static String newKey(String prefix) {
		return prefix + UUID.randomUUID().toString().replace("-", "") + "XyZ9";
	}

	MockHttpServletRequestBuilder as(UUID user, MockHttpServletRequestBuilder req) {
		var token = new OAuth2AuthenticationToken(new SessionUser(user), List.of(new SimpleGrantedAuthority("ROLE_USER")),
				"google");
		return req.with(authentication(token)).with(csrf());
	}

	MvcResult perform(UUID user, MockHttpServletRequestBuilder req) throws Exception {
		return mvc.perform(as(user, req)).andReturn();
	}

	MvcResult postJson(UUID user, String url, String body) throws Exception {
		return perform(user, post(url).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	static String body(MvcResult r) {
		return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

	JsonNode jsonOf(MvcResult r) {
		return json.readTree(body(r));
	}

	UUID createConnection(UUID user, String provider, String key) throws Exception {
		String kind = provider.equals("TAVILY") ? "SEARCH" : "LLM";
		var res = postJson(user, "/api/v1/connections",
				"{\"kind\":\"" + kind + "\",\"provider\":\"" + provider + "\",\"label\":\"My " + provider
						+ "\",\"apiKey\":\"" + key + "\"}");
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(201);
		return UUID.fromString(jsonOf(res).path("id").asString());
	}

	/** Connection marked VERIFIED and routed to {@code slot} at the given positions. */
	void route(UUID user, TaskSlot slot, UUID... connectionIds) throws Exception {
		StringBuilder routes = new StringBuilder();
		for (UUID id : connectionIds) {
			jdbc.sql("UPDATE ai_connection SET status = 'VERIFIED' WHERE id = :id").param("id", id).update();
			routes.append(routes.isEmpty() ? "" : ",").append("{\"connectionId\":\"").append(id)
					.append("\",\"model\":\"model-").append(id.toString(), 0, 4).append("\"}");
		}
		var res = perform(user, put("/api/v1/slots").contentType(MediaType.APPLICATION_JSON)
				.content("{\"" + slot + "\":[" + routes + "]}"));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(200);
	}

	static Prompt prompt() {
		return Prompt.of("test", 1, "You grade answers.", "Grade: 2+2=4");
	}

	long llmCalls(UUID owner, String status) {
		return jdbc.sql("SELECT count(*) FROM llm_call WHERE owner_id = :o AND status = :s")
				.param("o", owner).param("s", status).query(Long.class).single();
	}

	// ---------------------------------------------------------------- connections

	@Test
	@DisplayName("T-07-01 POST /connections (Gemini) → 201 with keyLast4, no key; DB holds ciphertext only")
	void createConnection() throws Exception {
		String key = newKey("AIzaFake");
		var res = postJson(alice, "/api/v1/connections",
				"{\"kind\":\"LLM\",\"provider\":\"GEMINI\",\"label\":\"My Gemini\",\"apiKey\":\"" + key + "\",\"baseUrl\":null}");

		assertThat(res.getResponse().getStatus()).isEqualTo(201);
		JsonNode b = jsonOf(res);
		assertThat(b.path("keyLast4").asString()).isEqualTo("XyZ9");
		assertThat(b.path("status").asString()).isEqualTo("UNVERIFIED");
		assertThat(b.path("baseUrl").asString()).isEqualTo(FAKE.baseUrl("gemini"));
		assertThat(body(res)).doesNotContain(key).doesNotContain(key.substring(8, 20));

		byte[] stored = jdbc.sql("SELECT key_ciphertext FROM ai_connection WHERE id = :id")
				.param("id", UUID.fromString(b.path("id").asString())).query(byte[].class).single();
		assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(key.substring(8, 20));
		assertThat(body(perform(alice, get("/api/v1/connections")))).contains("XyZ9").doesNotContain(key);
	}

	@Test
	@DisplayName("T-07-01 bad input → 400 VALIDATION_FAILED and the key is never echoed back")
	void createValidation() throws Exception {
		String key = newKey("gsk_");
		var wrongKind = postJson(alice, "/api/v1/connections",
				"{\"kind\":\"SEARCH\",\"provider\":\"GROQ\",\"label\":\"x\",\"apiKey\":\"" + key + "\"}");
		var broken = postJson(alice, "/api/v1/connections", "{\"kind\":\"LLM\",\"apiKey\":\"" + key + "\",");

		assertThat(wrongKind.getResponse().getStatus()).isEqualTo(400);
		assertThat(broken.getResponse().getStatus()).isEqualTo(400);
		assertThat(body(wrongKind) + body(broken)).contains("VALIDATION_FAILED").doesNotContain(key);
	}

	@Test
	@DisplayName("T-07-02 test call: 200 → VERIFIED; 401 → Key invalid; 404 → Model not found; 429 → VERIFIED + note")
	void connectionTest() throws Exception {
		String key = newKey("gsk_");
		UUID id = createConnection(alice, "GROQ", key);
		FAKE.script(key, Reply.chat("OK"),
				Reply.json(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + key + "\"}}"),
				Reply.json(404, "{\"error\":{\"message\":\"model not found\"}}"), Reply.rateLimited(20));
		String test = "/api/v1/connections/" + id + "/test";
		String withModel = "{\"model\":\"llama-test\"}";

		var ok = postJson(alice, test, withModel);
		assertThat(ok.getResponse().getStatus()).isEqualTo(200);
		assertThat(jsonOf(ok).path("status").asString()).isEqualTo("VERIFIED");
		assertThat(jsonOf(ok).path("sampleModel").asString()).isEqualTo("llama-test");

		var badKey = postJson(alice, test, withModel);
		assertThat(badKey.getResponse().getStatus()).isEqualTo(422);
		assertThat(jsonOf(badKey).path("code").asString()).isEqualTo("CONNECTION_TEST_FAILED");
		assertThat(jsonOf(badKey).path("detail").asString()).isEqualTo("Key invalid");
		assertThat(body(badKey)).as("provider error body (which echoes the key) is never forwarded").doesNotContain(key);
		assertThat(status(id)).isEqualTo("FAILING");

		var noModel = postJson(alice, test, withModel);
		assertThat(noModel.getResponse().getStatus()).isEqualTo(422);
		assertThat(jsonOf(noModel).path("detail").asString()).isEqualTo("Model not found");

		var limited = postJson(alice, test, withModel);
		assertThat(limited.getResponse().getStatus()).isEqualTo(200);
		assertThat(jsonOf(limited).path("status").asString()).isEqualTo("VERIFIED");
		assertThat(jsonOf(limited).path("note").asString()).contains("rate-limited");
		assertThat(status(id)).isEqualTo("VERIFIED");

		var chat = FAKE.requestsWithKey(key).getFirst();
		assertThat(chat.path()).isEqualTo("/groq/chat/completions");
		assertThat(chat.body()).contains("\"max_tokens\":5", "\"model\":\"llama-test\"");
	}

	@Test
	@DisplayName("T-07-02 test without a model picks the first chat model; models list is cached and free-filterable")
	void modelsAndDefaultTestModel() throws Exception {
		String key = newKey("sk-or-v1-");
		UUID id = createConnection(alice, "OPENROUTER", key);

		var test = postJson(alice, "/api/v1/connections/" + id + "/test", "{}");
		assertThat(jsonOf(test).path("sampleModel").asString()).isEqualTo("chat-pro"); // embedding model skipped

		var all = jsonOf(perform(alice, get("/api/v1/connections/" + id + "/models")));
		var free = jsonOf(perform(alice, get("/api/v1/connections/" + id + "/models?freeOnly=true")));
		assertThat(all.path("items").size()).isEqualTo(3);
		assertThat(free.path("items").size()).isEqualTo(1);
		assertThat(free.path("items").path(0).path("id").asString()).isEqualTo("chat-free:free");
		long modelCalls = FAKE.requestsWithKey(key).stream().filter(r -> r.path().endsWith("/models")).count();
		assertThat(modelCalls).as("1 for the test + 1 for the list, then cached").isEqualTo(2);
	}

	@Test
	@DisplayName("T-08 (A-FR-6) first verified key fills all 6 empty slots; a later key never overwrites them")
	void defaultSlotsAfterFirstVerifiedKey() throws Exception {
		UUID first = createConnection(alice, "GEMINI", newKey("AIzaFirst"));
		UUID second = createConnection(alice, "GROQ", newKey("gsk_"));

		var r1 = postJson(alice, "/api/v1/connections/" + first + "/test", "{\"model\":\"gemini-flash\"}");
		var r2 = postJson(alice, "/api/v1/connections/" + second + "/test", "{\"model\":\"llama-fast\"}");

		assertThat(jsonOf(r1).path("defaultSlotsSet").asBoolean()).isTrue();
		assertThat(jsonOf(r2).path("defaultSlotsSet").asBoolean()).isFalse();
		JsonNode slots = jsonOf(perform(alice, get("/api/v1/slots")));
		for (TaskSlot s : TaskSlot.values()) {
			assertThat(slots.path(s.name()).size()).as(s.name()).isEqualTo(1);
			assertThat(slots.path(s.name()).path(0).path("connectionId").asString()).isEqualTo(first.toString());
			assertThat(slots.path(s.name()).path(0).path("model").asString()).isEqualTo("gemini-flash");
		}
		var me = jsonOf(perform(alice, get("/api/v1/me")));
		assertThat(me.path("onboarding").path("hasVerifiedConnection").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("T-07-02 Tavily search key is tested with a search call")
	void tavilyTest() throws Exception {
		String key = newKey("tvly-");
		UUID id = createConnection(alice, "TAVILY", key);

		var res = postJson(alice, "/api/v1/connections/" + id + "/test", "{}");

		assertThat(jsonOf(res).path("status").asString()).isEqualTo("VERIFIED");
		assertThat(FAKE.requestsWithKey(key).getFirst().path()).isEqualTo("/tavily/search");
	}

	// ---------------------------------------------------------------- authorization

	@Test
	@DisplayName("T-07-03 PUT /slots with another user's connection id → 404 (IDOR); every by-id endpoint too")
	void idor() throws Exception {
		UUID aliceConn = createConnection(alice, "GROQ", newKey("gsk_"));
		jdbc.sql("UPDATE ai_connection SET status = 'VERIFIED' WHERE id = :id").param("id", aliceConn).update();

		var slots = perform(bob, put("/api/v1/slots").contentType(MediaType.APPLICATION_JSON)
				.content("{\"PLAN\":[{\"connectionId\":\"" + aliceConn + "\",\"model\":\"m\"}]}"));
		assertThat(slots.getResponse().getStatus()).isEqualTo(404);
		assertThat(perform(bob, delete("/api/v1/connections/" + aliceConn)).getResponse().getStatus()).isEqualTo(404);
		assertThat(postJson(bob, "/api/v1/connections/" + aliceConn + "/test", "{}").getResponse().getStatus())
				.isEqualTo(404);
		assertThat(perform(bob, get("/api/v1/connections/" + aliceConn + "/models")).getResponse().getStatus())
				.isEqualTo(404);
		assertThat(body(perform(bob, get("/api/v1/connections")))).doesNotContain(aliceConn.toString());
		assertThat(status(aliceConn)).as("alice's connection untouched").isEqualTo("VERIFIED");
	}

	@Test
	@DisplayName("T-07-03 slots: unverified connection → 422; more than 5 routes → 400; valid PUT round-trips")
	void slotValidation() throws Exception {
		UUID conn = createConnection(alice, "GROQ", newKey("gsk_"));
		String one = "{\"connectionId\":\"" + conn + "\",\"model\":\"m\"}";

		var unverified = perform(alice, put("/api/v1/slots").contentType(MediaType.APPLICATION_JSON)
				.content("{\"PLAN\":[" + one + "]}"));
		assertThat(unverified.getResponse().getStatus()).isEqualTo(422);

		jdbc.sql("UPDATE ai_connection SET status = 'VERIFIED' WHERE id = :id").param("id", conn).update();
		var six = perform(alice, put("/api/v1/slots").contentType(MediaType.APPLICATION_JSON)
				.content("{\"PLAN\":[" + String.join(",", List.of(one, one, one, one, one, one)) + "]}"));
		assertThat(six.getResponse().getStatus()).isEqualTo(400);

		var ok = perform(alice, put("/api/v1/slots").contentType(MediaType.APPLICATION_JSON)
				.content("{\"PLAN\":[" + one + "],\"WRITE\":[" + one + "]}"));
		assertThat(ok.getResponse().getStatus()).isEqualTo(200);
		JsonNode got = jsonOf(perform(alice, get("/api/v1/slots")));
		assertThat(got.path("PLAN").path(0).path("model").asString()).isEqualTo("m");
		assertThat(got.path("WRITE").size()).isEqualTo(1);
		assertThat(got.path("ASK").size()).isZero();
	}

	@Test
	@DisplayName("T-07-04 custom base URL https://10.0.0.5/v1 → 422 UNSAFE_URL (also http:// and user@host)")
	void unsafeCustomUrl() throws Exception {
		for (String url : List.of("https://10.0.0.5/v1", "http://api.example.com/v1", "https://user@api.example.com/v1",
				"https://169.254.169.254/latest")) {
			var res = postJson(alice, "/api/v1/connections", "{\"kind\":\"LLM\",\"provider\":\"CUSTOM\",\"label\":\"c\","
					+ "\"apiKey\":\"" + newKey("sk-") + "\",\"baseUrl\":\"" + url + "\"}");
			assertThat(res.getResponse().getStatus()).as(url).isEqualTo(422);
			assertThat(jsonOf(res).path("code").asString()).isEqualTo("UNSAFE_URL");
		}
		assertThat(jdbc.sql("SELECT count(*) FROM ai_connection WHERE owner_id = :o").param("o", alice)
				.query(Long.class).single()).isZero();
	}

	// ---------------------------------------------------------------- router + gateway

	@Test
	@DisplayName("T-07-05 route 1 returns 429 retry-after 30 → call succeeds on route 2; route 1 cools until ≈ now+30s")
	void fallbackOnRateLimit() throws Exception {
		String k1 = newKey("gsk_");
		String k2 = newKey("gsk_");
		UUID c1 = createConnection(alice, "GROQ", k1);
		UUID c2 = createConnection(alice, "GROQ", k2);
		route(alice, TaskSlot.PLAN, c1, c2);
		FAKE.script(k1, Reply.rateLimited(30));
		FAKE.script(k2, Reply.chat("{\"answer\":\"4\",\"score\":9}"));

		Instant before = Instant.now();
		var result = gateway.call(alice, TaskSlot.PLAN, prompt(), Answer.class);

		assertThat(result.value()).isEqualTo(new Answer("4", 9));
		assertThat(result.connectionId()).isEqualTo(c2);
		Instant cooldown = jdbc.sql("SELECT cooldown_until FROM route_quota WHERE connection_id = :c")
				.param("c", c1).query(Timestamp.class).single().toInstant();
		assertThat(cooldown.getEpochSecond()).isCloseTo(before.plusSeconds(30).getEpochSecond(), within(5L));
		assertThat(llmCalls(alice, "RATE_LIMITED")).isEqualTo(1);
		assertThat(llmCalls(alice, "OK")).isEqualTo(1);
		assertThat(jdbc.sql("SELECT requests FROM route_quota WHERE connection_id = :c").param("c", c2)
				.query(Integer.class).single()).isEqualTo(1);

		// route 1 is now skipped without being called
		FAKE.script(k2, Reply.chat("{\"answer\":\"again\",\"score\":1}"));
		gateway.call(alice, TaskSlot.PLAN, prompt(), Answer.class);
		assertThat(FAKE.requestsWithKey(k1)).hasSize(1);
	}

	@Test
	@DisplayName("T-07-06 all routes 429 → AllRoutesExhaustedException with resumeAt = earliest cooldown")
	void allRoutesExhausted() throws Exception {
		String k1 = newKey("gsk_");
		String k2 = newKey("gsk_");
		UUID c1 = createConnection(alice, "GROQ", k1);
		UUID c2 = createConnection(alice, "GROQ", k2);
		route(alice, TaskSlot.EXTRACT, c1, c2);
		FAKE.script(k1, Reply.rateLimited(90));
		FAKE.script(k2, Reply.rateLimited(30));

		Instant before = Instant.now();
		assertThatThrownBy(() -> gateway.call(alice, TaskSlot.EXTRACT, prompt(), Answer.class))
				.isInstanceOfSatisfying(AllRoutesExhaustedException.class, e -> {
					assertThat(e.slot()).isEqualTo(TaskSlot.EXTRACT);
					assertThat(e.resumeAt().getEpochSecond()).isCloseTo(before.plusSeconds(30).getEpochSecond(), within(5L));
				});
	}

	@Test
	@DisplayName("T-07-06 no route for the slot → NoModelForSlotException; rejected key → connection FAILING")
	void noRouteAndBadKey() throws Exception {
		assertThatThrownBy(() -> gateway.call(alice, TaskSlot.ASK, prompt(), Answer.class))
				.isInstanceOf(NoModelForSlotException.class);

		String k = newKey("gsk_");
		UUID c = createConnection(alice, "GROQ", k);
		route(alice, TaskSlot.ASK, c);
		FAKE.script(k, Reply.json(401, "{}"));
		assertThatThrownBy(() -> gateway.call(alice, TaskSlot.ASK, prompt(), Answer.class))
				.isInstanceOf(dev.anveshan.byok.api.ProviderUnavailableException.class);
		assertThat(status(c)).isEqualTo("FAILING");
		assertThat(llmCalls(alice, "AUTH_ERROR")).isEqualTo(1);
	}

	@Test
	@DisplayName("T-07-07 model returns prose, then valid JSON → 2 calls; parsed record returned")
	void proseThenJson() throws Exception {
		String k = newKey("gsk_");
		UUID c = createConnection(alice, "GROQ", k);
		route(alice, TaskSlot.WRITE, c);
		FAKE.script(k, Reply.chat("Sure! The answer is four and I'd give it a 9."),
				Reply.chat("```json\n{\"answer\":\"four\",\"score\":9}\n```"));

		var result = gateway.call(alice, TaskSlot.WRITE, prompt(), Answer.class);

		assertThat(result.value()).isEqualTo(new Answer("four", 9));
		assertThat(result.attempts()).isEqualTo(2);
		var calls = FAKE.requestsWithKey(k);
		assertThat(calls).hasSize(2);
		assertThat(calls.getFirst().body()).contains("JSON Schema", "\"response_format\":{\"type\":\"json_object\"}");
		assertThat(calls.get(1).body()).contains("Your output was invalid");
		assertThat(llmCalls(alice, "INVALID_OUTPUT")).isEqualTo(1);
		assertThat(llmCalls(alice, "OK")).isEqualTo(1);
	}

	@Test
	@DisplayName("T-07-08 invalid JSON 3 times (bad JSON, unknown field, failed validation) → InvalidLlmOutputException + 3 INVALID_OUTPUT rows")
	void threeInvalid() throws Exception {
		String k = newKey("gsk_");
		UUID c = createConnection(alice, "GROQ", k);
		route(alice, TaskSlot.VERIFY, c);
		FAKE.script(k, Reply.chat("{not json"), Reply.chat("{\"answer\":\"x\",\"score\":3,\"extra\":true}"),
				Reply.chat("{\"answer\":\"\",\"score\":42}"));

		assertThatThrownBy(() -> gateway.call(alice, TaskSlot.VERIFY, prompt(), Answer.class))
				.isInstanceOf(InvalidLlmOutputException.class);
		assertThat(llmCalls(alice, "INVALID_OUTPUT")).isEqualTo(3);
		assertThat(llmCalls(alice, "OK")).isZero();
	}

	@Test
	@DisplayName("T-07-10 Anthropic transport: x-api-key, anthropic-version, top-level system, max_tokens")
	void anthropic() throws Exception {
		String k = newKey("sk-ant-api03-");
		UUID c = createConnection(alice, "ANTHROPIC", k);
		route(alice, TaskSlot.INTERVIEW, c);
		FAKE.script(k, Reply.anthropic("{\"answer\":\"claude-style\",\"score\":7}"));

		var result = gateway.call(alice, TaskSlot.INTERVIEW, prompt(), Answer.class);

		assertThat(result.value().answer()).isEqualTo("claude-style");
		assertThat(result.tokensIn()).isEqualTo(13);
		var req = FAKE.requestsWithKey(k).getFirst();
		assertThat(req.path()).isEqualTo("/anthropic/messages");
		assertThat(req.headers()).containsEntry("x-api-key", k).containsEntry("anthropic-version", "2023-06-01")
				.doesNotContainKey("authorization");
		JsonNode body = json.readTree(req.body());
		assertThat(body.path("system").asString()).contains("You grade answers.", "JSON Schema");
		assertThat(body.path("max_tokens").asInt()).isEqualTo(2048);
		assertThat(body.path("messages").size()).isEqualTo(1);
		assertThat(body.path("messages").path(0).path("role").asString()).isEqualTo("user");
	}

	@Test
	@DisplayName("T-07-11 full flow (create, test, fail, route, call) leaves zero key fragments in logs")
	void noKeysInLogs(CapturedOutput output) throws Exception {
		String key = newKey("gsk_");
		UUID c = createConnection(alice, "GROQ", key);
		FAKE.script(key, Reply.json(401, "{\"error\":\"bad key " + key + "\"}"), Reply.chat("OK"),
				Reply.chat("garbage"), Reply.chat("{\"answer\":\"a\",\"score\":1}"));
		postJson(alice, "/api/v1/connections/" + c + "/test", "{\"model\":\"m\"}");
		postJson(alice, "/api/v1/connections/" + c + "/test", "{\"model\":\"m\"}");
		route(alice, TaskSlot.PLAN, c);
		gateway.call(alice, TaskSlot.PLAN, prompt(), Answer.class);

		String logs = output.getAll();
		assertThat(logs).doesNotContain(key).doesNotContain(key.substring(4, 16)).doesNotContain(key.substring(10, 30));
	}

	String status(UUID connection) {
		return jdbc.sql("SELECT status FROM ai_connection WHERE id = :id").param("id", connection)
				.query(String.class).single();
	}

}
