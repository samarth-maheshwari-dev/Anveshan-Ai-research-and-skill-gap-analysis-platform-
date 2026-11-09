package dev.anveshan.shared.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecretsProperties.class)
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

	@Bean
	SecretBox secretBox(SecretsProperties props) throws GeneralSecurityException, IOException {
		if (props.tinkKeyset() != null && !props.tinkKeyset().isBlank()) {
			return SecretBox.fromKeysetJson(props.tinkKeyset());
		}
		if (props.tinkKeysetFile() != null && Files.isRegularFile(props.tinkKeysetFile())
				&& Files.size(props.tinkKeysetFile()) > 0) {
			return SecretBox.fromKeysetJson(Files.readString(props.tinkKeysetFile(), StandardCharsets.UTF_8));
		}
		if (props.tinkEphemeral()) {
			log.warn("Using an EPHEMERAL encryption key: anything encrypted now is unreadable after restart. Tests/CI only.");
			return SecretBox.ephemeral();
		}
		throw new IllegalStateException("No Tink keyset configured: set ANV_TINK_KEYSET_FILE (or ANV_TINK_KEYSET). "
				+ "For tests/CI only: ANV_TINK_EPHEMERAL=true");
	}

}
