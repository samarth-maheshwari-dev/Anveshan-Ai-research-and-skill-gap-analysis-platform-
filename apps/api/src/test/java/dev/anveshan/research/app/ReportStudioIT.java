package dev.anveshan.research.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import dev.anveshan.sources.api.SourceLibrary;
import dev.anveshan.sources.api.SourceSearch.Candidate;
import dev.anveshan.sources.api.SourceSearch.CandidateType;
import dev.anveshan.research.domain.ResearchEnums.RunStatus;
import dev.anveshan.sources.FakeSearchApis;
import dev.anveshan.sources.FakeSite;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * P15 Report Studio over the API, on reports made by a real (scripted-LLM) run: the citation panel's passage,
 * section saves with If-Match, versions + restore, regenerate one section, Assist on a selection, and Find better
 * sources turning a WEAK claim SUPPORTED through a hidden child run.
 */
@SpringBootTest(properties = { "management.server.port=0", "anveshan.research.retry-backoff=100ms" })
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ReportStudioIT {

	static final Duration WAIT = Duration.ofSeconds(180);
	static final FakeSearchApis APIS = new FakeSearchApis();
	static final FakeSite SITE = new FakeSite();
	static final Pattern SOURCE = Pattern.compile("<source id=\"(P\\d+)\"[^>]*>\\n(.*?)\\n</source>", Pattern.DOTALL);
	static final Pattern CLAIM_LINE = Pattern.compile("^(C\\d+): .*\\n\\s+check against: ([P0-9, ]+)$", Pattern.MULTILINE);
	static final Pattern WRITER_CLAIM = Pattern.compile("^(C\\d+) \\[", Pattern.MULTILINE);
	static final Pattern ASSIST_CLAIM = Pattern.compile("\\[c:(C\\d+)\\]");

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

	@Autowired
	SourceLibrary sourceLibrary;

	UUID user;

	/** Every slot the fake LLM was called for, in order. */
	final ConcurrentLinkedQueue<TaskSlot> calls = new ConcurrentLinkedQueue<>();

	@BeforeEach
	void setUp() {
		APIS.reset();
		calls.clear();
		user = newUser();
		// two preprints (MEDIUM); every claim's own passage only PARTIALLY supports it → all claims start WEAK
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
	@DisplayName("T-15-01 GET /reports/{id}/claims/{claimId}/passage: highlight = the claim's stored quote span; other user / unknown claim → 404")
	void passage() throws Exception {
		JsonNode report = report(completedRun());
		String reportId = report.path("id").asString();
		String claimId = report.path("citations").get(0).path("claimId").asString();
		var res = perform(get("/api/v1/reports/" + reportId + "/claims/" + claimId + "/passage"));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(200);
		JsonNode p = jsonOf(res);
		Map<String, Object> stored = jdbc.sql("SELECT quote, quote_start, quote_end FROM claim WHERE id = :c")
				.param("c", UUID.fromString(claimId)).query().singleRow();
		assertThat(p.path("highlight").path("start").asInt()).isEqualTo(stored.get("quote_start"));
		assertThat(p.path("highlight").path("end").asInt()).isEqualTo(stored.get("quote_end"));
		String chunk = p.path("chunkText").asString();
		assertThat(chunk.substring(p.path("highlight").path("start").asInt(), p.path("highlight").path("end").asInt()))
				.isEqualTo(stored.get("quote")).isEqualTo(p.path("quote").asString());
		assertThat(p.path("source").path("credibilityReason").asString()).startsWith("Preprint");
		assertThat(p.path("trustLabel").asString()).isEqualTo("WEAK");
		JsonNode own = null;
		for (JsonNode e : p.path("evidence")) {
			if (e.path("own").asBoolean()) {
				own = e;
			}
		}
		assertThat(own).as("the claim's own source judged it").isNotNull();
		assertThat(own.path("verdict").asString()).isEqualTo("PARTIAL");

		assertThat(perform(get("/api/v1/reports/" + reportId + "/claims/" + UUID.randomUUID() + "/passage")).getResponse()
				.getStatus()).isEqualTo(404);
		asOtherUser(() -> assertThat(perform(get("/api/v1/reports/" + reportId + "/claims/" + claimId + "/passage"))
				.getResponse().getStatus()).isEqualTo(404));
	}

	@Test
	@DisplayName("T-15-02 two PATCHes with the same version → the second is 409 VERSION_CONFLICT; no If-Match → 400; other user → 404; signed out → 401")
	void patchConflict() throws Exception {
		JsonNode report = report(completedRun());
		String reportId = report.path("id").asString();
		JsonNode intro = section(report, "introduction");
		String url = "/api/v1/reports/" + reportId + "/sections/" + intro.path("id").asString();
		String body = "{\"contentDoc\":" + intro.path("contentDoc") + "}";
		int v = intro.path("version").asInt();

		var first = perform(patch(url).header("If-Match", "\"" + v + "\"").contentType(MediaType.APPLICATION_JSON).content(body));
		assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(200);
		assertThat(first.getResponse().getHeader("ETag")).isEqualTo("\"" + (v + 1) + "\"");
		var second = perform(patch(url).header("If-Match", "\"" + v + "\"").contentType(MediaType.APPLICATION_JSON).content(body));
		assertThat(second.getResponse().getStatus()).isEqualTo(409);
		assertThat(jsonOf(second).path("code").asString()).isEqualTo("VERSION_CONFLICT");
		assertThat(jsonOf(second).path("currentVersion").asString()).isEqualTo(Integer.toString(v + 1));

		assertThat(perform(patch(url).contentType(MediaType.APPLICATION_JSON).content(body)).getResponse().getStatus())
				.isEqualTo(400);
		assertThat(perform(patch(url).header("If-Match", "\"" + (v + 1) + "\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"contentDoc\":{\"type\":\"paragraph\"}}")).getResponse().getStatus()).isEqualTo(400);
		String refs = "/api/v1/reports/" + reportId + "/sections/" + section(report, "references").path("id").asString();
		assertThat(perform(patch(refs).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(body))
				.getResponse().getStatus()).as("References are built by code").isEqualTo(400);
		asOtherUser(() -> assertThat(perform(patch(url).header("If-Match", "\"" + (v + 1) + "\"")
				.contentType(MediaType.APPLICATION_JSON).content(body)).getResponse().getStatus()).isEqualTo(404));
		assertThat(mvc.perform(patch(url).with(csrf()).header("If-Match", "\"" + (v + 1) + "\"")
				.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	@DisplayName("T-15-03 save a section: unsafe nodes dropped, content_md re-derived, citations renumbered without gaps, search_tsv updated")
	void saveSection() throws Exception {
		JsonNode report = report(completedRun());
		String reportId = report.path("id").asString();
		JsonNode intro = section(report, "introduction");
		JsonNode findings = section(report, "key_findings");
		// the introduction loses its cited text; it now cites one of key findings' claims, plus junk
		String claim = firstCitation(findings.path("contentDoc")).path("claimId").asString();
		String doc = """
				{"type":"doc","content":[{"type":"paragraph","content":[
				  {"type":"text","text":"Zebracorn apps help "},
				  {"type":"citation","attrs":{"claimId":"%s","n":42,"label":"SUPPORTED"}},
				  {"type":"citation","attrs":{"claimId":"%s","n":1}},
				  {"type":"image","attrs":{"src":"https://evil.example/x.png"}},
				  {"type":"text","text":" students.","marks":[{"type":"bold"},{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}]}]}"""
				.formatted(claim, UUID.randomUUID());
		var res = perform(patch("/api/v1/reports/" + reportId + "/sections/" + intro.path("id").asString())
				.header("If-Match", "\"" + intro.path("version").asInt() + "\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"contentDoc\":" + doc + "}"));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(200);
		String saved = jsonOf(res).path("contentDoc").toString();
		assertThat(saved).doesNotContain("image", "evil", "javascript", "link", "\"n\":42");
		assertThat(saved).contains("\"n\":1", "\"label\":\"WEAK\"");

		Map<String, Object> row = jdbc.sql("""
				SELECT content_md, search_tsv @@ plainto_tsquery('simple', 'zebracorn') AS found
				  FROM report_section WHERE id = :s""").param("s", UUID.fromString(intro.path("id").asString())).query()
				.singleRow();
		assertThat(row.get("content_md")).isEqualTo("Zebracorn apps help [1]** students.**");
		assertThat(row.get("found")).isEqualTo(true);

		// the whole report: numbers 1..n by first appearance, no gaps; References list matches
		JsonNode after = report(UUID.fromString(report.path("runId").asString()));
		List<Integer> order = new ArrayList<>();
		for (JsonNode s : after.path("sections")) {
			collectNumbers(s.path("contentDoc"), order);
		}
		List<Integer> firsts = order.stream().distinct().toList();
		assertThat(firsts).isEqualTo(java.util.stream.IntStream.rangeClosed(1, firsts.size()).boxed().toList());
		String refs = section(after, "references").path("contentDoc").toString();
		assertThat(refs).contains("[" + firsts.size() + "] ").doesNotContain("[" + (firsts.size() + 1) + "] ");
	}

	@Test
	@DisplayName("T-15-04 restore version 1: sections equal the snapshot; a new version is created and history is kept")
	void restore() throws Exception {
		JsonNode report = report(completedRun());
		String reportId = report.path("id").asString();
		JsonNode intro = section(report, "introduction");
		var edit = perform(patch("/api/v1/reports/" + reportId + "/sections/" + intro.path("id").asString())
				.header("If-Match", "\"" + intro.path("version").asInt() + "\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"contentDoc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"My own words.\"}]}]}}"));
		assertThat(edit.getResponse().getStatus()).as(body(edit)).isEqualTo(200);

		JsonNode versions = jsonOf(perform(get("/api/v1/reports/" + reportId + "/versions")));
		List<Integer> numbers = new ArrayList<>();
		versions.path("items").forEach(v -> numbers.add(v.path("version").asInt()));
		assertThat(numbers).containsExactly(2, 1);
		assertThat(versions.path("items").get(0).path("byYou").asBoolean()).isTrue();
		assertThat(versions.path("items").get(1).path("byYou").asBoolean()).as("v1 was written by the assembler").isFalse();

		var res = perform(post("/api/v1/reports/" + reportId + "/versions/1/restore"));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(200);
		assertThat(jsonOf(res).path("version").asInt()).isEqualTo(3);

		JsonNode snapshot = json.readTree(jdbc.sql("SELECT snapshot::text FROM report_version WHERE report_id = :r AND version = 1")
				.param("r", UUID.fromString(reportId)).query(String.class).single());
		JsonNode now = report(UUID.fromString(report.path("runId").asString()));
		for (JsonNode s : snapshot.path("sections")) {
			JsonNode current = byId(now, s.path("id").asString());
			assertThat(current.path("contentDoc")).as(s.path("key").asString()).isEqualTo(s.path("contentDoc"));
		}
		assertThat(byId(now, intro.path("id").asString()).path("version").asInt()).isEqualTo(intro.path("version").asInt() + 2);
		assertThat(jdbc.sql("SELECT count(*) FROM report_version WHERE report_id = :r").param("r", UUID.fromString(reportId))
				.query(Long.class).single()).isEqualTo(3);
		assertThat(perform(post("/api/v1/reports/" + reportId + "/versions/99/restore")).getResponse().getStatus()).isEqualTo(404);
		asOtherUser(() -> {
			assertThat(perform(get("/api/v1/reports/" + reportId + "/versions")).getResponse().getStatus()).isEqualTo(404);
			assertThat(perform(post("/api/v1/reports/" + reportId + "/versions/1/restore")).getResponse().getStatus()).isEqualTo(404);
		});
	}

	@Test
	@DisplayName("T-15-05 Find better sources on a WEAK claim: a stronger source makes it SUPPORTED in the original run; the child run is hidden")
	void findBetterSources() throws Exception {
		UUID run = completedRun();
		JsonNode report = report(run);
		String reportId = report.path("id").asString();
		String claimId = report.path("citations").get(0).path("claimId").asString();
		assertThat(label(run, claimId)).isEqualTo("WEAK");

		// the mini-research finds a peer-reviewed paper (DOI → HIGH tier) that supports the claim
		APIS.respond("openalex", """
				{"results":[{"id":"https://openalex.org/W3","doi":"https://doi.org/10.1234/campus.survey",
				  "display_name":"A survey of campus lost and found","publication_date":"2025-05-01","type":"article",
				  "primary_location":{"landing_page_url":"%s"},"open_access":{"oa_url":null}}]}"""
				.formatted(SITE.url("/article-3.html")));
		// adapters address a DOI paper as https://doi.org/…, which the fake site can't serve: the paper is already
		// in the public library (read from its landing page), so the child run reuses it by DOI without a fetch
		var survey = sourceLibrary.ingest(new Candidate("A survey of campus lost and found", SITE.url("/article-3.html"),
				"https://doi.org/10.1234/campus.survey", "", java.time.LocalDate.of(2025, 5, 1), CandidateType.PAPER,
				"openalex", 1.0));
		assertThat(survey.tier()).isEqualTo("HIGH");
		asOtherUser(() -> assertThat(perform(post("/api/v1/reports/" + reportId + "/claims/" + claimId
				+ "/find-better-sources")).getResponse().getStatus()).isEqualTo(404));
		var res = perform(post("/api/v1/reports/" + reportId + "/claims/" + claimId + "/find-better-sources"));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(202);
		UUID child = UUID.fromString(jsonOf(res).path("runId").asString());
		assertThat(res.getResponse().getHeader("Location")).isEqualTo("/api/v1/runs/" + child);
		awaitStatus(child, RunStatus.COMPLETED);

		assertThat(label(run, claimId)).as(() -> "child log: " + jdbc.sql(
				"SELECT type || ' ' || payload::text FROM run_event WHERE run_id = :r ORDER BY id").param("r", child)
				.query(String.class).list()).isEqualTo("SUPPORTED");
		assertThat(jdbc.sql("""
				SELECT count(*) FROM verdict v JOIN source_chunk c ON c.id = v.chunk_id JOIN source_document d ON d.id = c.source_id
				 WHERE v.run_id = :r AND v.claim_id = :c AND v.verdict = 'SUPPORTS' AND d.credibility_tier = 'HIGH'""")
				.param("r", run).param("c", UUID.fromString(claimId)).query(Long.class).single()).isPositive();
		assertThat(jdbc.sql("SELECT count(*) FROM research_step WHERE run_id = :r AND kind IN ('PLAN','WRITE_SECTION','ASSEMBLE')")
				.param("r", child).query(Long.class).single()).as("no planning, no report").isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM report WHERE run_id = :r").param("r", child).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :r AND type = 'claim.updated'").param("r", child)
				.query(Long.class).single()).isEqualTo(1);

		// the report shows the new label, also on its citation chips; the run list hides the child run
		JsonNode after = report(run);
		assertThat(after.path("claims").path(claimId).path("trustLabel").asString()).isEqualTo("SUPPORTED");
		for (JsonNode s : after.path("sections")) {
			assertCitationLabel(s.path("contentDoc"), claimId, "SUPPORTED");
		}
		JsonNode list = json.readTree(body(perform(get("/api/v1/runs"))));
		assertThat(list.path("items").size()).isEqualTo(1);
		assertThat(list.path("items").get(0).path("id").asString()).isEqualTo(run.toString());
		assertThat(perform(get("/api/v1/runs/" + child)).getResponse().getStatus()).as("still readable by id").isEqualTo(200);
	}

	@Test
	@DisplayName("T-15-06 regenerate a section: one WRITE call over the existing claims — zero SEARCH/EXTRACT/VERIFY — and a new version")
	void regenerate() throws Exception {
		UUID run = completedRun();
		JsonNode report = report(run);
		String reportId = report.path("id").asString();
		JsonNode findings = section(report, "key_findings");
		long steps = jdbc.sql("SELECT count(*) FROM research_step WHERE run_id = :r").param("r", run).query(Long.class).single();
		long searches = APIS.requestsTo("openalex").size();
		calls.clear();

		var res = perform(post("/api/v1/reports/" + reportId + "/sections/" + findings.path("id").asString() + "/regenerate")
				.header("If-Match", "\"" + findings.path("version").asInt() + "\""));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(200);
		assertThat(List.copyOf(calls)).containsExactly(TaskSlot.WRITE);
		assertThat(APIS.requestsTo("openalex")).hasSize((int) searches);
		assertThat(jdbc.sql("SELECT count(*) FROM research_step WHERE run_id = :r").param("r", run).query(Long.class).single())
				.isEqualTo(steps);
		JsonNode saved = jsonOf(res);
		assertThat(saved.path("version").asInt()).isEqualTo(findings.path("version").asInt() + 1);
		assertThat(saved.path("contentDoc").toString()).contains("Regenerated:", "\"type\":\"citation\"");
		assertThat(jdbc.sql("SELECT max(version) FROM report_version WHERE report_id = :r")
				.param("r", UUID.fromString(reportId)).query(Integer.class).single()).isEqualTo(2);

		assertThat(perform(post("/api/v1/reports/" + reportId + "/sections/" + findings.path("id").asString() + "/regenerate")
				.header("If-Match", "\"" + findings.path("version").asInt() + "\"")).getResponse().getStatus())
				.as("stale version").isEqualTo(409);
		assertThat(perform(post("/api/v1/reports/" + reportId + "/sections/" + section(report, "conflicts").path("id").asString()
				+ "/regenerate")).getResponse().getStatus()).isEqualTo(400);
		asOtherUser(() -> assertThat(perform(post("/api/v1/reports/" + reportId + "/sections/"
				+ findings.path("id").asString() + "/regenerate")).getResponse().getStatus()).isEqualTo(404));
	}

	@Test
	@DisplayName("T-15-14 Assist on a selection: one WRITE call; the suggestion cites only claims from the selection; empty selection → 400")
	void assist() throws Exception {
		JsonNode report = report(completedRun());
		String reportId = report.path("id").asString();
		JsonNode intro = section(report, "introduction");
		String claim = firstCitation(intro.path("contentDoc")).path("claimId").asString();
		calls.clear();
		var res = perform(post("/api/v1/reports/" + reportId + "/assist").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sectionId\":\"" + intro.path("id").asString() + "\",\"selection\":{\"from\":0,\"to\":100000},"
						+ "\"action\":\"SIMPLIFY\"}"));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(200);
		assertThat(List.copyOf(calls)).containsExactly(TaskSlot.WRITE);
		JsonNode suggestion = jsonOf(res).path("suggestion");
		assertThat(suggestion.path("type").asString()).isEqualTo("doc");
		assertThat(suggestion.toString()).contains("Simpler:", "\"claimId\":\"" + claim + "\"").doesNotContain("C9", "[c:");
		// nothing was saved: Assist only suggests
		assertThat(jdbc.sql("SELECT max(version) FROM report_version WHERE report_id = :r")
				.param("r", UUID.fromString(reportId)).query(Integer.class).single()).isEqualTo(1);

		assertThat(perform(post("/api/v1/reports/" + reportId + "/assist").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sectionId\":\"" + intro.path("id").asString() + "\",\"selection\":{\"from\":5,\"to\":5},"
						+ "\"action\":\"SIMPLIFY\"}")).getResponse().getStatus()).isEqualTo(400);
		assertThat(perform(post("/api/v1/reports/" + reportId + "/assist").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sectionId\":\"" + intro.path("id").asString() + "\",\"selection\":{\"from\":0,\"to\":9},"
						+ "\"action\":\"SHOUT\"}")).getResponse().getStatus()).isEqualTo(400);
		asOtherUser(() -> assertThat(perform(post("/api/v1/reports/" + reportId + "/assist")
				.contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + intro.path("id").asString()
						+ "\",\"selection\":{\"from\":0,\"to\":9},\"action\":\"SIMPLIFY\"}"))
				.getResponse().getStatus()).isEqualTo(404));
	}

	// --- the scripted LLM ---

	void script() {
		when(gateway.call(any(), any(), any(), any(), any())).thenAnswer(inv -> {
			TaskSlot slot = inv.getArgument(1);
			Prompt prompt = inv.getArgument(2);
			Class<?> type = inv.getArgument(3);
			calls.add(slot);
			Object value;
			if (slot == TaskSlot.PLAN) {
				value = new PlanOutput(List.of(new PlanItem("How do campus lost and found apps help students?",
						List.of("ACADEMIC"), List.of("campus lost and found app"))));
			}
			else if (slot == TaskSlot.EXTRACT) {
				value = extract(prompt);
			}
			else if (slot == TaskSlot.VERIFY) {
				value = verify(prompt);
			}
			else if (type == ReasonsOutput.class) {
				value = new ReasonsOutput(List.of(new Reason("K1", "Different years.")));
			}
			else if (type == ReportStudio.AssistOutput.class) {
				Matcher m = ASSIST_CLAIM.matcher(prompt.user());
				String first = m.find() ? m.group(1) : "C1";
				value = new ReportStudio.AssistOutput("Simpler: students report lost items [c:" + first + "] [c:C9].");
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

	/**
	 * The claim's own passage (listed first) only PARTIALLY supports it; other passages SUPPORT it only when they come
	 * from the peer-reviewed survey (Find better sources) — otherwise INSUFFICIENT. So every claim starts WEAK.
	 */
	static VerifyOutput verify(Prompt prompt) {
		List<VerdictItem> out = new ArrayList<>();
		java.util.Set<String> survey = new java.util.HashSet<>();
		Matcher s = SOURCE.matcher(prompt.user());
		while (s.find()) {
			if (s.group(2).contains("/article-3.html")) {
				survey.add(s.group(1));
			}
		}
		Matcher m = CLAIM_LINE.matcher(prompt.user());
		while (m.find()) {
			String[] ps = m.group(2).split(",");
			out.add(new VerdictItem(m.group(1), ps[0].strip(), "PARTIAL", "Says part of it."));
			for (int i = 1; i < ps.length; i++) {
				String p = ps[i].strip();
				out.add(new VerdictItem(m.group(1), p, survey.contains(p) ? "SUPPORTS" : "INSUFFICIENT", "Checked."));
			}
		}
		return new VerifyOutput(out);
	}

	static WriteOutput write(Prompt prompt) {
		List<String> ids = new ArrayList<>();
		Matcher m = WRITER_CLAIM.matcher(prompt.user());
		while (m.find()) {
			ids.add(m.group(1));
		}
		String second = ids.size() > 1 ? ids.get(1) : ids.get(0);
		return new WriteOutput("Regenerated: students can report lost items [c:" + ids.get(0) + "].\n\n"
				+ "- Offices match items with reports [c:" + second + "].");
	}

	// --- helpers ---

	interface Body {

		void run() throws Exception;

	}

	void asOtherUser(Body body) throws Exception {
		UUID me = user;
		user = newUser();
		try {
			body.run();
		}
		finally {
			user = me;
		}
	}

	UUID newUser() {
		UUID u = jdbc.sql("INSERT INTO app_user (email, email_verified) VALUES (:e, true) RETURNING id")
				.param("e", "sneha-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
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

	/** A QUICK academic run approved over the API and finished with its report. */
	UUID completedRun() throws Exception {
		var res = perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
				.content("{\"topic\":\"How do campus lost and found apps help students?\",\"depth\":\"QUICK\",\"sources\":[\"ACADEMIC\"]}"));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(202);
		UUID run = UUID.fromString(jsonOf(res).path("id").asString());
		awaitStatus(run, RunStatus.AWAITING_APPROVAL);
		String etag = perform(get("/api/v1/runs/" + run)).getResponse().getHeader("ETag");
		assertThat(perform(post("/api/v1/runs/" + run + "/approve").header("If-Match", etag)).getResponse().getStatus())
				.isEqualTo(202);
		awaitStatus(run, RunStatus.COMPLETED);
		return run;
	}

	JsonNode report(UUID run) throws Exception {
		String reportId = jsonOf(perform(get("/api/v1/runs/" + run))).path("reportId").asString();
		var res = perform(get("/api/v1/reports/" + reportId));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(200);
		return jsonOf(res);
	}

	String label(UUID run, String claimId) {
		return jdbc.sql("SELECT trust_label FROM run_claim WHERE run_id = :r AND claim_id = :c").param("r", run)
				.param("c", UUID.fromString(claimId)).query(String.class).single();
	}

	static JsonNode section(JsonNode report, String key) {
		for (JsonNode s : report.path("sections")) {
			if (s.path("key").asString().equals(key)) {
				return s;
			}
		}
		throw new AssertionError("no section " + key);
	}

	static JsonNode byId(JsonNode report, String id) {
		for (JsonNode s : report.path("sections")) {
			if (s.path("id").asString().equals(id)) {
				return s;
			}
		}
		throw new AssertionError("no section " + id);
	}

	static JsonNode firstCitation(JsonNode node) {
		if ("citation".equals(node.path("type").asString())) {
			return node.path("attrs");
		}
		for (JsonNode c : node.path("content")) {
			JsonNode found = firstCitation(c);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	static void collectNumbers(JsonNode node, List<Integer> out) {
		if ("citation".equals(node.path("type").asString())) {
			out.add(node.path("attrs").path("n").asInt());
		}
		node.path("content").forEach(c -> collectNumbers(c, out));
	}

	static void assertCitationLabel(JsonNode node, String claimId, String label) {
		if ("citation".equals(node.path("type").asString()) && claimId.equals(node.path("attrs").path("claimId").asString())) {
			assertThat(node.path("attrs").path("label").asString()).isEqualTo(label);
		}
		node.path("content").forEach(c -> assertCitationLabel(c, claimId, label));
	}

	void awaitStatus(UUID run, RunStatus status) {
		await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> assertThat(
				RunStatus.valueOf(jdbc.sql("SELECT status FROM research_run WHERE id = :id").param("id", run)
						.query(String.class).single())).isEqualTo(status));
	}

	MvcResult perform(MockHttpServletRequestBuilder req) throws Exception {
		var token = new OAuth2AuthenticationToken(new SessionUser(user), List.of(new SimpleGrantedAuthority("ROLE_USER")),
				"google");
		return mvc.perform(req.with(authentication(token)).with(csrf())).andReturn();
	}

	JsonNode jsonOf(MvcResult r) {
		return json.readTree(body(r));
	}

	static String body(MvcResult r) {
		return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

}
