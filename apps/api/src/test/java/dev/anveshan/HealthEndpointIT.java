package dev.anveshan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestcontainersConfig.class)
class HealthEndpointIT {

	@LocalManagementPort
	int managementPort;

	@Test
	@DisplayName("T-01-03 GET /actuator/health on the management port returns 200 UP")
	void healthIsUp() {
		ResponseEntity<Map<String, Object>> res = RestClient.create("http://localhost:" + managementPort)
				.get().uri("/actuator/health")
				.retrieve()
				.toEntity(new ParameterizedTypeReference<>() {});

		assertThat(res.getStatusCode().value()).isEqualTo(200);
		assertThat(res.getBody()).containsEntry("status", "UP");
	}

}
