package dev.anveshan;

import org.springframework.boot.SpringApplication;

/** Runs the API locally against a throwaway Postgres container: {@code ./mvnw spring-boot:test-run}. */
public class TestApplication {

	public static void main(String[] args) {
		SpringApplication.from(Application::main).with(TestcontainersConfig.class).run(args);
	}

}
