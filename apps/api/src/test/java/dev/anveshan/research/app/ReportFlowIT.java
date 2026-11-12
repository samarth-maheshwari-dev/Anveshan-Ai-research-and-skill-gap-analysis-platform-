package dev.anveshan.research.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import dev.anveshan.byok.api.Prompt;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.identity.infra.security.SessionUser;
import dev.anveshan.research.app.AssembleHandler.Reason;
import dev.anveshan.research.app.AssembleHandler.ReasonsOutput;
import dev.anveshan.research.app.ExtractHandler.ExtractOutput;
import dev.anveshan.research.app.ExtractHandler.ExtractedClaim;
import dev.anveshan.research.app.Planner.PlanItem;
import dev.anveshan.research.app.Planner.PlanOutput;
import dev.anveshan.research.app.VerifyHandler.VerdictItem;
import dev.anveshan.research.app.VerifyHandler.VerifyOutput;
import dev.anveshan.research.app.WriteHandler.WriteOutput;
import dev.anveshan.research.domain.ResearchEnums.RunStatus;
import dev.anveshan.sources.FakeSearchApis;
import dev.anveshan.sources.FakeSite;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * P14 end to end with a scripted LLM: approve → … → LABEL (one conflict between two preprints) → WRITE (5 default
 * sections, one unknown marker) → ASSEMBLE → COMPLETED, then the report over the API; and a run where the user
 * pauses, skips a source and resumes.
 */
@SpringBootTest(properties = { "management.server.port=0", "anveshan.research.retry-backoff=100ms" })
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ReportFlowIT {

	static final Duration WAIT = Duration.ofSeconds(180);
	static final FakeSearchApis APIS = new FakeSearchApis();
	static final FakeSite SITE = new FakeSite();
	static final Pattern SOURCE = Pattern.compile("<source id=\"(P\\d+)\"[^>]*>\\n(.*?)\\n</source>", Pattern.DOTALL);
	static final Pattern CLAIM_LINE = Pattern.compile("^(C\\d+): .*\\n\\s+check against: ([P0-9, ]+)$", Pattern.MULTILINE);
	static final Pattern WRITER_CLAIM = Pattern.compile("^(C\\d+) \\[", Pattern.MULTILINE);

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

	/** Milliseconds each EXTRACT call waits — gives the skip test a window to pause the run. */
	final AtomicLong extractDelay = new AtomicLong();

	@BeforeEach
	void setUp() {
		APIS.reset();
		user = newUser();
		// two preprints (MEDIUM tier) on the fake site; the other academic APIs find nothing
		APIS.respond("openalex", """
				{"results":[
				 {"id":"https://openalex.org/W1","doi":null,"display_name":"Lost and found apps compared","publication_date":"2024-01-10",
				  "type":"preprint","primary_location":{"landing_page_url":"%s"},"open_access":{"oa_url":null}},
				 {"id":"https://openalex.org/W2","doi":null,"display_name":"Campus item recovery study","publication_date":"2025-03-01",
				  "type":"preprint","primary_location":{"landing_page_url":"%s"},"open_access":{"oa_url":null}}]}"""
				.formatted(SITE.url("/article.html"), SITE.url("/article-2.html")));
		APIS.respond("semanticscholar", "{\"data\":[]}");
		APIS.respond("crossref", "{\"message\":{\"items\":[]}}");
		APIS.respond("arxiv", "<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"></feed>");
		script();
	}

	@Test
	@DisplayName("T-14-04/05 full run 0–8: report, sections, conflicts by code, numbered citations of run claims, IDOR 404")
	void fullRun() throws Exception {
		UUID run = approvedRun();
		awaitStatus(run, RunStatus.COMPLETED);

		JsonNode view = json.readTree(body(perform(get("/api/v1/runs/" + run))));
		String reportId = view.path("reportId").asString();
		assertThat(reportId).isNotBlank();
		var res = perform(get("/api/v1/reports/" + reportId));
		assertThat(res.getResponse().getStatus()).isEqualTo(200);
		JsonNode report = json.readTree(body(res));

		// 5 default sections + conflicts + references
		List<String> keys = new ArrayList<>();
		report.path("sections").forEach(s -> keys.add(s.path("key").asString()));
		assertThat(keys).containsExactly("introduction", "background", "key_findings", "discussion", "conclusion",
				"conflicts", "references");

		// T-14-05: every citation's claim is a claim of this run; numbers start at 1 without gaps
		assertThat(report.path("citations").size()).isPositive();
		Set<String> runClaims = Set.copyOf(jdbc.sql("SELECT claim_id::text FROM run_claim WHERE run_id = :r")
				.param("r", run).query(String.class).list());
		List<Integer> numbers = new ArrayList<>();
		report.path("citations").forEach(c -> {
			assertThat(runClaims).contains(c.path("claimId").asString());
			numbers.add(c.path("n").asInt());
		});
		assertThat(numbers.stream().distinct().sorted().toList()).startsWith(1)
				.isEqualTo(java.util.stream.IntStream.rangeClosed(1, (int) numbers.stream().distinct().count()).boxed().toList());
		assertThat(report.path("claims").size()).isPositive();
		assertThat(report.path("sources").size()).isEqualTo(2);
		String references = section(report, "references").toString();
		assertThat(references).contains("[1] R. Sharma, \\\"Lost and found apps compared,\\\"", "[2] ").doesNotContain("[3] ");
		assertThat(report.path("sources").toString()).contains("Preprint (not peer-reviewed)");

		// T-14-04: the conflicts section — claim, both sides with citations, the one-line reason (1 WRITE call)
		String conflicts = section(report, "conflicts").toString();
		assertThat(conflicts).contains("Supported by:", "Contradicted by:", "Possible reason: The studies looked at different years.");
		assertThat(conflicts).contains("\"type\":\"citation\"");

		// T-14-01 in the flow: the unknown marker C99 left no trace; the unmarked number sentence was counted
		assertThat(report.toString()).doesNotContain("C99", "[c:");
		assertThat(logs(run)).anyMatch(m -> m.startsWith("Removed 1 citation markers"));
		assertThat(logs(run)).anyMatch(m -> m.startsWith("Report ready: 5 sections"));
		assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :r AND type = 'report.ready'").param("r", run)
				.query(Long.class).single()).isEqualTo(1);

		// the run list shows it with its report; another user sees neither
		JsonNode list = json.readTree(body(perform(get("/api/v1/runs"))));
		assertThat(list.path("items").get(0).path("id").asString()).isEqualTo(run.toString());
		assertThat(list.path("items").get(0).path("reportId").asString()).isEqualTo(reportId);
		UUID me = user;
		user = newUser();
		assertThat(perform(get("/api/v1/reports/" + reportId)).getResponse().getStatus()).isEqualTo(404);
		assertThat(perform(get("/api/v1/runs/" + run + "/sources")).getResponse().getStatus()).isEqualTo(404);
		assertThat(json.readTree(body(perform(get("/api/v1/runs")))).path("items").size()).isZero();
		user = me;
	}

	@Test
	@DisplayName("T-14-06 (API) pause → skip a source → resume: it is REJECTED_BY_USER and never cited; skipping after completion → 409")
	void skipSource() throws Exception {
		extractDelay.set(2500);
		UUID run = approvedRun();
		await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> assertThat(
				jdbc.sql("SELECT count(*) FROM research_step WHERE run_id = :r AND kind = 'RELEVANCE' AND status = 'DONE'")
						.param("r", run).query(Long.class).single()).isPositive());
		assertThat(perform(post("/api/v1/runs/" + run + "/pause")).getResponse().getStatus()).isEqualTo(202);
		awaitStatus(run, RunStatus.PAUSED);
		extractDelay.set(0);

		JsonNode sources = json.readTree(body(perform(get("/api/v1/runs/" + run + "/sources"))));
		assertThat(sources.size()).isEqualTo(2);
		String skipped = sources.get(1).path("sourceId").asString();
		assertThat(perform(post("/api/v1/runs/" + run + "/sources/" + skipped + "/skip")).getResponse().getStatus())
				.isEqualTo(204);
		assertThat(perform(post("/api/v1/runs/" + run + "/sources/" + UUID.randomUUID() + "/skip")).getResponse()
				.getStatus()).as("not a source of this run").isEqualTo(404);
		assertThat(json.readTree(body(perform(get("/api/v1/runs/" + run + "/sources")))).toString())
				.contains("REJECTED_BY_USER");

		assertThat(perform(post("/api/v1/runs/" + run + "/resume")).getResponse().getStatus()).isEqualTo(202);
		awaitStatus(run, RunStatus.COMPLETED);
		String reportId = json.readTree(body(perform(get("/api/v1/runs/" + run)))).path("reportId").asString();
		JsonNode report = json.readTree(body(perform(get("/api/v1/reports/" + reportId))));
		report.path("citations").forEach(c -> assertThat(c.path("sourceId").asString()).isNotEqualTo(skipped));
		assertThat(report.path("sources").has(skipped)).isFalse();
		assertThat(perform(post("/api/v1/runs/" + run + "/sources/" + skipped + "/skip")).getResponse().getStatus())
				.as("the report is written: nothing left to skip").isEqualTo(409);
		assertThat(logs(run)).isNotEmpty();
		assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :r AND type = 'source.skipped'").param("r", run)
				.query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :r AND type = 'source.added'").param("r", run)
				.query(Long.class).single()).isEqualTo(2);
	}

	// --- the scripted LLM ---

	void script() {
		when(gateway.call(any(), any(), any(), any(), any())).thenAnswer(inv -> {
			TaskSlot slot = inv.getArgument(1);
			Prompt prompt = inv.getArgument(2);
			Class<?> type = inv.getArgument(3);
			Object value;
			if (slot == TaskSlot.PLAN) {
				value = new PlanOutput(List.of(new PlanItem("How do campus lost and found apps help students?",
						List.of("ACADEMIC"), List.of("campus lost and found app"))));
			}
			else if (slot == TaskSlot.EXTRACT) {
				Thread.sleep(extractDelay.get());
				value = extract(prompt);
			}
			else if (slot == TaskSlot.VERIFY) {
				value = verify(prompt);
			}
			else if (type == ReasonsOutput.class) {
				value = new ReasonsOutput(List.of(new Reason("K1", "The studies looked at different years.")));
			}
			else {
				value = write(prompt);
			}
			return new LlmResult<>(value, UUID.randomUUID(), "fake", 10, 10, 1);
		});
	}

	static ExtractOutput extract(Prompt prompt) {
		List<ExtractedClaim> out = new ArrayList<>();
		Matcher m = SOURCE.matcher(prompt.user());
		while (m.find()) {
			String first = m.group(2).strip().split("(?<=\\.)\\s", 2)[0].replaceAll("\\s+", " ");
			out.add(new ExtractedClaim(m.group(1), first, first));
		}
		return new ExtractOutput(out);
	}

	/** Own passage SUPPORTS; C1 is also CONTRADICTED by the first other source's passage → one conflict. */
	static VerifyOutput verify(Prompt prompt) {
		List<VerdictItem> out = new ArrayList<>();
		Matcher m = CLAIM_LINE.matcher(prompt.user());
		while (m.find()) {
			String[] ps = m.group(2).split(",");
			out.add(new VerdictItem(m.group(1), ps[0].strip(), "SUPPORTS", "States it."));
			if (m.group(1).equals("C1") && ps.length > 1) {
				out.add(new VerdictItem("C1", ps[1].strip(), "CONTRADICTS", "Says the opposite."));
			}
		}
		return new VerifyOutput(out);
	}

	/** Cites the first two claims given, plus an unknown C99 and an unmarked sentence with a number. */
	static WriteOutput write(Prompt prompt) {
		List<String> ids = new ArrayList<>();
		Matcher m = WRITER_CLAIM.matcher(prompt.user());
		while (m.find()) {
			ids.add(m.group(1));
		}
		String second = ids.size() > 1 ? ids.get(1) : ids.get(0);
		return new WriteOutput("Students can report lost items from their phones [c:" + ids.get(0) + "].\n\n"
				+ "- Offices match items with reports [c:" + second + ", C99].\n- About 40 percent of items are returned.");
	}

	// --- helpers ---

	UUID newUser() {
		UUID u = jdbc.sql("INSERT INTO app_user (email, email_verified) VALUES (:e, true) RETURNING id")
				.param("e", "reena-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
		UUID llm = jdbc.sql("""
				INSERT INTO ai_connection (owner_id, kind, provider, label, base_url, key_ciphertext, key_last4, status)
				VALUES (:o, 'LLM', 'GROQ', 'Test', 'https://example.test/', :c, 'abcd', 'VERIFIED') RETURNING id""")
				.param("o", u).param("c", new byte[] { 1 }).query(UUID.class).single();
		for (String slot : List.of("PLAN", "WRITE", "EXTRACT", "VERIFY")) {
			jdbc.sql("INSERT INTO slot_route (owner_id, slot, position, connection_id, model) VALUES (:o, :s, 0, :c, 'm')")
					.param("o", u).param("s", slot).param("c", llm).update();
		}
		return u;
	}

	UUID approvedRun() throws Exception {
		var res = perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
				.content("{\"topic\":\"How do campus lost and found apps help students?\",\"depth\":\"QUICK\",\"sources\":[\"ACADEMIC\"]}"));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(202);
		UUID run = UUID.fromString(json.readTree(body(res)).path("id").asString());
		awaitStatus(run, RunStatus.AWAITING_APPROVAL);
		String etag = perform(get("/api/v1/runs/" + run)).getResponse().getHeader("ETag");
		assertThat(perform(post("/api/v1/runs/" + run + "/approve").header("If-Match", etag)).getResponse().getStatus())
				.isEqualTo(202);
		return run;
	}

	static JsonNode section(JsonNode report, String key) {
		for (JsonNode s : report.path("sections")) {
			if (s.path("key").asString().equals(key)) {
				return s;
			}
		}
		throw new AssertionError("no section " + key);
	}

	void awaitStatus(UUID run, RunStatus status) {
		await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> assertThat(
				RunStatus.valueOf(jdbc.sql("SELECT status FROM research_run WHERE id = :id").param("id", run)
						.query(String.class).single())).isEqualTo(status));
	}

	List<String> logs(UUID run) {
		return jdbc.sql("SELECT payload->>'message' FROM run_event WHERE run_id = :r AND type = 'log'").param("r", run)
				.query(String.class).list();
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
