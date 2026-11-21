package dev.anveshan.llm.app;

import java.net.URI;

import dev.anveshan.shared.security.Secret;

/** One gateway endpoint ready for a call: {@code key} prints as ****. Built from config, never from user input. */
public record LlmEndpoint(String name, URI baseUrl, Secret key, boolean jsonMode) {
}
