package dev.anveshan.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.health.contributor.Status;

class PublicHealthControllerTest {

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "DOWN", "OUT_OF_SERVICE", "UNKNOWN" })
	@DisplayName("T-02-01 public health returns 503 DOWN (no details) when the app is not UP")
	void downWhenNotUp(String code) {
		var res = PublicHealthController.respond(new Status(code));

		assertThat(res.getStatusCode().value()).isEqualTo(503);
		assertThat(res.getBody()).isEqualTo(Map.of("status", "DOWN"));
	}

}
