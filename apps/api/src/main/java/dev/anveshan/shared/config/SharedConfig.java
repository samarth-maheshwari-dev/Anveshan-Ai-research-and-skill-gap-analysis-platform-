package dev.anveshan.shared.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties.class)
class SharedConfig {

	/** Inject this instead of calling Instant.now() so tests can fix time (RULES §12). */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
