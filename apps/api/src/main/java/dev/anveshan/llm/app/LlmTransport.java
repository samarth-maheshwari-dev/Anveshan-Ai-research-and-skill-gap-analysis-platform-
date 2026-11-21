package dev.anveshan.llm.app;

import java.util.List;

import dev.anveshan.llm.app.ProviderModels.ChatMessage;
import dev.anveshan.llm.app.ProviderModels.CompletionOptions;
import dev.anveshan.llm.app.ProviderModels.RawCompletion;

/** One chat completion on an OpenAI-compatible endpoint. Throws {@code Provider*Exception} on 429 / 5xx / timeouts. */
public interface LlmTransport {

	RawCompletion complete(LlmEndpoint endpoint, String model, List<ChatMessage> messages, CompletionOptions options);

}
