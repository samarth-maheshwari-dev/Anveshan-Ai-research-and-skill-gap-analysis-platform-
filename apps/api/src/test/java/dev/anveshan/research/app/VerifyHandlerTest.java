package dev.anveshan.research.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.byok.api.LlmGateway;
import dev.anveshan.byok.api.LlmResult;
import dev.anveshan.byok.api.Prompt;
import dev.anveshan.byok.api.RequestTooLargeException;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.research.app.StepHandler.StepContext;
import dev.anveshan.research.app.StepHandler.StepResult;
import dev.anveshan.research.app.VerifyHandler.VerdictItem;
import dev.anveshan.research.app.VerifyHandler.VerifyOutput;
import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.infra.PromptFiles;
import dev.anveshan.sources.api.ClaimLibrary;
import dev.anveshan.sources.api.ClaimLibrary.Claim;
import dev.anveshan.sources.api.ClaimLibrary.Passage;
import tools.jackson.databind.json.JsonMapper;

/** The VERIFY step against a model that skips claims (what free reasoning models did on real runs). */
class VerifyHandlerTest {

	static final Pattern CLAIM_LINE = Pattern.compile("^(C\\d+): .*\\n\\s+check against: ([P0-9, ]+)$", Pattern.MULTILINE);

	final LlmGateway gateway = mock(LlmGateway.class);
	final ClaimLibrary claims = mock(ClaimLibrary.class);
	final JsonMapper json = JsonMapper.builder().build();
	final VerifyHandler handler = new VerifyHandler(gateway, new PromptFiles(), claims, json);
	final List<Prompt> prompts = new ArrayList<>();

	final UUID run = UUID.randomUUID();
	final List<Claim> batch = List.of(claim("Students report lost items."), claim("Offices keep items 30 days."),
			claim("Most items are phones."));

	@Test
	@DisplayName("T-15-21 VERIFY: claims the model skipped are asked once more (only those); still missing → counted and logged")
	void asksAgainForSkippedClaims() {
		setUp();
		// 1st call: only C1 is answered. 2nd call (C2, C3 re-numbered C1, C2): only the first is answered.
		when(gateway.call(any(), eq(TaskSlot.VERIFY), any(), eq(VerifyOutput.class), any())).thenAnswer(inv -> {
			Prompt p = inv.getArgument(2);
			prompts.add(p);
			return result(answerFirstOnly(p));
		});

		StepResult r = handler.handle(step());

		assertThat(prompts).hasSize(2);
		assertThat(prompts.get(0).version()).isEqualTo(2);
		assertThat(prompts.get(0).maxTokens()).isEqualTo(VerifyHandler.MAX_TOKENS);
		assertThat(prompts.get(0).system()).contains("6 verdicts in total");
		assertThat(prompts.get(1).user()).as("only the skipped claims are sent again")
				.contains(batch.get(1).text(), batch.get(2).text()).doesNotContain(batch.get(0).text());
		assertThat(prompts.get(1).system()).contains("4 verdicts in total");

		verify(claims, times(2)).saveVerdict(eq(run), eq(batch.get(0).id()), any(), eq("SUPPORTS"), anyString());
		verify(claims, times(2)).saveVerdict(eq(run), eq(batch.get(1).id()), any(), eq("SUPPORTS"), anyString());
		verify(claims, never()).saveVerdict(eq(run), eq(batch.get(2).id()), any(), any(), any());
		assertThat(r.output()).containsEntry("llmCalls", 2).containsEntry("retried", 2).containsEntry("unchecked", 1)
				.containsEntry("verdicts", 4);
		assertThat(messages(r)).anyMatch(m -> m.startsWith("The AI skipped 2 of 3 claims"))
				.anyMatch(m -> m.startsWith("1 claims couldn't be checked"));
	}

	@Test
	@DisplayName("T-15-21 VERIFY: every claim answered → one call, no retry")
	void oneCallWhenComplete() {
		setUp();
		when(gateway.call(any(), eq(TaskSlot.VERIFY), any(), eq(VerifyOutput.class), any())).thenAnswer(inv -> {
			Prompt p = inv.getArgument(2);
			prompts.add(p);
			List<VerdictItem> out = new ArrayList<>();
			Matcher m = CLAIM_LINE.matcher(p.user());
			while (m.find()) {
				for (String pa : m.group(2).split(",")) {
					out.add(new VerdictItem(m.group(1), pa.strip(), "INSUFFICIENT", "Doesn't say."));
				}
			}
			return result(new VerifyOutput(out));
		});

		StepResult r = handler.handle(step());

		assertThat(prompts).hasSize(1);
		assertThat(r.output()).containsEntry("llmCalls", 1).containsEntry("retried", 0).containsEntry("unchecked", 0)
				.containsEntry("verdicts", 6);
	}

	@Test
	@DisplayName("T-15-30 VERIFY: a batch the models refuse as too large is checked in halves; every claim judged, no pause loop")
	void splitsTooLargeBatches() {
		setUp();
		when(gateway.call(any(), eq(TaskSlot.VERIFY), any(), eq(VerifyOutput.class), any())).thenAnswer(inv -> {
			Prompt p = inv.getArgument(2);
			prompts.add(p);
			Matcher m = CLAIM_LINE.matcher(p.user());
			List<VerdictItem> out = new ArrayList<>();
			int claimsInPrompt = 0;
			while (m.find()) {
				claimsInPrompt++;
				for (String pa : m.group(2).split(",")) {
					out.add(new VerdictItem(m.group(1), pa.strip(), "INSUFFICIENT", "Doesn't say."));
				}
			}
			if (claimsInPrompt > 1) {
				throw new RequestTooLargeException("too large");
			}
			return result(new VerifyOutput(out));
		});

		StepResult r = handler.handle(step());

		// 3 claims: [3] too large → [1] ok + [2] too large → [1] ok + [1] ok
		assertThat(prompts).hasSize(5);
		assertThat(r.output()).containsEntry("verdicts", 6).containsEntry("unchecked", 0).containsEntry("llmCalls", 5);
		assertThat(messages(r)).anyMatch(m -> m.startsWith("The request was too large for your AI models — checking 3 claims"));
	}

	// --- helpers ---

	void setUp() {
		when(claims.claims(any())).thenReturn(batch);
		UUID other = UUID.randomUUID();
		for (Claim c : batch) {
			when(claims.verificationPassages(eq(run), eq(c), anyInt())).thenReturn(List.of(
					new Passage(c.chunkId(), c.sourceId(), c.text() + " More text.", "Own source", "HIGH"),
					new Passage(UUID.randomUUID(), other, "Another source about " + c.text(), "Other source", "MEDIUM")));
		}
	}

	/** SUPPORTS for every pair of the first claim listed; nothing for the rest. */
	static VerifyOutput answerFirstOnly(Prompt p) {
		Matcher m = CLAIM_LINE.matcher(p.user());
		List<VerdictItem> out = new ArrayList<>();
		if (m.find()) {
			for (String pa : m.group(2).split(",")) {
				out.add(new VerdictItem(m.group(1), pa.strip(), "SUPPORTS", "States it."));
			}
		}
		return new VerifyOutput(out);
	}

	StepContext step() {
		String input = json.writeValueAsString(java.util.Map.of("claimIds", batch.stream().map(c -> c.id().toString()).toList()));
		return new StepContext(run, UUID.randomUUID(), UUID.randomUUID(), StepKind.VERIFY, 5, 0, null, input, 1);
	}

	static Claim claim(String text) {
		return new Claim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), text, text, 0, text.length());
	}

	static LlmResult<VerifyOutput> result(VerifyOutput out) {
		return new LlmResult<>(out, UUID.randomUUID(), "fake", 10, 10, 1);
	}

	static List<String> messages(StepResult r) {
		return r.events().stream().map(e -> String.valueOf(e.payload().get("message"))).toList();
	}

}
