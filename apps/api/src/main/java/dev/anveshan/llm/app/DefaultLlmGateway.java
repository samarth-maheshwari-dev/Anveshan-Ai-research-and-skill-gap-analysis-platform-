package dev.anveshan.llm.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import dev.anveshan.llm.api.CallContext;
import dev.anveshan.llm.api.InvalidLlmOutputException;
import dev.anveshan.llm.api.LlmGateway;
import dev.anveshan.llm.api.LlmResult;
import dev.anveshan.llm.api.Prompt;
import dev.anveshan.llm.api.TaskSlot;
import dev.anveshan.llm.app.ProviderModels.ChatMessage;
import dev.anveshan.llm.app.ProviderModels.CompletionOptions;
import dev.anveshan.llm.domain.LlmEnums.LlmCallStatus;
import dev.anveshan.llm.infra.LlmCallLog;
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

	private final GatewayChain chain;
	private final JsonSchemas schemas;
	private final Validator validator;
	private final LlmCallLog callLog;
	private final JsonMapper strict;

	DefaultLlmGateway(GatewayChain chain, JsonSchemas schemas, Validator validator,
			LlmCallLog callLog, JsonMapper json) {
		this.chain = chain;
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
		Set<String> gaveInvalid = new HashSet<>();
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			List<ChatMessage> snapshot = List.copyOf(messages);
			var routed = chain.call(ownerId, slot, context, snapshot, options, Set.copyOf(gaveInvalid));
			var raw = routed.completion();

			Optional<T> parsed = parse(raw.text(), outputType);
			List<String> problems = parsed.map(this::violations).orElse(List.of("Output is not valid JSON for the schema"));
			if (problems.isEmpty()) {
				callLog.record(ownerId, slot, routed.endpoint(), routed.model(), raw.latencyMs(), LlmCallStatus.OK,
						raw.tokensIn(), raw.tokensOut(), null, context);
				return new LlmResult<>(parsed.get(), routed.endpoint(), routed.model(), raw.tokensIn(),
						raw.tokensOut(), attempt);
			}
			lastProblem = String.join("; ", problems);
			gaveInvalid.add(routed.endpoint()); // next attempt asks a different model first
			callLog.record(ownerId, slot, routed.endpoint(), routed.model(), raw.latencyMs(),
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
