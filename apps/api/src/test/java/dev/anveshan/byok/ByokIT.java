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

	/** Test value for the Gemini preset hint, so these tests don't depend on the production model name. */
	static final String GEMINI_HINT = "gemini-hint";

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("anveshan.outbound.allow-loopback", () -> "true");
		for (String p : FakeProvider.PROVIDERS) {
			r.add("anveshan.providers." + p.toUpperCase() + ".base-url", () -> FAKE.baseUrl(p));
		}
		r.add("anveshan.providers.GEMINI.test-model", () -> GEMINI_HINT);
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
		// P08: the test request is the smallest one every model accepts (T-08-14)
		assertThat(chat.body()).contains("\"model\":\"llama-test\"").doesNotContain("max_tokens")
				.doesNotContain("temperature").doesNotContain("response_format");
	}

	@Test
	@DisplayName("T-07-02 test without a model picks the best-ranked chat model (OpenRouter: :free first, never an embedding); the list is cached, ranked, flagged and free-filterable")
	void modelsAndDefaultTestModel() throws Exception {
		String key = newKey("sk-or-v1-");
		UUID id = createConnection(alice, "OPENROUTER", key);

		var test = postJson(alice, "/api/v1/connections/" + id + "/test", "{}");
		assertThat(jsonOf(test).path("sampleModel").asString()).isEqualTo("chat-free:free");

		var all = jsonOf(perform(alice, get("/api/v1/connections/" + id + "/models")));
		var free = jsonOf(perform(alice, get("/api/v1/connections/" + id + "/models?freeOnly=true")));
		assertThat(all.path("items").size()).isEqualTo(3);
		assertThat(all.path("items").path(0).path("id").asString()).isEqualTo("chat-free:free");
		assertThat(all.path("items").path(1).path("id").asString()).isEqualTo("chat-pro");
		assertThat(all.path("items").path(2).path("id").asString()).isEqualTo("text-embedding-3");
		assertThat(all.path("items").path(2).path("chat").asBoolean()).as("embeddings are not chat models").isFalse();
		assertThat(all.path("items").path(0).path("chat").asBoolean()).isTrue();
		assertThat(free.path("items").size()).isEqualTo(1);
		assertThat(free.path("items").path(0).path("id").asString()).isEqualTo("chat-free:free");
		long modelCalls = FAKE.requestsWithKey(key).stream().filter(r -> r.path().endsWith("/models")).count();
		assertThat(modelCalls).as("the test fills the 1-hour cache; both list calls reuse it").isEqualTo(1);
		assertThat(FAKE.chatModels(key)).containsExactly("chat-free:free");
	}

	// ---------------------------------------------------------------- P08 key-test rework (Samarth's bug report)

	@Test
	@DisplayName("T-08-10 wizard test without a model: first-ranked model retired (404) → next one works → VERIFIED, slots use it, never FAILING")
	void fallbackWhenFirstModelIsRetired() throws Exception {
		String key = newKey("AIzaWizard");
		UUID id = createConnection(alice, "GEMINI", key);
		FAKE.script(key, FakeProvider.geminiModels("embedding-gecko-001", "gemini-1.0-pro-vision-latest",
				"gemini-2.0-flash", "gemini-3-flash", "aqa"));
		FAKE.whenModel(key, "gemini-3-flash", FakeProvider.retired("gemini-3-flash"));

		var res = postJson(alice, "/api/v1/connections/" + id + "/test", "{}");

		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(200);
		assertThat(jsonOf(res).path("status").asString()).isEqualTo("VERIFIED");
		assertThat(jsonOf(res).path("sampleModel").asString()).isEqualTo("gemini-2.0-flash");
		assertThat(jsonOf(res).path("defaultSlotsSet").asBoolean()).isTrue();
		assertThat(FAKE.chatModels(key)).as("hint not listed → skipped; non-chat models never tried")
				.containsExactly("gemini-3-flash", "gemini-2.0-flash");
		assertThat(status(id)).isEqualTo("VERIFIED");
		JsonNode slots = jsonOf(perform(alice, get("/api/v1/slots")));
		assertThat(slots.path("PLAN").path(0).path("model").asString()).isEqualTo("gemini-2.0-flash");
	}

	@Test
	@DisplayName("T-08-11 Settings re-test (no model) checks the routed slot model first → stays VERIFIED every time; retired listed models are never tried")
	void retestUsesRoutedModel() throws Exception {
		String key = newKey("AIzaRetest");
		UUID id = createConnection(alice, "GEMINI", key);
		FAKE.script(key, FakeProvider.geminiModels("gemini-1.0-pro-vision-latest", "gemini-1.5-flash",
				"gemini-2.5-flash", "gemini-2.0-flash-001"));
		FAKE.whenModel(key, "gemini-1.0-pro-vision-latest", FakeProvider.retired("gemini-1.0-pro-vision-latest"));
		FAKE.whenModel(key, "gemini-1.5-flash", FakeProvider.retired("gemini-1.5-flash"));
		String test = "/api/v1/connections/" + id + "/test";

		var wizard = postJson(alice, test, "{}");
		assertThat(jsonOf(wizard).path("sampleModel").asString()).isEqualTo("gemini-2.5-flash");
		for (int i = 0; i < 3; i++) {
			var retest = postJson(alice, test, "{}");
			assertThat(retest.getResponse().getStatus()).as(body(retest)).isEqualTo(200);
			assertThat(jsonOf(retest).path("sampleModel").asString()).isEqualTo("gemini-2.5-flash");
		}
		assertThat(status(id)).isEqualTo("VERIFIED");
		assertThat(FAKE.chatModels(key)).hasSize(4).containsOnly("gemini-2.5-flash");

		// a model the user routed by hand is checked before the ranking's favourite
		var put = perform(alice, put("/api/v1/slots").contentType(MediaType.APPLICATION_JSON)
				.content("{\"PLAN\":[{\"connectionId\":\"" + id + "\",\"model\":\"gemini-2.0-flash-001\"}]}"));
		assertThat(put.getResponse().getStatus()).isEqualTo(200);
		assertThat(jsonOf(postJson(alice, test, "{}")).path("sampleModel").asString()).isEqualTo("gemini-2.0-flash-001");
	}

	@Test
	@DisplayName("T-08-25 routed model retired later → re-test still VERIFIED with the next model, and the note names the routed model")
	void retiredRoutedModelIsReported() throws Exception {
		String key = newKey("AIzaRouted");
		UUID id = createConnection(alice, "GEMINI", key);
		FAKE.script(key, FakeProvider.geminiModels("gemini-2.5-flash", "gemini-2.0-flash-001"));
		String test = "/api/v1/connections/" + id + "/test";
		assertThat(jsonOf(postJson(alice, test, "{}")).path("sampleModel").asString()).isEqualTo("gemini-2.5-flash");

		FAKE.whenModel(key, "gemini-2.5-flash", FakeProvider.retired("gemini-2.5-flash"));
		var retest = postJson(alice, test, "{}");
		assertThat(retest.getResponse().getStatus()).as(body(retest)).isEqualTo(200);
		JsonNode r = jsonOf(retest);
		assertThat(r.path("sampleModel").asString()).isEqualTo("gemini-2.0-flash-001");
		assertThat(r.path("note").asString()).contains("gemini-2.5-flash didn't answer").contains("Task routing");
		assertThat(status(id)).isEqualTo("VERIFIED");
	}

	@Test
	@DisplayName("T-08-37 Gemini 503 'model overloaded' on the first model → next model answers → VERIFIED, not 'couldn't reach'")
	void overloadedModelFallsBackToTheNext() throws Exception {
		String key = newKey("AIzaOverloaded");
		UUID id = createConnection(alice, "GEMINI", key);
		FAKE.script(key, FakeProvider.geminiModels("gemini-2.5-flash", "gemini-2.0-flash-001"));
		FAKE.whenModel(key, "gemini-2.5-flash", Reply.json(503,
				"{\"error\":{\"code\":503,\"message\":\"The model is overloaded. Please try again later.\",\"status\":\"UNAVAILABLE\"}}"));

		var r = postJson(alice, "/api/v1/connections/" + id + "/test", "{}");
		assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
		assertThat(jsonOf(r).path("sampleModel").asString()).isEqualTo("gemini-2.0-flash-001");
		assertThat(status(id)).isEqualTo("VERIFIED");
	}

	@Test
	@DisplayName("T-08-38 Samarth's real case: the key gets the model list but every model answers 503 → VERIFIED with a 'models overloaded' note, slots filled")
	void allOverloadedButListedIsVerified() throws Exception {
		String key = newKey("AIzaAllOverloaded");
		UUID id = createConnection(alice, "GEMINI", key);
		FAKE.script(key, FakeProvider.geminiModels("gemini-3.8-flash", "gemini-3.7-flash"));
		Reply overloaded = Reply.json(503, "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\"}}");
		FAKE.whenModel(key, "gemini-3.8-flash", overloaded);
		FAKE.whenModel(key, "gemini-3.7-flash", overloaded);

		var r = postJson(alice, "/api/v1/connections/" + id + "/test", "{}");
		assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
		JsonNode j = jsonOf(r);
		assertThat(j.path("status").asString()).isEqualTo("VERIFIED");
		assertThat(j.path("note").asString()).contains("Your key works").contains("overloaded");
		assertThat(j.path("defaultSlotsSet").asBoolean()).isTrue();
		assertThat(status(id)).isEqualTo("VERIFIED");
	}

	@Test
	@DisplayName("T-08-39 model list AND the known-good model both 503 → key not proven → 422 PROVIDER_UNAVAILABLE, not FAILING")
	void overloadedWithoutListIsProviderUnavailable() throws Exception {
		String key = newKey("AIzaNothingAnswers");
		UUID id = createConnection(alice, "GEMINI", key);
		Reply overloaded = Reply.json(503, "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\"}}");
		FAKE.script(key, overloaded); // GET models
		FAKE.whenModel(key, GEMINI_HINT, overloaded);

		var r = postJson(alice, "/api/v1/connections/" + id + "/test", "{}");
		assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(422);
		assertThat(jsonOf(r).path("reason").asString()).isEqualTo("PROVIDER_UNAVAILABLE");
		assertThat(status(id)).isNotEqualTo("FAILING");
	}

	@Test
	@DisplayName("T-08-34 routed model rate-limited (429) → next model answers, note says 'rate-limited right now', no re-route advice")
	void rateLimitedRoutedModelIsOnlyAHeadsUp() throws Exception {
		String key = newKey("AIzaRoutedLimited");
		UUID id = createConnection(alice, "GEMINI", key);
		FAKE.script(key, FakeProvider.geminiModels("gemini-2.5-flash", "gemini-2.0-flash-001"));
		String test = "/api/v1/connections/" + id + "/test";
		assertThat(jsonOf(postJson(alice, test, "{}")).path("sampleModel").asString()).isEqualTo("gemini-2.5-flash");

		FAKE.whenModel(key, "gemini-2.5-flash", FakeProvider.Reply.rateLimited(30));
		var retest = postJson(alice, test, "{}");
		assertThat(retest.getResponse().getStatus()).as(body(retest)).isEqualTo(200);
		JsonNode r = jsonOf(retest);
		assertThat(r.path("sampleModel").asString()).isEqualTo("gemini-2.0-flash-001");
		assertThat(r.path("note").asString())
				.isEqualTo("gemini-2.5-flash is rate-limited right now; gemini-2.0-flash-001 answered")
				.doesNotContain("Task routing");
		assertThat(status(id)).isEqualTo("VERIFIED");
	}

	@Test
	@DisplayName("T-08-26 model list rate-limited (429) → the preset hint is still tested → VERIFIED with a model and slots filled")
	void listRateLimitedStillTestsHint() throws Exception {
		String key = newKey("AIzaListLimited");
		UUID id = createConnection(alice, "GEMINI", key);
		FAKE.script(key, FakeProvider.Reply.rateLimited(30)); // the first call (GET models) is rate-limited
		var r = postJson(alice, "/api/v1/connections/" + id + "/test", "{}");
		assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
		assertThat(jsonOf(r).path("sampleModel").asString()).isEqualTo(GEMINI_HINT);
		assertThat(jsonOf(r).path("defaultSlotsSet").asBoolean()).isTrue();
		assertThat(status(id)).isEqualTo("VERIFIED");
	}

	@Test
	@DisplayName("T-08-12 explicit model not found → 422 reason MODEL_NOT_FOUND, no fallback, status unchanged")
	void explicitModelNotFound() throws Exception {
		String key = newKey("gsk_");
		UUID id = createConnection(alice, "GROQ", key);
		jdbc.sql("UPDATE ai_connection SET status = 'VERIFIED' WHERE id = :id").param("id", id).update();
		FAKE.whenModel(key, "llama-gone", Reply.json(404, "{\"error\":{\"message\":\"The model `llama-gone` does not "
				+ "exist or you do not have access to it.\",\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}"));

		var res = postJson(alice, "/api/v1/connections/" + id + "/test", "{\"model\":\"llama-gone\"}");

		assertThat(res.getResponse().getStatus()).isEqualTo(422);
		assertThat(jsonOf(res).path("reason").asString()).isEqualTo("MODEL_NOT_FOUND");
		assertThat(jsonOf(res).path("detail").asString()).isEqualTo("Model not found");
		assertThat(FAKE.chatModels(key)).containsExactly("llama-gone");
		assertThat(status(id)).as("a missing model is not a broken key").isEqualTo("VERIFIED");
	}

	@Test
	@DisplayName("T-08-13 Gemini wrong key (HTTP 400 API_KEY_INVALID) → 422 reason KEY_INVALID 'Key invalid', FAILING, body never forwarded")
	void geminiInvalidKey() throws Exception {
		String key = newKey("AIzaWrong");
		UUID id = createConnection(alice, "GEMINI", key);
		String google = "[{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key. " + key
				+ "\",\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}]";
		FAKE.script(key, Reply.json(400, google), Reply.json(400, google));
		String test = "/api/v1/connections/" + id + "/test";

		var auto = postJson(alice, test, "{}"); // fails on the model list
		var explicit = postJson(alice, test, "{\"model\":\"gemini-2.5-flash\"}"); // fails on the chat call

		for (MvcResult r : List.of(auto, explicit)) {
			assertThat(r.getResponse().getStatus()).isEqualTo(422);
			assertThat(jsonOf(r).path("reason").asString()).isEqualTo("KEY_INVALID");
			assertThat(jsonOf(r).path("detail").asString()).isEqualTo("Key invalid");
			assertThat(body(r)).doesNotContain(key).doesNotContain("API key not valid");
		}
		assertThat(status(id)).isEqualTo("FAILING");
	}

	@Test
	@DisplayName("T-08-14 2xx without any text (thinking model) → VERIFIED; the test request sends no max_tokens / temperature")
	void emptyReplyStillVerifies() throws Exception {
		String key = newKey("AIzaThink");
		UUID id = createConnection(alice, "GEMINI", key);
		FAKE.script(key, Reply.json(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\"},\"finish_reason\":\"length\"}],"
				+ "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":0}}"));

		var res = postJson(alice, "/api/v1/connections/" + id + "/test", "{\"model\":\"gemini-2.5-flash\"}");

		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(200);
		assertThat(jsonOf(res).path("status").asString()).isEqualTo("VERIFIED");
		String sent = FAKE.requestsWithKey(key).getFirst().body();
		assertThat(sent).doesNotContain("max_tokens").doesNotContain("temperature");
	}

	@Test
	@DisplayName("T-08-15 a 400 that only mentions a model ('not supported with this model') → reason PROVIDER_ERROR, no fallback, status unchanged")
	void unrelated400IsNotModelNotFound() throws Exception {
		String key = newKey("AIzaParam");
		UUID id = createConnection(alice, "GEMINI", key);
		FAKE.script(key, FakeProvider.geminiModels("gemini-2.5-flash", "gemini-2.0-flash"));
		FAKE.whenModel(key, "gemini-2.5-flash", Reply.json(400, "{\"error\":{\"message\":\"Unsupported parameter: "
				+ "'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.\","
				+ "\"type\":\"invalid_request_error\",\"code\":\"unsupported_parameter\"}}"));

		var res = postJson(alice, "/api/v1/connections/" + id + "/test", "{}");

		assertThat(res.getResponse().getStatus()).isEqualTo(422);
		assertThat(jsonOf(res).path("reason").asString()).isEqualTo("PROVIDER_ERROR");
		assertThat(body(res)).doesNotContain("max_completion_tokens");
		assertThat(FAKE.chatModels(key)).containsExactly("gemini-2.5-flash");
		assertThat(status(id)).isEqualTo("UNVERIFIED");
	}

	@Test
	@DisplayName("T-08-16 no candidate works → 422 reason NO_CHAT_MODEL after at most 5 tries (listed hint first); status unchanged")
	void noUsableModel() throws Exception {
		String key = newKey("AIzaNothing");
		UUID id = createConnection(alice, "GEMINI", key);
		List<String> all = List.of(GEMINI_HINT, "gemini-3-flash", "gemini-2.5-flash", "gemini-2.0-flash",
				"gemini-1.5-flash", "gemini-1.0-flash");
		FAKE.script(key, FakeProvider.geminiModels(all.reversed().toArray(String[]::new)));
		for (String m : all) {
			FAKE.whenModel(key, m, FakeProvider.retired(m));
		}

		var res = postJson(alice, "/api/v1/connections/" + id + "/test", "{}");

		assertThat(res.getResponse().getStatus()).isEqualTo(422);
		assertThat(jsonOf(res).path("reason").asString()).isEqualTo("NO_CHAT_MODEL");
		// anveshan.models.test-candidates = 5: the 6th (oldest) model is never tried
		assertThat(FAKE.chatModels(key)).containsExactlyElementsOf(all.subList(0, 5));
		assertThat(status(id)).isEqualTo("UNVERIFIED");
		var models = jsonOf(perform(alice, get("/api/v1/connections/" + id + "/models")));
		assertThat(models.path("items").size()).as("the picker can still offer the live list").isEqualTo(6);
	}

	@Test
	@DisplayName("T-08-17 first candidate rate-limited (e.g. free tier limit 0) → next candidate works → VERIFIED with it, no note")
	void rateLimitedCandidateIsSkipped() throws Exception {
		String key = newKey("AIzaQuota");
		UUID id = createConnection(alice, "GEMINI", key);
		FAKE.script(key, FakeProvider.geminiModels("gemini-3-flash", "gemini-2.5-flash"));
		FAKE.whenModel(key, "gemini-3-flash", Reply.rateLimited(30));

		var res = postJson(alice, "/api/v1/connections/" + id + "/test", "{}");

		assertThat(jsonOf(res).path("status").asString()).isEqualTo("VERIFIED");
		assertThat(jsonOf(res).path("sampleModel").asString()).isEqualTo("gemini-2.5-flash");
		assertThat(jsonOf(res).path("note").isNull() || jsonOf(res).path("note").isMissingNode()).isTrue();
		assertThat(status(id)).isEqualTo("VERIFIED");
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
	@DisplayName("T-10-15 every attempt of a research call is logged with its run and step (429 on route 1, OK on route 2)")
	void callContextLogged() throws Exception {
		String k1 = newKey("gsk_");
		String k2 = newKey("gsk_");
		UUID c1 = createConnection(alice, "GROQ", k1);
		UUID c2 = createConnection(alice, "GROQ", k2);
		route(alice, TaskSlot.PLAN, c1, c2);
		FAKE.script(k1, Reply.rateLimited(30));
		FAKE.script(k2, Reply.chat("{\"answer\":\"4\",\"score\":9}"));
		UUID run = UUID.randomUUID();
		UUID step = UUID.randomUUID();

		gateway.call(alice, TaskSlot.PLAN, prompt(), Answer.class, dev.anveshan.byok.api.CallContext.step(run, step));

		assertThat(jdbc.sql("SELECT count(*) FROM llm_call WHERE owner_id = :o AND run_id = :r AND step_id = :s")
				.param("o", alice).param("r", run).param("s", step).query(Long.class).single())
				.as("both attempts carry the run").isEqualTo(2);
		// a call outside a run keeps them empty
		FAKE.script(k2, Reply.chat("{\"answer\":\"5\",\"score\":1}"));
		gateway.call(alice, TaskSlot.PLAN, prompt(), Answer.class);
		assertThat(jdbc.sql("SELECT count(*) FROM llm_call WHERE owner_id = :o AND run_id IS NULL").param("o", alice)
				.query(Long.class).single()).isEqualTo(1);
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
