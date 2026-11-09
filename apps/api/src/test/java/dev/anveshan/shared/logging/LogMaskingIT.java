package dev.anveshan.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

import dev.anveshan.TestcontainersConfig;

/** Real app logging config (logback-spring.xml) — what actually reaches stdout/docker logs. */
@SpringBootTest(properties = "management.server.port=0")
@Import(TestcontainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class LogMaskingIT {

	private static final Logger log = LoggerFactory.getLogger(LogMaskingIT.class);

	@Test
	@DisplayName("T-06-09 gsk_, AIza, anv_pat_ and Bearer values are masked in messages and stack traces")
	void masked(CapturedOutput output) {
		log.info("provider call key={} other={} mcp={} header=Bearer {}", "gsk_abcDEF1234567890xyz",
				"AIzaSyA1234567890abcdefghijklmnopq", "anv_pat_9f8e7d6c5b4a3210", "eyJhbGciOi.payload.sig");
		log.warn("call failed", new IllegalStateException("401 for key sk-proj-AbCdEf1234567890",
				new RuntimeException("cause had gsk_secretInCause123456")));

		String out = output.getAll();
		assertThat(out).contains("key=gsk_****", "other=AIza****", "mcp=anv_pat_****", "Bearer ****",
				"sk-proj-****", "gsk_****");
		assertThat(out).doesNotContain("gsk_abcDEF1234567890xyz", "AIzaSyA1234567890abcdefghijklmnopq",
				"anv_pat_9f8e7d6c5b4a3210", "eyJhbGciOi.payload.sig", "AbCdEf1234567890", "secretInCause");
		assertThat(out).contains("IllegalStateException", "call failed");
	}

}
