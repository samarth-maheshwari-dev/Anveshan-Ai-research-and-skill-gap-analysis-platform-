package dev.anveshan.byok.app;

import org.springframework.http.HttpStatus;

import dev.anveshan.byok.app.ProviderModels.ProviderException;
import dev.anveshan.shared.web.ApiException;

/** The words and error shapes a key test ends with — kept apart from the test logic in {@link ConnectionTester}. */
final class TestOutcome {

	static final String RATE_LIMITED_NOTE = "Key is valid but rate-limited right now";

	static final String BUSY_NOTE = "Your key works, but the provider's models are overloaded right now"
			+ " — research will retry them and switch models if needed";

	/** Machine-readable cause of a failed test, sent as the Problem {@code reason}; the UI words it. */
	enum TestFailure {

		KEY_INVALID, NO_CREDIT, KEY_UNREADABLE, MODEL_NOT_FOUND, NO_CHAT_MODEL, PROVIDER_UNAVAILABLE, PROVIDER_ERROR;

		static TestFailure of(ProviderException e) {
			return switch (e.code()) {
				case "AUTH_ERROR" -> KEY_INVALID;
				case "PAYMENT_REQUIRED" -> NO_CREDIT;
				case "MODEL_NOT_FOUND" -> MODEL_NOT_FOUND;
				case "UNREACHABLE", "PROVIDER_UNAVAILABLE" -> PROVIDER_UNAVAILABLE;
				default -> PROVIDER_ERROR;
			};
		}

	}

	/** Why a routed model was skipped during a test. */
	enum Skip {
		GONE, RATE_LIMITED, OVERLOADED
	}

	private TestOutcome() {
	}

	/**
	 * A rate limit or an overload is temporary (research falls back by itself), so it only gets a heads-up;
	 * a model that's gone needs the user to change Task routing.
	 */
	static String skippedNote(String routedModel, String usedModel, Skip why) {
		return switch (why) {
			case RATE_LIMITED -> routedModel + " is rate-limited right now; " + usedModel + " answered";
			case OVERLOADED -> routedModel + " is overloaded right now; " + usedModel + " answered";
			case GONE -> routedModel + " didn't answer, " + usedModel + " did. Research still uses " + routedModel
					+ " — change it in Task routing";
		};
	}

	static ApiException failed(TestFailure reason, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CONNECTION_TEST_FAILED", "Connection test failed",
				detail).withProperty("reason", reason.name());
	}

	/** A provider error from the model list, as the same 422 Problem a test failure gets. */
	static ApiException modelsFailed(ProviderException e) {
		return ApiException.unprocessable("CONNECTION_TEST_FAILED", "Couldn't load models", e.getMessage())
				.withProperty("reason", TestFailure.of(e).name());
	}

}
