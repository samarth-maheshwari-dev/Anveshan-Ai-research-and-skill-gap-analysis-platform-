package dev.anveshan.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import dev.anveshan.TestcontainersConfig;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "management.server.port=0", "anveshan.login-rate-per-minute=3" })
@Import(TestcontainersConfig.class)
class LoginRateLimitIT {

	@LocalServerPort
	int port;

	@Test
	@DisplayName("T-05-11 login routes are rate-limited per IP → 429 + Retry-After + Problem Details (TECHSPEC §5.9)")
	void loginRoutesLimited() {
		var b = new Browser("http://localhost:" + port);
		for (int i = 0; i < 3; i++) {
			assertThat(b.get("/oauth2/authorization/github").statusCode()).isEqualTo(302);
		}

		var limited = b.get("/oauth2/authorization/github");

		assertThat(limited.statusCode()).isEqualTo(429);
		assertThat(limited.headers().firstValue("Retry-After")).hasValueSatisfying(v -> assertThat(Integer.parseInt(v)).isPositive());
		assertThat(limited.body()).contains("\"code\":\"RATE_LIMITED\"");
		assertThat(b.get("/api/v1/health").statusCode()).as("other routes unaffected").isEqualTo(200);
	}

}
