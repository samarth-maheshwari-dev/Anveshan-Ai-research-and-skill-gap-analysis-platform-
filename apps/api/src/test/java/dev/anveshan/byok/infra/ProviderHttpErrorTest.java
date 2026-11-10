package dev.anveshan.byok.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import dev.anveshan.byok.app.ProviderModels.ProviderAuthException;
import dev.anveshan.byok.app.ProviderModels.ProviderCallException;
import dev.anveshan.byok.app.ProviderModels.ProviderException;
import dev.anveshan.byok.app.ProviderModels.ProviderModelNotFoundException;
import dev.anveshan.byok.app.ProviderModels.ProviderRateLimitException;

/** How provider HTTP errors are classified (P08 key-test rework). Bodies are read, never repeated. */
class ProviderHttpErrorTest {

	final ProviderHttp http = new ProviderHttp(null, null,
			Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC));

	ProviderException error(int status, String body) {
		return http.error(new ProviderHttp.Reply(status, new HttpHeaders(), body, 7));
	}

	@Test
	@DisplayName("T-08-13 wrong-key answers → auth error 'Key invalid': Gemini 400 API_KEY_INVALID, 401, 403; 402 → no credit")
	void invalidKey() {
		String gemini = "[{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\","
				+ "\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}]";
		for (var e : new ProviderException[] { error(400, gemini), error(401, "{}"), error(403, "{}"),
				error(400, "{\"error\":{\"code\":\"invalid_api_key\"}}") }) {
			assertThat(e).isInstanceOf(ProviderAuthException.class);
			assertThat(e.code()).isEqualTo("AUTH_ERROR");
			assertThat(e.getMessage()).isEqualTo("Key invalid");
		}
		assertThat(error(402, "{}").code()).isEqualTo("PAYMENT_REQUIRED");
	}

	@Test
	@DisplayName("T-08-15 model-not-found only on explicit signals (404, model_not_found, 'not a valid model', 'unexpected model name')")
	void modelNotFoundIsNarrow() {
		assertThat(error(404, "")).isInstanceOf(ProviderModelNotFoundException.class);
		assertThat(error(400, "{\"error\":{\"code\":\"model_not_found\"}}")).isInstanceOf(ProviderModelNotFoundException.class);
		assertThat(error(400, "{\"error\":{\"message\":\"foo/bar is not a valid model ID\"}}"))
				.isInstanceOf(ProviderModelNotFoundException.class);
		assertThat(error(400, "* GenerateContentRequest.model: unexpected model name format"))
				.isInstanceOf(ProviderModelNotFoundException.class);
		assertThat(error(400, "The model `x` does not exist or you do not have access to it."))
				.isInstanceOf(ProviderModelNotFoundException.class);
	}

	@Test
	@DisplayName("T-08-23 OpenAI 403 model_not_found and Groq 400 model_decommissioned → model not found (key is fine)")
	void restrictedOrRetiredModel() {
		var openAi = error(403, "{\"error\":{\"message\":\"Project proj_x does not have access to model gpt-x\","
				+ "\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}");
		var groq = error(400, "{\"error\":{\"message\":\"The model `llama-old` has been decommissioned and is no longer "
				+ "supported.\",\"type\":\"invalid_request_error\",\"code\":\"model_decommissioned\"}}");
		assertThat(openAi).isInstanceOf(ProviderModelNotFoundException.class);
		assertThat(groq).isInstanceOf(ProviderModelNotFoundException.class);
		assertThat(error(403, "{\"error\":{\"message\":\"Forbidden\"}}")).as("a plain 403 is still a key problem")
				.isInstanceOf(ProviderAuthException.class);
	}

	@Test
	@DisplayName("T-08-15 a 400 that only mentions a model is a request error, not a missing model; 5xx = unavailable; 429 = rate limit")
	void otherErrors() {
		var param = error(400, "Unsupported parameter: 'max_tokens' is not supported with this model. "
				+ "Use 'max_completion_tokens' instead.");
		var temperature = error(400, "Unsupported value: 'temperature' does not support 0 with this model.");
		assertThat(param).isInstanceOf(ProviderCallException.class);
		assertThat(param.code()).isEqualTo("PROVIDER_ERROR");
		assertThat(param.getMessage()).isEqualTo("The provider returned an error (HTTP 400)").doesNotContain("max_tokens");
		assertThat(temperature.code()).isEqualTo("PROVIDER_ERROR");
		assertThat(error(503, "{}").code()).isEqualTo("PROVIDER_UNAVAILABLE");
		assertThat(error(500, "model not found")).as("only a 400/404 can mean 'no such model'")
				.isInstanceOf(ProviderCallException.class);
		assertThat(error(429, "")).isInstanceOf(ProviderRateLimitException.class);
	}

}
