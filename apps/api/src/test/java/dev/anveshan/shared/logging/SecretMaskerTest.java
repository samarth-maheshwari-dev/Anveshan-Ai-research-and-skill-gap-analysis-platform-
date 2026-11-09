package dev.anveshan.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SecretMaskerTest {

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = {
			"calling groq with gsk_abcDEF1234567890xyz | calling groq with gsk_****",
			"key AIzaSyA1234567890abcdefghijklmnopq done | key AIza**** done",
			"openai sk-proj-AbCdEf1234567890 ok | openai sk-proj-**** ok",
			"anthropic sk-ant-api03-AbCdEf123456789 | anthropic sk-ant-****",
			"openrouter sk-or-v1-abcdef0123456789 | openrouter sk-or-v1-****",
			"tavily tvly-ABCdef123456789 | tavily tvly-****",
			"gh token ghp_abcdefghijklmnop1234 | gh token ghp_****",
			"oauth gho_fake_token_value_12345 | oauth gho_****",
			"mcp anv_pat_9f8e7d6c5b4a | mcp anv_pat_****",
			"Authorization: Bearer eyJhbGciOi.payload.sig | Authorization: Bearer ****",
			"google GOCSPX-AbCdEf123456789 | google GOCSPX-****",
			"{\"api_key\":\"plainvalue123\"} | {\"api_key\":\"****\"}",
			"client_secret=abc123def456&x=1 | client_secret=****&x=1",
	})
	@DisplayName("T-06-09 credential patterns are masked, surrounding text kept")
	void masks(String input, String expected) {
		assertThat(SecretMasker.mask(input)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = {
			"tokens_in=100 tokens_out=50 | tokens_in=100 tokens_out=50",
			"task-slot PLAN skipped | task-slot PLAN skipped",
			"user 6f1c4b2e-1111-2222-3333-444455556666 logged in | user 6f1c4b2e-1111-2222-3333-444455556666 logged in",
	})
	@DisplayName("T-06-09 ordinary log text is left alone")
	void leavesNormalText(String input, String expected) {
		assertThat(SecretMasker.mask(input)).isEqualTo(expected);
	}

}
