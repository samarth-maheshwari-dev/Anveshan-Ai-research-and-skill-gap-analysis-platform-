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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.anveshan.TestcontainersConfig;
import dev.anveshan.byok.api.LlmGateway;
import dev.anveshan.byok.api.LlmResult;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.identity.infra.security.SessionUser;
import dev.anveshan.research.api.PlanReady;
import dev.anveshan.research.app.PlanHandler.PlanItem;
import dev.anveshan.research.app.PlanHandler.PlanOutput;
import dev.anveshan.research.domain.ResearchEnums.RunStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * P10: the real Planner with a fake LLM (the gateway is stubbed — no provider is ever called), then Plan Review
 * over HTTP: edit with If-Match, approve, and the C-FR-3 start check.
 */
@SpringBootTest(properties = { "management.server.port=0", "anveshan.research.retry-backoff=100ms" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfig.class, PlanApiIT.Events.class })
class PlanApiIT {

	static final Duration WAIT = Duration.ofSeconds(60);

	@TestConfiguration(proxyBeanMethods = false)
	static class Events {

		@Bean
		PlanReadyLog planReadyLog() {
			return new PlanReadyLog();
		}

	}

	static class PlanReadyLog {

		final List<PlanReady> seen = new CopyOnWriteArrayList<>();

		@EventListener
		void on(PlanReady e) {
			seen.add(e);
		}

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
	PlanReadyLog planReady;

	UUID alice;

	UUID bob;

	@BeforeEach
	void setUp() {
		alice = newUser("alice");
		bob = newUser("bob");
		routeSlots(alice, "PLAN", "WRITE");
		routeSlots(bob, "PLAN", "WRITE");
		answerPlan(5);
	}

	/** The fake model answers with {@code n} sub-questions (plus junk the code must clean up). */
	void answerPlan(int n) {
		List<PlanItem> items = new ArrayList<>(IntStream.rangeClosed(1, n)
				.mapToObj(i -> new PlanItem("  Which campus apps solve problem " + i + "?  ", List.of("WEB", "youtube"),
						List.of("campus lost and found " + i)))
				.toList());
		when(gateway.call(any(), eq(TaskSlot.PLAN), any(), eq(PlanOutput.class)))
				.thenAnswer(inv -> new LlmResult<>(new PlanOutput(items), UUID.randomUUID(), "fake-model", 100, 50, 1));
	}

	@Test
	@DisplayName("T-10-02 PLAN with a fake LLM → 3–8 sub-questions saved, AWAITING_APPROVAL, PlanReady published")
	void planWithFakeLlm() throws Exception {
		answerPlan(11); // more than allowed: the code keeps the first 8
		UUID run = startRun(alice);
		awaitStatus(run, RunStatus.AWAITING_APPROVAL);

		JsonNode view = jsonOf(perform(alice, get("/api/v1/runs/" + run)));
		JsonNode subs = view.path("subQuestions");
		assertThat(subs.size()).isBetween(3, 8).isEqualTo(8);
		assertThat(subs.get(0).path("text").asString()).isEqualTo("Which campus apps solve problem 1?");
		assertThat(subs.get(0).path("sourceTypes").size()).as("unknown source type dropped").isEqualTo(1);
		assertThat(view.path("estimate").path("requests").path("PLAN").asInt()).isEqualTo(1);
		assertThat(view.path("estimate").path("totalRequests").asInt()).isPositive();
		await().atMost(WAIT).untilAsserted(() -> assertThat(planReady.seen)
				.anySatisfy(e -> assertThat(e.runId()).isEqualTo(run)));
		assertThat(planReady.seen.stream().filter(e -> e.runId().equals(run)).findFirst().orElseThrow().subQuestions())
				.isEqualTo(8);
	}

	@Test
	@DisplayName("T-10-03 PUT /plan with a stale If-Match → 409 VERSION_CONFLICT with currentVersion; a fresh one → 200")
	void staleVersion() throws Exception {
		UUID run = startRun(alice);
		awaitStatus(run, RunStatus.AWAITING_APPROVAL);
		MvcResult got = perform(alice, get("/api/v1/runs/" + run));
		String etag = got.getResponse().getHeader("ETag");
		JsonNode subs = jsonOf(got).path("subQuestions");
		String edit = planBody(subs, 2);

		var first = perform(alice, put("/api/v1/runs/" + run + "/plan").header("If-Match", etag)
				.contentType(MediaType.APPLICATION_JSON).content(edit));
		assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(200);
		assertThat(jsonOf(first).path("subQuestions").size()).isEqualTo(2);
		assertThat(jsonOf(first).path("estimate").path("requests").path("PLAN").asInt()).isEqualTo(1);
		String newEtag = first.getResponse().getHeader("ETag");
		assertThat(newEtag).isNotEqualTo(etag);

		var stale = perform(alice, put("/api/v1/runs/" + run + "/plan").header("If-Match", etag)
				.contentType(MediaType.APPLICATION_JSON).content(edit));
		assertThat(stale.getResponse().getStatus()).isEqualTo(409);
		assertThat(jsonOf(stale).path("code").asString()).isEqualTo("VERSION_CONFLICT");
		assertThat("\"" + jsonOf(stale).path("currentVersion").asInt() + "\"").isEqualTo(newEtag);

		var noHeader = perform(alice, put("/api/v1/runs/" + run + "/plan").contentType(MediaType.APPLICATION_JSON)
				.content(edit));
		assertThat(noHeader.getResponse().getStatus()).isEqualTo(400);

		// another user's run is unknown, not forbidden
		var bobs = perform(bob, put("/api/v1/runs/" + run + "/plan").header("If-Match", newEtag)
				.contentType(MediaType.APPLICATION_JSON).content(edit));
		assertThat(bobs.getResponse().getStatus()).isEqualTo(404);
		assertThat(perform(bob, post("/api/v1/runs/" + run + "/approve").header("If-Match", newEtag)).getResponse()
				.getStatus()).isEqualTo(404);
	}

	@Test
	@DisplayName("T-10-04 approve → 202 RUNNING with SEARCH steps; editing or approving again → 409 RUN_INVALID_STATE")
	void editAfterApprove() throws Exception {
		UUID run = startRun(alice);
		awaitStatus(run, RunStatus.AWAITING_APPROVAL);
		MvcResult got = perform(alice, get("/api/v1/runs/" + run));
		String etag = got.getResponse().getHeader("ETag");
		JsonNode subs = jsonOf(got).path("subQuestions");

		var stale = perform(alice, post("/api/v1/runs/" + run + "/approve").header("If-Match", "\"999\""));
		assertThat(stale.getResponse().getStatus()).isEqualTo(409);
		assertThat(jsonOf(stale).path("code").asString()).isEqualTo("VERSION_CONFLICT");

		var ok = perform(alice, post("/api/v1/runs/" + run + "/approve").header("If-Match", etag));
		assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(202);
		assertThat(status(run)).isNotEqualTo(RunStatus.AWAITING_APPROVAL);
		assertThat(jdbc.sql("SELECT count(*) FROM research_step WHERE run_id = :r AND kind = 'SEARCH'").param("r", run)
				.query(Long.class).single()).as("one SEARCH step per sub-question").isEqualTo(subs.size());

		String now = perform(alice, get("/api/v1/runs/" + run)).getResponse().getHeader("ETag");
		var edit = perform(alice, put("/api/v1/runs/" + run + "/plan").header("If-Match", now)
				.contentType(MediaType.APPLICATION_JSON).content(planBody(subs, 1)));
		assertThat(edit.getResponse().getStatus()).isEqualTo(409);
		assertThat(jsonOf(edit).path("code").asString()).isEqualTo("RUN_INVALID_STATE");
		var again = perform(alice, post("/api/v1/runs/" + run + "/approve").header("If-Match", now));
		assertThat(again.getResponse().getStatus()).isEqualTo(409);
		assertThat(jsonOf(again).path("code").asString()).isEqualTo("RUN_INVALID_STATE");
	}

	@Test
	@DisplayName("T-10-05 PUT /plan with 0 or 9 sub-questions → 400; the plan is unchanged")
	void wrongCount() throws Exception {
		UUID run = startRun(alice);
		awaitStatus(run, RunStatus.AWAITING_APPROVAL);
		MvcResult got = perform(alice, get("/api/v1/runs/" + run));
		String etag = got.getResponse().getHeader("ETag");
		int before = jsonOf(got).path("subQuestions").size();

		var none = perform(alice, put("/api/v1/runs/" + run + "/plan").header("If-Match", etag)
				.contentType(MediaType.APPLICATION_JSON).content("{\"subQuestions\":[]}"));
		assertThat(none.getResponse().getStatus()).as(body(none)).isEqualTo(400);

		String nine = "{\"subQuestions\":[" + String.join(",", IntStream.rangeClosed(1, 9)
				.mapToObj(i -> "{\"text\":\"New question number " + i + "\"}").toList()) + "]}";
		var tooMany = perform(alice, put("/api/v1/runs/" + run + "/plan").header("If-Match", etag)
				.contentType(MediaType.APPLICATION_JSON).content(nine));
		assertThat(tooMany.getResponse().getStatus()).as(body(tooMany)).isEqualTo(400);

		MvcResult after = perform(alice, get("/api/v1/runs/" + run));
		assertThat(jsonOf(after).path("subQuestions").size()).isEqualTo(before);
		assertThat(after.getResponse().getHeader("ETag")).isEqualTo(etag);
	}

	@Test
	@DisplayName("T-10-06 POST /runs without a PLAN or WRITE model → 422 NO_MODEL_FOR_SLOT, no run created")
	void noModelForSlot() throws Exception {
		UUID carol = newUser("carol");
		var none = perform(carol, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content(TOPIC));
		assertThat(none.getResponse().getStatus()).as(body(none)).isEqualTo(422);
		assertThat(jsonOf(none).path("code").asString()).isEqualTo("NO_MODEL_FOR_SLOT");
		assertThat(jsonOf(none).path("slot").asString()).isEqualTo("PLAN");

		routeSlots(carol, "PLAN");
		var noWrite = perform(carol, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content(TOPIC));
		assertThat(noWrite.getResponse().getStatus()).isEqualTo(422);
		assertThat(jsonOf(noWrite).path("slot").asString()).isEqualTo("WRITE");
		assertThat(jdbc.sql("SELECT count(*) FROM research_run WHERE owner_id = :o").param("o", carol)
				.query(Long.class).single()).isZero();

		routeSlots(carol, "WRITE");
		assertThat(perform(carol, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content(TOPIC))
				.getResponse().getStatus()).isEqualTo(202);
	}

	@Test
	@DisplayName("T-10-02 GET /templates is public and lists the four seeded templates")
	void templates() throws Exception {
		var res = mvc.perform(get("/api/v1/templates")).andReturn();
		assertThat(res.getResponse().getStatus()).isEqualTo(200);
		JsonNode items = jsonOf(res).path("items");
		assertThat(items.size()).isEqualTo(4);
		assertThat(items.get(0).path("sections").size()).isPositive();
	}

	// --- helpers ---

	static final String TOPIC = "{\"topic\":\"Compare lost-and-found apps for college campuses\",\"depth\":\"STANDARD\"}";

	/** The first {@code keep} cards, edited text kept, ids kept. */
	static String planBody(JsonNode subs, int keep) {
		List<String> cards = new ArrayList<>();
		for (int i = 0; i < keep; i++) {
			cards.add("{\"id\":\"" + subs.get(i).path("id").asString() + "\",\"text\":\"Edited question " + (i + 1)
					+ "\",\"sourceTypes\":[\"WEB\"]}");
		}
		return "{\"subQuestions\":[" + String.join(",", cards) + "]}";
	}

	UUID startRun(UUID user) throws Exception {
		var res = perform(user, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content(TOPIC));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(202);
		return UUID.fromString(jsonOf(res).path("id").asString());
	}

	UUID newUser(String name) {
		return jdbc.sql("INSERT INTO app_user (email, email_verified) VALUES (:e, true) RETURNING id")
				.param("e", name + "-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
	}

	void routeSlots(UUID user, String... slots) {
		UUID conn = jdbc.sql("""
				INSERT INTO ai_connection (owner_id, kind, provider, label, base_url, key_ciphertext, key_last4, status)
				VALUES (:o, 'LLM', 'GROQ', 'Test Groq', 'https://api.groq.com/openai/v1/', :k, 'abcd', 'VERIFIED')
				RETURNING id""").param("o", user).param("k", new byte[] { 1, 2, 3 }).query(UUID.class).single();
		for (String slot : slots) {
			jdbc.sql("INSERT INTO slot_route (owner_id, slot, position, connection_id, model) VALUES (:o, :s, 0, :c, 'm')")
					.param("o", user).param("s", slot).param("c", conn).update();
		}
	}

	MockHttpServletRequestBuilder as(UUID user, MockHttpServletRequestBuilder req) {
		var token = new OAuth2AuthenticationToken(new SessionUser(user), List.of(new SimpleGrantedAuthority("ROLE_USER")),
				"google");
		return req.with(authentication(token)).with(csrf());
	}

	MvcResult perform(UUID user, MockHttpServletRequestBuilder req) throws Exception {
		return mvc.perform(as(user, req)).andReturn();
	}

	static String body(MvcResult r) {
		return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

	JsonNode jsonOf(MvcResult r) {
		return json.readTree(body(r));
	}

	RunStatus status(UUID run) {
		return RunStatus.valueOf(jdbc.sql("SELECT status FROM research_run WHERE id = :id").param("id", run)
				.query(String.class).single());
	}

	void awaitStatus(UUID run, RunStatus expected) {
		await().atMost(WAIT).pollInterval(Duration.ofMillis(100))
				.untilAsserted(() -> assertThat(status(run)).as("run status").isEqualTo(expected));
	}

}
