package dev.anveshan.research.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.anveshan.TestcontainersConfig;
import dev.anveshan.identity.infra.security.SessionUser;
import dev.anveshan.research.domain.ResearchEnums.RunStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared context for the run-engine tests (same config → Spring reuses one context for all subclasses). */
@SpringBootTest(properties = { "management.server.port=0", "anveshan.research.retry-backoff=100ms",
		"anveshan.research.sweep-interval=1s",
		// tests trigger the periodic re-queue themselves; on its own it must not touch rows a test is setting up
		"anveshan.research.requeue-interval=1h" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfig.class, FakeSteps.Config.class })
abstract class ResearchITBase {

	static final Duration WAIT = Duration.ofSeconds(60);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	@Autowired
	FakeSteps fake;

	@Autowired
	RunEngine engine;

	@Autowired
	StepWorker worker;

	@Autowired
	RunSweeper sweeper;

	UUID alice;

	UUID bob;

	@BeforeEach
	void setUp() {
		fake.reset();
		alice = newUser("alice");
		bob = newUser("bob");
	}

	UUID newUser(String name) {
		return jdbc.sql("INSERT INTO app_user (email, email_verified) VALUES (:e, true) RETURNING id")
				.param("e", name + "-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
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

	/** POST /runs as {@code user}; returns the new run's id. */
	UUID startRun(UUID user) throws Exception {
		var res = perform(user, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
				.content("{\"topic\":\"Compare lost-and-found apps for college campuses\",\"depth\":\"STANDARD\"}"));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(202);
		return UUID.fromString(jsonOf(res).path("id").asString());
	}

	/** Starts a run, lets the fake PLAN finish, approves it (P10's endpoint is not built yet). */
	UUID startApprovedRun(UUID user) throws Exception {
		UUID run = startRun(user);
		awaitStatus(run, RunStatus.AWAITING_APPROVAL);
		engine.approve(run);
		return run;
	}

	RunStatus status(UUID run) {
		return RunStatus.valueOf(jdbc.sql("SELECT status FROM research_run WHERE id = :id").param("id", run)
				.query(String.class).single());
	}

	void awaitStatus(UUID run, RunStatus expected) {
		await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> assertThat(status(run))
				.as("run status").isEqualTo(expected));
	}

	String stepStatus(UUID step) {
		return jdbc.sql("SELECT status FROM research_step WHERE id = :id").param("id", step).query(String.class)
				.single();
	}

	/** Ids of the run's steps of one kind, in seq order. */
	List<UUID> steps(UUID run, String kind) {
		return jdbc.sql("SELECT id FROM research_step WHERE run_id = :run AND kind = :kind ORDER BY seq")
				.param("run", run).param("kind", kind).query(UUID.class).list();
	}

	long events(UUID run, String type, UUID stepId) {
		return jdbc.sql("""
				SELECT count(*) FROM run_event WHERE run_id = :run AND type = :type
				   AND (CAST(:step AS text) IS NULL OR payload->>'stepId' = CAST(:step AS text))""")
				.param("run", run).param("type", type).param("step", stepId == null ? null : stepId.toString())
				.query(Long.class).single();
	}

}
