package dev.anveshan.research.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import dev.anveshan.research.domain.ResearchEnums.RunStatus;

/** /api/v1/runs endpoints: SSE replay + live, ownership (404), idempotency, auth and validation. */
class RunApiIT extends ResearchITBase {

	static final Pattern SSE_ID = Pattern.compile("(?m)^id:(\\d+)$");

	@Test
	@DisplayName("T-09-08 SSE with Last-Event-ID = the 5th event → events 6…n replayed in order, then live ones follow")
	void sseReplayThenLive() throws Exception {
		UUID run = startRun(alice);
		awaitStatus(run, RunStatus.AWAITING_APPROVAL);
		List<Long> ids = eventIds(run);
		assertThat(ids).as("PLANNING, step.started, step.completed, log, run.progress, AWAITING_APPROVAL").hasSize(6);

		MvcResult stream = mvc.perform(as(alice, get("/api/v1/runs/" + run + "/events"))
				.accept(MediaType.TEXT_EVENT_STREAM).header("Last-Event-ID", ids.get(4).toString())).andReturn();
		assertThat(stream.getRequest().isAsyncStarted()).isTrue();
		assertThat(stream.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
		assertThat(stream.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
		await().atMost(WAIT).untilAsserted(() -> assertThat(sentIds(stream)).containsExactly(ids.get(5)));
		assertThat(body(stream)).contains("event:run.status").containsPattern("\"status\":\\s*\"AWAITING_APPROVAL\"");

		engine.approve(run); // live: RUNNING → stages → COMPLETED arrive on the open stream
		awaitStatus(run, RunStatus.COMPLETED);
		await().atMost(WAIT).untilAsserted(() -> {
			List<Long> all = eventIds(run);
			assertThat(sentIds(stream)).as("every later event, once, in order").containsExactlyElementsOf(all.subList(5, all.size()));
		});
		assertThat(body(stream)).containsPattern("\"status\":\\s*\"RUNNING\"").containsPattern("\"status\":\\s*\"COMPLETED\"");
		mvc.perform(asyncDispatch(stream)).andReturn(); // the stream ended itself after COMPLETED (no 30-min idle)

		// T-09-25 reconnecting after the end: the replay is empty and the stream closes at once (REV-P09-R2-08)
		long last = eventIds(run).getLast();
		MvcResult again = mvc.perform(as(alice, get("/api/v1/runs/" + run + "/events"))
				.accept(MediaType.TEXT_EVENT_STREAM).header("Last-Event-ID", Long.toString(last))).andReturn();
		mvc.perform(asyncDispatch(again)).andReturn();
		assertThat(sentIds(again)).isEmpty();
	}

	@Test
	@DisplayName("T-09-09 user B opens user A's run events stream (and every other run endpoint) → 404")
	void otherUsersRunIsNotFound() throws Exception {
		UUID run = startRun(alice);
		var events = perform(bob, get("/api/v1/runs/" + run + "/events").accept(MediaType.TEXT_EVENT_STREAM));
		assertThat(events.getResponse().getStatus()).isEqualTo(404);
		assertThat(events.getRequest().isAsyncStarted()).isFalse();

		assertThat(perform(bob, get("/api/v1/runs/" + run)).getResponse().getStatus()).isEqualTo(404);
		for (String action : List.of("pause", "resume", "cancel", "steps/" + UUID.randomUUID() + "/retry")) {
			assertThat(perform(bob, post("/api/v1/runs/" + run + "/" + action)).getResponse().getStatus()).as(action)
					.isEqualTo(404);
		}
		assertThat(perform(alice, get("/api/v1/runs/" + UUID.randomUUID())).getResponse().getStatus()).isEqualTo(404);
		assertThat(status(run)).isNotEqualTo(RunStatus.CANCELLED);
	}

	@Test
	@DisplayName("T-09-10 POST /runs twice with the same Idempotency-Key → one run, one PLAN step, same response")
	void idempotentCreate() throws Exception {
		String key = UUID.randomUUID().toString();
		String body = "{\"topic\":\"Compare lost-and-found apps for college campuses\",\"sources\":[\"WEB\"]}";
		var first = perform(alice, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
				.content(body));
		var second = perform(alice, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
				.content(body));

		assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(202);
		assertThat(second.getResponse().getStatus()).isEqualTo(202);
		String id = jsonOf(first).path("id").asString();
		assertThat(jsonOf(second).path("id").asString()).isEqualTo(id);
		assertThat(second.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"))
				.isEqualTo("/api/v1/runs/" + id);
		assertThat(jdbc.sql("SELECT count(*) FROM research_run WHERE owner_id = :o AND idempotency_key = :k")
				.param("o", alice).param("k", key).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM research_step WHERE run_id = :r AND kind = 'PLAN'")
				.param("r", UUID.fromString(id)).query(Long.class).single()).isEqualTo(1);
		assertThat(jsonOf(first).path("config").path("sources").get(0).asString()).isEqualTo("WEB");

		// the same key from another user is a different run (keys are per user)
		var bobs = perform(bob, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
				.content(body));
		assertThat(jsonOf(bobs).path("id").asString()).isNotEqualTo(id);
	}

	@Test
	@DisplayName("runs: 401 without a session, 400 for a too-short topic, GET returns the run with an ETag")
	void authValidationAndRead() throws Exception {
		assertThat(mvc.perform(get("/api/v1/runs/" + UUID.randomUUID())).andReturn().getResponse().getStatus())
				.isEqualTo(401);
		var shortTopic = perform(alice, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
				.content("{\"topic\":\"too short\"}"));
		assertThat(shortTopic.getResponse().getStatus()).isEqualTo(400);
		assertThat(jsonOf(shortTopic).path("code").asString()).isEqualTo("VALIDATION_FAILED");

		UUID run = startRun(alice);
		var read = perform(alice, get("/api/v1/runs/" + run));
		assertThat(read.getResponse().getStatus()).isEqualTo(200);
		assertThat(read.getResponse().getHeader("ETag")).matches("\"\\d+\"");
		assertThat(jsonOf(read).path("topic").asString()).isEqualTo("Compare lost-and-found apps for college campuses");
		assertThat(jsonOf(read).path("steps").get(0).path("kind").asString()).isEqualTo("PLAN");
	}

	@Test
	@DisplayName("T-09-20 Idempotency-Key must be a UUID (400) and is remembered for 24 h — after that the same key "
			+ "starts a new run (REV-P09-10)")
	void idempotencyKeyRules() throws Exception {
		String body = "{\"topic\":\"Compare lost-and-found apps for college campuses\"}";
		for (String bad : List.of("my-key-1", "1-1-1-1-1")) { // UUID.fromString would accept the second one
			var notUuid = perform(alice, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
					.header("Idempotency-Key", bad).content(body));
			assertThat(notUuid.getResponse().getStatus()).as(bad).isEqualTo(400);
			assertThat(jsonOf(notUuid).path("code").asString()).isEqualTo("VALIDATION_FAILED");
		}

		String key = UUID.randomUUID().toString();
		var first = perform(alice, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", key).content(body));
		String firstId = jsonOf(first).path("id").asString();
		jdbc.sql("UPDATE research_run SET created_at = now() - interval '25 hours' WHERE id = :id")
				.param("id", UUID.fromString(firstId)).update();
		var later = perform(alice, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", key).content(body));
		assertThat(later.getResponse().getStatus()).isEqualTo(202);
		assertThat(jsonOf(later).path("id").asString()).isNotEqualTo(firstId);
	}

	@Test
	@DisplayName("T-09-21 topics are checked as the DB counts them: padded or emoji-only short topics → 400, never 500 "
			+ "(CHK-P09-03)")
	void topicLengthInCharacters() throws Exception {
		for (String topic : List.of("x         ", "\\uD83D\\uDE00\\uD83D\\uDE00\\uD83D\\uDE00\\uD83D\\uDE00\\uD83D\\uDE00\\uD83D\\uDE00")) {
			var res = perform(alice, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
					.content("{\"topic\":\"" + topic + "\"}"));
			assertThat(res.getResponse().getStatus()).as(topic).isEqualTo(400);
			assertThat(jsonOf(res).path("code").asString()).isEqualTo("VALIDATION_FAILED");
		}
		// ten emoji are ten characters: allowed
		var ok = perform(alice, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
				.content("{\"topic\":\"" + "\\uD83D\\uDE00".repeat(10) + "\"}"));
		assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(202);
	}

	List<Long> eventIds(UUID run) {
		return jdbc.sql("SELECT id FROM run_event WHERE run_id = :r ORDER BY id").param("r", run).query(Long.class).list();
	}

	static List<Long> sentIds(MvcResult stream) {
		Matcher m = SSE_ID.matcher(body(stream));
		List<Long> out = new ArrayList<>();
		while (m.find()) {
			out.add(Long.parseLong(m.group(1)));
		}
		return out;
	}

}
