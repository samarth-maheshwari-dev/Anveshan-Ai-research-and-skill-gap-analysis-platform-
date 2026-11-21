package dev.anveshan.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import dev.anveshan.identity.infra.security.SessionUser;
import dev.anveshan.llm.FakeGateway.Reply;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The real gateway chain end to end: research start, daily allowance, status, pause when every endpoint fails. */
@SpringBootTest(properties = { "management.server.port=0", "anveshan.llm.daily-runs-per-user=2" })
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class GatewayIT {

	static final FakeGateway FAKE = new FakeGateway();
	static final String TOPIC = "{\"topic\":\"Compare lost-and-found apps for college campuses\",\"depth\":\"QUICK\"}";

	@DynamicPropertySource
	static void endpoints(DynamicPropertyRegistry r) {
		r.add("anveshan.llm.endpoints[0].name", () -> "freellmapi");
		r.add("anveshan.llm.endpoints[0].base-url", () -> FAKE.baseUrl("freellmapi"));
		r.add("anveshan.llm.endpoints[0].api-key", () -> "free-key");
		r.add("anveshan.llm.endpoints[0].model", () -> "auto");
	}

	@AfterAll
	static void stop() {
		FAKE.close();
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	UUID user;

	@BeforeEach
	void setUp() {
		FAKE.reset();
		FAKE.reply("freellmapi", Reply.rateLimited(3600)); // nothing answers: runs pause instead of finishing
		user = jdbc.sql("INSERT INTO app_user (email, email_verified) VALUES (:e, true) RETURNING id")
				.param("e", "gw-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
	}

	@Test
	@DisplayName("T-16-07 every endpoint rate-limited → planning waits for the AI (AI_LIMIT_REACHED log with resumeAt), nothing fails")
	void allEndpointsFailPausesRun() throws Exception {
		var res = perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content(TOPIC));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(202);
		UUID run = UUID.fromString(json.readTree(body(res)).path("id").asString());

		// PLANNING has no PAUSED state (PRD §8.7): the engine logs the limit and re-queues PLAN at resumeAt
		await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(jdbc
				.sql("SELECT payload->>'resumeAt' FROM run_event WHERE run_id = :r AND payload->>'code' = 'AI_LIMIT_REACHED'")
				.param("r", run).query(String.class).list()).isNotEmpty());
		assertThat(jdbc.sql("SELECT status FROM research_run WHERE id = :r").param("r", run).query(String.class).single())
				.isEqualTo("PLANNING");
		assertThat(jdbc.sql("SELECT status FROM research_step WHERE run_id = :r AND kind = 'PLAN'").param("r", run)
				.query(String.class).single()).isNotEqualTo("FAILED");
	}

	@Test
	@DisplayName("T-16-08 daily limit: the 3rd research start of the day → 429 LLM_DAILY_LIMIT, no run created; status counts the used runs")
	void dailyLimit() throws Exception {
		JsonNode before = json.readTree(body(perform(get("/api/v1/ai/status"))));
		assertThat(before.path("runsUsedToday").asInt()).isZero();
		assertThat(before.path("runsLimit").asInt()).isEqualTo(2);

		for (int i = 0; i < 2; i++) {
			assertThat(perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content(TOPIC)).getResponse()
					.getStatus()).isEqualTo(202);
		}
		var third = perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content(TOPIC));
		assertThat(third.getResponse().getStatus()).as(body(third)).isEqualTo(429);
		JsonNode problem = json.readTree(body(third));
		assertThat(problem.path("code").asString()).isEqualTo("LLM_DAILY_LIMIT");
		assertThat(problem.path("limit").asInt()).isEqualTo(2);
		assertThat(jdbc.sql("SELECT count(*) FROM research_run WHERE owner_id = :o").param("o", user)
				.query(Long.class).single()).isEqualTo(2);
		assertThat(json.readTree(body(perform(get("/api/v1/ai/status")))).path("runsUsedToday").asInt()).isEqualTo(2);
	}

	@Test
	@DisplayName("T-16-09 GET /api/v1/ai/status: signed-in only; ONLINE with a configured endpoint, BUSY once its breaker is open")
	void status() throws Exception {
		assertThat(mvc.perform(get("/api/v1/ai/status")).andReturn().getResponse().getStatus()).isEqualTo(401);
		// a transport-level answer (even an unusable one) closes the breaker -> ONLINE
		FAKE.reply("freellmapi", Reply.chat("not json"));
		var ok = perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content(TOPIC));
		UUID run = UUID.fromString(json.readTree(body(ok)).path("id").asString());
		await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(jdbc
				.sql("SELECT count(*) FROM llm_call WHERE run_id = :r AND status = 'INVALID_OUTPUT' AND endpoint = 'freellmapi'").param("r", run)
				.query(Long.class).single()).isPositive());
		assertThat(json.readTree(body(perform(get("/api/v1/ai/status")))).path("state").asString()).isEqualTo("ONLINE");

		FAKE.reply("freellmapi", Reply.rateLimited(3600));
		user = jdbc.sql("INSERT INTO app_user (email, email_verified) VALUES (:e, true) RETURNING id")
				.param("e", "gw-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
		for (int i = 0; i < 3; i++) { // 3 failing runs' PLAN calls open the breaker
			perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content(TOPIC));
			user = jdbc.sql("INSERT INTO app_user (email, email_verified) VALUES (:e, true) RETURNING id")
					.param("e", "gw-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
		}
		await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(
				json.readTree(body(perform(get("/api/v1/ai/status")))).path("state").asString()).isEqualTo("BUSY"));
	}

	MvcResult perform(MockHttpServletRequestBuilder req) throws Exception {
		var token = new OAuth2AuthenticationToken(new SessionUser(user), List.of(new SimpleGrantedAuthority("ROLE_USER")),
				"google");
		return mvc.perform(req.with(authentication(token)).with(csrf())).andReturn();
	}

	static String body(MvcResult r) {
		return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

}
