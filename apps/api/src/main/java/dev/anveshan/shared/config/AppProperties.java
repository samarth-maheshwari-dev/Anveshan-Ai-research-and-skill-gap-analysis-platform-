package dev.anveshan.shared.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * App-wide settings (TECHSPEC §17.1).
 *
 * @param appOrigin     frontend origin, e.g. {@code http://localhost:3000}; CORS + post-login redirect
 * @param cookieDomain  parent domain for cookies; empty = host-only (local development)
 * @param cookieSecure  {@code Secure} flag on cookies; true whenever served over HTTPS
 * @param githubApiBase GitHub REST API base (overridable for tests)
 * @param loginRatePerMinute login routes limit per IP (TECHSPEC §5.9)
 */
@ConfigurationProperties("anveshan")
public record AppProperties(
		@DefaultValue("http://localhost:3000") String appOrigin,
		@DefaultValue("") String cookieDomain,
		@DefaultValue("false") boolean cookieSecure,
		@DefaultValue("https://api.github.com") String githubApiBase,
		@DefaultValue("20") int loginRatePerMinute) {

	public AppProperties {
		URI origin = URI.create(appOrigin);
		if (origin.getScheme() == null || origin.getHost() == null || !"".equals(nullToEmpty(origin.getPath()))
				&& !"/".equals(origin.getPath())) {
			throw new IllegalArgumentException("anveshan.app-origin must be scheme://host[:port], got " + appOrigin);
		}
		appOrigin = appOrigin.endsWith("/") ? appOrigin.substring(0, appOrigin.length() - 1) : appOrigin;
		if (loginRatePerMinute < 1) {
			throw new IllegalArgumentException("anveshan.login-rate-per-minute must be >= 1");
		}
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}

}
