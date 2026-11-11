package dev.anveshan.byok.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import dev.anveshan.byok.api.CallContext;
import dev.anveshan.byok.api.InvalidLlmOutputException;
import dev.anveshan.byok.api.LlmGateway;
import dev.anveshan.byok.api.LlmResult;
import dev.anveshan.byok.api.Prompt;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.byok.app.ProviderModels.ChatMessage;
import dev.anveshan.byok.app.ProviderModels.CompletionOptions;
import dev.anveshan.byok.domain.ByokEnums.LlmCallStatus;
import dev.anveshan.byok.infra.LlmCallLog;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Structured output with validation retry (TECHSPEC §10.4): schema in the system message, strict
 * parse into the output record, Bean Validation, max 3 attempts, each attempt logged in
 * {@code llm_call}. LLM output is untrusted until it passes all of that (RULES §0 rule 9).
 */
@Service
class DefaultLlmGateway implements LlmGateway {

	static final int MAX_ATTEMPTS = 3;
	private static final Pattern FENCE = Pattern.compile("^```(?:json|JSON)?\\s*\\n?(.*?)\\n?```\\s*$", Pattern.DOTALL);

	private final QuotaAwareRouter router;
	private final KeyVault vault;
	private final JsonSchemas schemas;
	private final Validator validator;
	private final LlmCallLog callLog;
	private final JsonMapper strict;

	DefaultLlmGateway(QuotaAwareRouter router, KeyVault vault, JsonSchemas schemas, Validator validator,
			LlmCallLog callLog, JsonMapper json) {
		this.router = router;
		this.vault = vault;
		this.schemas = schemas;
		this.validator = validator;
		this.callLog = callLog;
		this.strict = json.rebuild()
				.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
				.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.build();
	}

	@Override
	public <T> LlmResult<T> call(UUID ownerId, TaskSlot slot, Prompt prompt, Class<T> outputType,
			CallContext context) {
		List<ChatMessage> messages = new ArrayList<>();
		messages.add(ChatMessage.system(prompt.system() + "\n\nRespond with ONLY a JSON value (no prose, no Markdown) "
				+ "that matches this JSON Schema:\n" + schemas.schemaFor(outputType)));
		messages.add(ChatMessage.user(prompt.user()));
		var options = new CompletionOptions(prompt.temperature(), prompt.maxTokens(), true);

		String lastProblem = "no attempt";
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			List<ChatMessage> snapshot = List.copyOf(messages);
			var routed = router.call(ownerId, slot, context,
					route -> vault.transportFor(route.connection().preset()).complete(route, snapshot, options));
			var raw = routed.completion();
			var route = routed.route();

			Optional<T> parsed = parse(raw.text(), outputType);
			List<String> problems = parsed.map(this::violations).orElse(List.of("Output is not valid JSON for the schema"));
			if (problems.isEmpty()) {
				callLog.record(ownerId, slot, route.connection().id(), route.model(), raw.latencyMs(), LlmCallStatus.OK,
						raw.tokensIn(), raw.tokensOut(), null, context);
				return new LlmResult<>(parsed.get(), route.connection().id(), route.model(), raw.tokensIn(),
						raw.tokensOut(), attempt);
			}
			lastProblem = String.join("; ", problems);
			callLog.record(ownerId, slot, route.connection().id(), route.model(), raw.latencyMs(),
					LlmCallStatus.INVALID_OUTPUT, raw.tokensIn(), raw.tokensOut(), "INVALID_OUTPUT", context);
			messages.add(ChatMessage.assistant(truncate(raw.text(), 4000)));
			messages.add(ChatMessage.user("Your output was invalid: " + lastProblem
					+ ". Return only the corrected JSON, nothing else."));
		}
		throw new InvalidLlmOutputException(slot, lastProblem);
	}

	<T> Optional<T> parse(String text, Class<T> type) {
		if (text == null) {
			return Optional.empty();
		}
		String s = text.strip();
		Matcher m = FENCE.matcher(s);
		if (m.matches()) {
			s = m.group(1).strip();
		}
		try {
			return Optional.ofNullable(strict.readValue(s, type));
		}
		catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	private <T> List<String> violations(T value) {
		Set<ConstraintViolation<T>> v = validator.validate(value);
		return v.stream().map(c -> c.getPropertyPath() + " " + c.getMessage()).sorted().toList();
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

}
