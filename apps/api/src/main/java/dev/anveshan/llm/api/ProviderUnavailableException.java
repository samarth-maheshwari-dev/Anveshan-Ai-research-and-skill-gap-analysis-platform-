package dev.anveshan.llm.api;

/** Every route failed for a reason other than rate limits (bad keys, provider errors). HTTP 503. */
public class ProviderUnavailableException extends RuntimeException {

	public ProviderUnavailableException(String message) {
		super(message);
	}

}
