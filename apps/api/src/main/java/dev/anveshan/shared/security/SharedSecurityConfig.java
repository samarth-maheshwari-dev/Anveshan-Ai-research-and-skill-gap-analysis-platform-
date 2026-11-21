package dev.anveshan.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

@Configuration(proxyBeanMethods = false)
class SharedSecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SharedSecurityConfig.class);

	@Bean
	OutboundUrlGuard outboundUrlGuard(
			@org.springframework.beans.factory.annotation.Value("${anveshan.outbound.allow-loopback:false}") boolean allowLoopback) {
		if (allowLoopback) {
			log.warn("anveshan.outbound.allow-loopback=true: loopback counts as public. Tests only — never in production.");
			return new OutboundUrlGuard(a -> !a.isLoopbackAddress() && OutboundUrlGuard.isBlocked(a));
		}
		return new OutboundUrlGuard();
	}

	@Bean(destroyMethod = "close")
	SafeHttp safeHttp(OutboundUrlGuard guard) {
		return new SafeHttp(guard, new SafeDnsResolver(guard));
	}

	/** For provider API calls (P07): same guarded client, no redirects, no cookies. */
	@Bean
	RestClient safeRestClient(SafeHttp safeHttp) {
		return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(safeHttp.client())).build();
	}

}
