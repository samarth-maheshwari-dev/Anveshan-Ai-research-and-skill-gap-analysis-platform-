package dev.anveshan.shared.web;

import java.util.Map;

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public liveness check for uptime monitors and deploy smoke tests (TECHSPEC §16.5).
 * Returns only the overall status — never component names or details. The full
 * actuator stays on the internal management port.
 */
@RestController
class PublicHealthController {

	private final HealthEndpoint health;

	PublicHealthController(HealthEndpoint health) {
		this.health = health;
	}

	@GetMapping("/api/v1/health")
	ResponseEntity<Map<String, String>> health() {
		var descriptor = health.health();
		return respond(descriptor == null ? Status.UNKNOWN : descriptor.getStatus());
	}

	static ResponseEntity<Map<String, String>> respond(Status status) {
		boolean up = Status.UP.equals(status);
		return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
				.cacheControl(CacheControl.noStore())
				.body(Map.of("status", up ? "UP" : "DOWN"));
	}

}
