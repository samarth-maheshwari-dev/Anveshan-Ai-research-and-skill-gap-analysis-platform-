package dev.anveshan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class ContextLoadsIT {

	@Test
	@DisplayName("T-01-02 context loads against pgvector/pgvector:pg17 in < 60 s")
	void contextLoadsQuickly() {
		long start = System.nanoTime();
		try (ConfigurableApplicationContext ctx = SpringApplication.from(Application::main)
				.with(TestcontainersConfig.class)
				.run("--server.port=0", "--management.server.port=0")
				.getApplicationContext()) {
			Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

			assertThat(ctx.isRunning()).isTrue();
			assertThat(elapsed).isLessThan(Duration.ofSeconds(60));
		}
	}

}
