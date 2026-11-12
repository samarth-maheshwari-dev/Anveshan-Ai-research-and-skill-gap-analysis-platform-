package dev.anveshan.research.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.anveshan.TestcontainersConfig;
import dev.anveshan.byok.api.LlmGateway;
import dev.anveshan.byok.api.LlmResult;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.identity.infra.security.SessionUser;
import dev.anveshan.research.app.Planner.PlanItem;
import dev.anveshan.research.app.Planner.PlanOutput;
import dev.anveshan.research.domain.ResearchEnums.RunStatus;
import dev.anveshan.sources.FakeSearchApis;
import dev.anveshan.sources.infra.SearchResilience;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * P11: after approval, one real SEARCH step per sub-question against the search APIs (a local fake), with the
 * planner's LLM stubbed. FETCH (P12) has no handler yet, so the run then stops — these tests look at SEARCH only.
 */
@SpringBootTest(properties = { "management.server.port=0", "anveshan.research.retry-backoff=100ms" })
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SearchStepIT {

	static final Duration WAIT = Duration.ofSeconds(90);
	static final FakeSearchApis FAKE = new FakeSearchApis();

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		FAKE.register(r);
	}

	@MockitoBean
	LlmGateway gateway;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	@Autowired
	SearchResilience resilience;

	UUID user;

	@BeforeEach
	void setUp() {
		FAKE.reset();
		resilience.reset();
		user = jdbc.sql("INSERT INTO app_user (email, email_verified) VALUES (:e, true) RETURNING id")
				.param("e", "reena-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
		UUID llm = connection(user, "GROQ", "LLM");
		for (String slot : List.of("PLAN", "WRITE")) {
			jdbc.sql("INSERT INTO slot_route (owner_id, slot, position, connection_id, model) VALUES (:o, :s, 0, :c, 'm')")
					.param("o", user).param("s", slot).param("c", llm).update();
		}
		// two sub-questions: one academic + web, one GitHub
		List<PlanItem> plan = List.of(
				new PlanItem("Which lost and found apps work best on college campuses?", List.of("ACADEMIC", "WEB"),
						List.of("campus lost and found app", "lost item recovery university")),
				new PlanItem("Which open-source lost and found projects exist?", List.of("GITHUB"),
						List.of("lost and found app")));
		when(gateway.call(any(), eq(TaskSlot.PLAN), any(), eq(PlanOutput.class), any()))
				.thenAnswer(inv -> new LlmResult<>(new PlanOutput(plan), UUID.randomUUID(), "fake", 10, 10, 1));
	}

	@Test
	@DisplayName("T-11-05 Semantic Scholar returns 503, others OK → SEARCH DONE with partial results + a log event")
	void oneApiDown() throws Exception {
		FAKE.fail("semanticscholar", 503);
		UUID run = approvedRun();
		List<JsonNode> outputs = awaitSearch(run, "DONE");

		JsonNode academic = outputs.stream().filter(o -> o.path("adapters").toString().contains("openalex")).findFirst()
				.orElseThrow();
		assertThat(academic.path("candidates").size()).isPositive();
		assertThat(academic.path("adapters").toString()).contains("\"semanticscholar\"", "\"FAILED\"");
		assertThat(FAKE.requestsTo("semanticscholar")).as("retried before giving up").hasSizeGreaterThanOrEqualTo(3);
		assertThat(logMessages(run)).anyMatch(m -> m.equals("Semantic Scholar didn't answer — continuing with the other sources."));
		assertThat(logMessages(run)).anyMatch(m -> m.startsWith("Found "));
		JsonNode code = outputs.stream().filter(o -> o.path("adapters").toString().contains("github")).findFirst()
				.orElseThrow();
		assertThat(code.path("candidates").get(0).path("url").asString()).isEqualTo("https://github.com/college/lost-and-found");
	}

	@Test
	@DisplayName("T-11-06 every API fails → SEARCH retried, then FAILED with a plain, retryable message")
	void allApisDown() throws Exception {
		for (String api : FakeSearchApis.APIS) {
			FAKE.fail(api, 503);
		}
		UUID run = approvedRun();
		await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> assertThat(jdbc.sql(
				"SELECT count(*) FROM research_step WHERE run_id = :r AND kind = 'SEARCH' AND status = 'FAILED'")
				.param("r", run).query(Long.class).single()).isPositive());
		var failed = jdbc.sql("SELECT attempts, last_error FROM research_step WHERE run_id = :r AND kind = 'SEARCH' AND status = 'FAILED' LIMIT 1")
				.param("r", run).query((rs, n) -> new Object[] { rs.getInt(1), rs.getString(2) }).single();
		assertThat((int) failed[0]).as("retryable: tried 3 times").isEqualTo(3);
		assertThat((String) failed[1]).isEqualTo("None of the search services answered. Anveshan will try again shortly.");
		assertThat(status(run)).isEqualTo(RunStatus.FAILED);
	}

	@Test
	@DisplayName("T-11-07 no Tavily key → Tavily never called; with a verified key → called for web sub-questions, key never in output")
	void tavilyOnlyWithKey() throws Exception {
		UUID run = approvedRun();
		awaitSearch(run, "DONE");
		assertThat(FAKE.requestsTo("tavily")).isEmpty();

		// the same user adds a Tavily key (tested against the fake) → the next run uses it
		String key = "tvly-" + UUID.randomUUID().toString().replace("-", "");
		var created = perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON).content(
				"{\"kind\":\"SEARCH\",\"provider\":\"TAVILY\",\"label\":\"My Tavily\",\"apiKey\":\"" + key + "\"}"));
		assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
		String id = json.readTree(body(created)).path("id").asString();
		var tested = perform(post("/api/v1/connections/" + id + "/test").contentType(MediaType.APPLICATION_JSON).content("{}"));
		assertThat(tested.getResponse().getStatus()).as(body(tested)).isEqualTo(200);
		FAKE.reset();

		UUID second = approvedRun();
		List<JsonNode> outputs = awaitSearch(second, "DONE");
		assertThat(FAKE.requestsTo("tavily")).as("only the ACADEMIC+WEB sub-question uses web search").hasSize(2);
		assertThat(FAKE.requestsTo("tavily").get(0).headers().get("Authorization")).containsExactly("Bearer " + key);
		assertThat(outputs.toString()).contains("https://university.example/lost-found").doesNotContain(key);
	}

	// --- helpers ---

	UUID approvedRun() throws Exception {
		var res = perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
				.content("{\"topic\":\"Compare lost-and-found apps for college campuses\",\"depth\":\"QUICK\"}"));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(202);
		UUID run = UUID.fromString(json.readTree(body(res)).path("id").asString());
		await().atMost(WAIT).pollInterval(Duration.ofMillis(100))
				.untilAsserted(() -> assertThat(status(run)).isEqualTo(RunStatus.AWAITING_APPROVAL));
		String etag = perform(get("/api/v1/runs/" + run)).getResponse().getHeader("ETag");
		assertThat(perform(post("/api/v1/runs/" + run + "/approve").header("If-Match", etag)).getResponse().getStatus())
				.isEqualTo(202);
		return run;
	}

	/** Waits until every SEARCH step has {@code status}; returns their outputs. */
	List<JsonNode> awaitSearch(UUID run, String status) {
		await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
			List<String> s = jdbc.sql("SELECT status FROM research_step WHERE run_id = :r AND kind = 'SEARCH'")
					.param("r", run).query(String.class).list();
			assertThat(s).hasSize(2).allMatch(status::equals);
		});
		return jdbc.sql("SELECT output::text FROM research_step WHERE run_id = :r AND kind = 'SEARCH' ORDER BY seq")
				.param("r", run).query(String.class).list().stream().map(json::readTree).toList();
	}

	List<String> logMessages(UUID run) {
		return jdbc.sql("SELECT payload->>'message' FROM run_event WHERE run_id = :r AND type = 'log'").param("r", run)
				.query(String.class).list();
	}

	RunStatus status(UUID run) {
		return RunStatus.valueOf(jdbc.sql("SELECT status FROM research_run WHERE id = :id").param("id", run)
				.query(String.class).single());
	}

	UUID connection(UUID owner, String provider, String kind) {
		UUID id = jdbc.sql("""
				INSERT INTO ai_connection (owner_id, kind, provider, label, base_url, key_ciphertext, key_last4, status)
				VALUES (:o, :k, :p, 'Test', 'https://example.test/', :c, 'abcd', 'VERIFIED') RETURNING id""")
				.param("o", owner).param("k", kind).param("p", provider).param("c", new byte[] { 1 }).query(UUID.class)
				.single();
		return id;
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
