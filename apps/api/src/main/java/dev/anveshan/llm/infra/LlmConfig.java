package dev.anveshan.llm.infra;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
class LlmConfig {

	/**
	 * HTTP for the gateway endpoints and Tavily. Their URLs are developer config (never user input), so this is not
	 * the SSRF-guarded client: a local gateway such as freellmapi on localhost must work. No redirects.
	 */
	static RestClient gatewayClient(LlmProperties props) {
		HttpClient client = HttpClient.newBuilder().connectTimeout(props.timeout())
				.followRedirects(HttpClient.Redirect.NEVER).build();
		var factory = new JdkClientHttpRequestFactory(client);
		factory.setReadTimeout(props.timeout());
		return RestClient.builder().requestFactory(factory).build();
	}

}
