package dev.anveshan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestcontainersConfig.class)
class PublicHealthIT {

	@LocalServerPort
	int port;

	@Test
	@DisplayName("T-02-01 GET /api/v1/health on the app port returns 200 and only {\"status\":\"UP\"}")
	void publicHealthIsUpAndMinimal() {
		ResponseEntity<String> res = RestClient.create("http://localhost:" + port)
				.get().uri("/api/v1/health")
				.retrieve()
				.toEntity(String.class);

		assertThat(res.getStatusCode().value()).isEqualTo(200);
		assertThat(res.getBody()).isEqualTo("{\"status\":\"UP\"}");
		assertThat(res.getHeaders().getCacheControl()).contains("no-store");
	}

	@Test
	@DisplayName("T-02-02 actuator is not reachable on the public app port")
	void actuatorNotOnAppPort() {
		var result = RestClient.create("http://localhost:" + port)
				.get().uri("/actuator/health")
				.exchange((req, resp) -> resp.getStatusCode().value() + " " + new String(resp.getBody().readAllBytes()));

		// 401 since P05 (anonymous) or 404 (signed in) — never the health payload
		assertThat(result).matches("^(401|404) .*").doesNotContain("\"UP\"");
	}

}
