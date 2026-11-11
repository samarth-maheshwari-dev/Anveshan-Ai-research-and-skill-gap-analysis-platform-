package dev.anveshan.research.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResearchProperties.class)
@EnableScheduling // stale-step / auto-resume sweep and SSE keep-alive
class ResearchConfig {

	/** Short write transactions around (never during) step work — no network inside a DB tx (RULES §4.2). */
	@Bean
	TransactionTemplate researchTx(PlatformTransactionManager tm) {
		return new TransactionTemplate(tm);
	}

}
