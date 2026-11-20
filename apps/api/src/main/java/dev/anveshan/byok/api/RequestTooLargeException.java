package dev.anveshan.byok.api;

/**
 * Every route that answered refused the request as too large (model context or the key's per-minute token budget).
 * Retrying the same request can't help; the caller should send less (e.g. a smaller batch). Thrown even when other
 * routes are only rate-limited, so a run never waits forever for a request that can't fit (I-017).
 */
public class RequestTooLargeException extends ProviderUnavailableException {

	public RequestTooLargeException(String message) {
		super(message);
	}

}
