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
import dev.anveshan.sources.FakeSite;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * P12 end to end: approve → SEARCH (fake APIs whose results point at a fake website) → FETCH (real reading,
 * chunking, embedding) → RELEVANCE. EXTRACT (P13) has no handler yet, so the run stops after RELEVANCE.
 */
@SpringBootTest(properties = { "management.server.port=0", "anveshan.research.retry-backoff=100ms" })
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class FetchFlowIT {

	static final Duration WAIT = Duration.ofSeconds(120);
	static final FakeSearchApis APIS = new FakeSearchApis();
	static final FakeSite SITE = new FakeSite();

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		APIS.register(r);
		SITE.register(r);
	}

	@MockitoBean
	LlmGateway gateway;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	UUID user;

	@Test
	@DisplayName("T-12-05/07 flow: web results are read, linked to the run, and its most relevant passages picked")
	void searchFetchRelevance() throws Exception {
		user = jdbc.sql("INSERT INTO app_user (email, email_verified) VALUES (:e, true) RETURNING id")
				.param("e", "reena-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
		UUID llm = jdbc.sql("""
				INSERT INTO ai_connection (owner_id, kind, provider, label, base_url, key_ciphertext, key_last4, status)
				VALUES (:o, 'LLM', 'GROQ', 'Test', 'https://example.test/', :c, 'abcd', 'VERIFIED') RETURNING id""")
				.param("o", user).param("c", new byte[] { 1 }).query(UUID.class).single();
		for (String slot : List.of("PLAN", "WRITE")) {
			jdbc.sql("INSERT INTO slot_route (owner_id, slot, position, connection_id, model) VALUES (:o, :s, 0, :c, 'm')")
					.param("o", user).param("s", slot).param("c", llm).update();
		}
		when(gateway.call(any(), eq(TaskSlot.PLAN), any(), eq(PlanOutput.class), any()))
				.thenAnswer(inv -> new LlmResult<>(new PlanOutput(List.of(new PlanItem(
						"How do students report a lost item on campus apps?", List.of("WEB"),
						List.of("campus lost and found app")))), UUID.randomUUID(), "fake", 10, 10, 1));
		// the web search finds three pages of the fake site: an article, a PDF and a robots-blocked one
		APIS.respond("searxng", """
				{"results":[
				 {"url":"%s","title":"Lost and found apps compared","content":"Students report lost items from their phone."},
				 {"url":"%s","title":"QR code recovery","content":"QR codes on items."},
				 {"url":"%s","title":"Secret page","content":"Not for bots."}]}"""
				.formatted(SITE.url("/article.html"), SITE.url("/paper.pdf"), SITE.url("/private/secret.html")));

		var res = perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
				.content("{\"topic\":\"How do campus lost and found apps work?\",\"depth\":\"QUICK\",\"sources\":[\"WEB\"]}"));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(202);
		UUID run = UUID.fromString(json.readTree(body(res)).path("id").asString());
		await().atMost(WAIT).untilAsserted(() -> assertThat(status(run)).isEqualTo(RunStatus.AWAITING_APPROVAL));
		String etag = perform(get("/api/v1/runs/" + run)).getResponse().getHeader("ETag");
		assertThat(perform(post("/api/v1/runs/" + run + "/approve").header("If-Match", etag)).getResponse().getStatus())
				.isEqualTo(202);

		await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> assertThat(
				jdbc.sql("SELECT status FROM research_step WHERE run_id = :r AND kind = 'RELEVANCE'").param("r", run)
						.query(String.class).list()).containsExactly("DONE"));

		JsonNode fetch = output(run, "FETCH");
		assertThat(fetch.path("sources").size()).isEqualTo(2);
		assertThat(fetch.path("unavailable").toString()).contains("robots.txt");
		assertThat(SITE.hits("/private/secret.html")).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM run_source WHERE run_id = :r AND status = 'USED'").param("r", run)
				.query(Long.class).single()).isEqualTo(2);

		JsonNode relevance = output(run, "RELEVANCE");
		assertThat(relevance.path("chunks").size()).isPositive().isLessThanOrEqualTo(4 * 2); // QUICK: 4 sources × 2 chunks
		List<String> messages = jdbc.sql("SELECT payload->>'message' FROM run_event WHERE run_id = :r AND type = 'log'")
				.param("r", run).query(String.class).list();
		assertThat(messages).anyMatch(m -> m.startsWith("Read 2 sources"));
		assertThat(messages).anyMatch(m -> m.contains("couldn't be opened"));
		assertThat(messages).anyMatch(m -> m.startsWith("Picked the"));
	}

	JsonNode output(UUID run, String kind) {
		return json.readTree(jdbc.sql("SELECT output::text FROM research_step WHERE run_id = :r AND kind = :k AND status = 'DONE'")
				.param("r", run).param("k", kind).query(String.class).single());
	}

	RunStatus status(UUID run) {
		return RunStatus.valueOf(jdbc.sql("SELECT status FROM research_run WHERE id = :id").param("id", run)
				.query(String.class).single());
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
