package dev.anveshan.llm.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.llm.FakeGateway;
import dev.anveshan.llm.FakeGateway.Reply;
import dev.anveshan.llm.api.AllRoutesExhaustedException;
import dev.anveshan.llm.api.CallContext;
import dev.anveshan.llm.api.NoModelForSlotException;
import dev.anveshan.llm.api.TaskSlot;
import dev.anveshan.llm.app.ProviderModels.ChatMessage;
import dev.anveshan.llm.app.ProviderModels.CompletionOptions;
import dev.anveshan.llm.domain.LlmEnums.LlmCallStatus;
import dev.anveshan.llm.infra.LlmCallLog;
import dev.anveshan.llm.infra.LlmProperties;
import dev.anveshan.llm.infra.LlmProperties.Endpoint;
import dev.anveshan.llm.infra.TestTransports;

/** The developer-configured endpoint chain against a fake gateway server (no real providers, fixed clock). */
class GatewayChainTest {

	static final UUID OWNER = UUID.randomUUID();
	static final List<ChatMessage> MESSAGES = List.of(ChatMessage.user("hi"));
	static final CompletionOptions OPTIONS = new CompletionOptions(0.2, 100, true);

	final FakeGateway fake = new FakeGateway();
	final MutableClock clock = new MutableClock(Instant.parse("2026-11-21T10:00:00Z"));
	final LlmCallLog callLog = mock(LlmCallLog.class);

	@AfterEach
	void stop() {
		fake.close();
	}

	GatewayChain chain(Duration timeout, Endpoint... endpoints) {
		var props = new LlmProperties(List.of(endpoints), 5, timeout, new LlmProperties.Breaker(3, Duration.ofSeconds(60)),
				null);
		return new GatewayChain(props, TestTransports.openAi(props, clock), callLog, clock);
	}

	GatewayChain chain(Endpoint... endpoints) {
		return chain(Duration.ofSeconds(5), endpoints);
	}

	Endpoint endpoint(String name, String key, String model) {
		return new Endpoint(name, URI.create(fake.baseUrl(name)), key, model, Map.of(), false);
	}

	GatewayChain.Routed call(GatewayChain c) {
		return c.call(OWNER, TaskSlot.PLAN, CallContext.NONE, MESSAGES, OPTIONS);
	}

	@Test
	@DisplayName("T-16-01 freellmapi answers 429 → the chain falls to gemini; both attempts logged by endpoint name, key never sent to the wrong one")
	void fallsToGeminiOn429() {
		fake.reply("freellmapi", Reply.rateLimited(30));
		fake.reply("gemini", Reply.chat("{\"ok\":true}"));
		var c = chain(endpoint("freellmapi", "free-key", "auto"), endpoint("gemini", "gem-key", "gemini-flash-latest"));

		var routed = call(c);

		assertThat(routed.endpoint()).isEqualTo("gemini");
		assertThat(routed.model()).isEqualTo("gemini-flash-latest");
		assertThat(routed.completion().text()).isEqualTo("{\"ok\":true}");
		assertThat(fake.requestsTo("freellmapi")).hasSize(1);
		assertThat(fake.requestsTo("freellmapi").getFirst().body()).contains("\"model\":\"auto\"");
		assertThat(fake.requestsTo("gemini").getFirst().headers().get("authorization")).isEqualTo("Bearer gem-key");
		verify(callLog).record(eq(OWNER), eq(TaskSlot.PLAN), eq("freellmapi"), eq("auto"), anyLong(),
				eq(LlmCallStatus.RATE_LIMITED), isNull(), isNull(), eq("RATE_LIMITED"), eq(CallContext.NONE));
	}

	@Test
	@DisplayName("T-16-02 an endpoint with a blank key is skipped: never called, not counted as configured")
	void blankKeySkipped() {
		fake.reply("gemini", Reply.chat("x"));
		var c = chain(endpoint("freellmapi", "", "auto"), endpoint("gemini", "gem-key", "gemini-flash-latest"));

		assertThat(call(c).endpoint()).isEqualTo("gemini");
		assertThat(fake.requestsTo("freellmapi")).isEmpty();
		assertThat(chain(endpoint("freellmapi", " ", "auto")).anyConfigured()).isFalse();
		assertThatThrownBy(() -> call(chain(endpoint("freellmapi", "", "auto"))))
				.isInstanceOf(NoModelForSlotException.class);
	}

	@Test
	@DisplayName("T-16-03 breaker: 3 failures in a row → endpoint skipped for 60 s, then tried again (and closes on success)")
	void breakerOpensAndRecovers() {
		fake.reply("freellmapi", Reply.status(503));
		fake.reply("gemini", Reply.chat("x"));
		var c = chain(endpoint("freellmapi", "free-key", "auto"), endpoint("gemini", "gem-key", "gemini-flash-latest"));

		for (int i = 0; i < 3; i++) {
			assertThat(call(c).endpoint()).isEqualTo("gemini");
		}
		assertThat(fake.requestsTo("freellmapi")).hasSize(3);

		clock.advance(Duration.ofSeconds(59));
		call(c);
		assertThat(fake.requestsTo("freellmapi")).as("open: skipped").hasSize(3);

		clock.advance(Duration.ofSeconds(1));
		fake.reply("freellmapi", Reply.chat("back"));
		assertThat(call(c).endpoint()).as("after 60 s it is tried again").isEqualTo("freellmapi");
		assertThat(fake.requestsTo("freellmapi")).hasSize(4);
	}

	@Test
	@DisplayName("T-16-04 every endpoint fails (5xx, 429, timeout) → AllRoutesExhaustedException with the earliest resume time; BUSY once all breakers are open")
	void allFail() {
		fake.reply("freellmapi", Reply.status(500));
		fake.reply("gemini", Reply.rateLimited(30));
		fake.reply("slow", Reply.slow(1500));
		var c = chain(Duration.ofMillis(300), endpoint("freellmapi", "k1", "auto"),
				endpoint("gemini", "k2", "gemini-flash-latest"), endpoint("slow", "k3", "m"));

		assertThatThrownBy(() -> call(c)).isInstanceOfSatisfying(AllRoutesExhaustedException.class,
				e -> assertThat(e.resumeAt()).isEqualTo(clock.instant().plusSeconds(30)));
		verify(callLog).record(eq(OWNER), eq(TaskSlot.PLAN), eq("slow"), eq("m"), anyLong(), eq(LlmCallStatus.ERROR),
				isNull(), isNull(), eq("TIMEOUT"), any());
		assertThat(c.allOpen()).isFalse();

		assertThatThrownBy(() -> call(c)).isInstanceOf(AllRoutesExhaustedException.class);
		assertThatThrownBy(() -> call(c)).isInstanceOf(AllRoutesExhaustedException.class);
		assertThat(c.allOpen()).isTrue();
		assertThatThrownBy(() -> call(c)).isInstanceOfSatisfying(AllRoutesExhaustedException.class,
				e -> assertThat(e.resumeAt()).as("every breaker open: resume when the first closes")
						.isEqualTo(clock.instant().plusSeconds(60)));
	}

	@Test
	@DisplayName("T-16-05 keys never leak: endpoint config prints ****, and error messages carry no provider body")
	void keysNeverLeak() {
		fake.reply("freellmapi", Reply.status(500));
		var e = endpoint("freellmapi", "sk-secret", "auto");
		assertThat(e.toString()).doesNotContain("sk-secret").contains("****");
		assertThatThrownBy(() -> call(chain(e))).isInstanceOf(AllRoutesExhaustedException.class)
				.message().doesNotContain("sk-secret");
	}

	@Test
	@DisplayName("T-16-06 per-slot model override wins over the endpoint's default model")
	void perSlotModel() {
		fake.reply("gemini", Reply.chat("x"));
		var e = new Endpoint("gemini", URI.create(fake.baseUrl("gemini")), "k", "gemini-flash-latest",
				Map.of(TaskSlot.PLAN, "gemini-2.5-pro"), true);
		assertThat(call(chain(e)).model()).isEqualTo("gemini-2.5-pro");
		assertThat(fake.requestsTo("gemini").getFirst().body()).contains("\"response_format\"");
	}

	static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration d) {
			now = now.plus(d);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
