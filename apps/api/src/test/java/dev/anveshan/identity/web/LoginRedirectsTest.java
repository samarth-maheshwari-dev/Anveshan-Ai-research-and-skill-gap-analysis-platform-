package dev.anveshan.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;

class LoginRedirectsTest {

	LoginRedirects redirects = new LoginRedirects("http://localhost:3000");

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "https://evil.example", "//evil.example", "/\\evil.example", "http:/evil.example",
			"javascript:alert(1)", "/dashboard/../../evil", "/unknown", "/dashboard?x=https://evil.example",
			"/dashboard#frag", "/reports/%2F%2Fevil", "", " /dashboard", "/w/@evil.example" })
	@DisplayName("T-05-08 unsafe or unknown ?next values fall back to /dashboard")
	void rejectsUnsafe(String next) {
		assertThat(redirects.target(next)).isEqualTo("http://localhost:3000/dashboard");
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "/dashboard", "/reports/5b1e-42", "/skills", "/settings/connections", "/w/abc/reports/x" })
	@DisplayName("T-05-08 allow-listed app paths are kept, always on our own origin")
	void keepsSafe(String next) {
		assertThat(redirects.target(next)).isEqualTo("http://localhost:3000" + next);
	}

}
