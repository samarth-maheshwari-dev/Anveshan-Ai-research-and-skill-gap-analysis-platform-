package dev.anveshan.identity.web;

import java.io.IOException;
import java.util.List;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import dev.anveshan.identity.app.AccountService;
import dev.anveshan.identity.infra.security.GitHubOAuth2UserService;
import dev.anveshan.identity.infra.security.GoogleOidcUserService;
import dev.anveshan.identity.infra.security.NoStoreAuthorizedClientRepository;
import dev.anveshan.shared.config.AppProperties;
import dev.anveshan.shared.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Browser login + session security (TECHSPEC §5.5–5.6). The MCP bearer-token chain is added in P27.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

	@Bean
	SecurityFilterChain appChain(HttpSecurity http, AppProperties props, AccountService accounts,
			ClientRegistrationRepository registrations) throws Exception {

		var csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse(); // the SPA must read XSRF-TOKEN
		csrfRepo.setCookieCustomizer(c -> {
			c.path("/").secure(props.cookieSecure()).sameSite("Lax");
			if (StringUtils.hasText(props.cookieDomain())) {
				c.domain(props.cookieDomain());
			}
		});
		var redirects = new LoginRedirects(props.appOrigin());

		http
			.cors(Customizer.withDefaults())
			.csrf(c -> c.spa().csrfTokenRepository(csrfRepo))
			.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
			.addFilterBefore(new LoginRateLimitFilter(props.loginRatePerMinute()),
					OAuth2AuthorizationRequestRedirectFilter.class)
			.authorizeHttpRequests(a -> a
				.requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll() // only on internal port 8081
				.requestMatchers("/oauth2/**", "/login/**", "/error").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/templates").permitAll() // public data (TECHSPEC §5.2)
				.anyRequest().authenticated())
			.oauth2Login(o -> o
				.loginPage(props.appOrigin() + "/login") // no server-rendered login page
				.authorizationEndpoint(e -> e.authorizationRequestResolver(LoginRedirects.capturingResolver(registrations)))
				.authorizedClientRepository(new NoStoreAuthorizedClientRepository())
				.userInfoEndpoint(u -> u
					.oidcUserService(new GoogleOidcUserService(accounts))
					.userService(new GitHubOAuth2UserService(accounts, props.githubApiBase())))
				.successHandler(redirects)
				.failureHandler((req, res, ex) -> res.sendRedirect(props.appOrigin() + "/login?error=1")))
			.requestCache(r -> r.disable())
			.exceptionHandling(e -> e
				.authenticationEntryPoint((req, res, ex) -> ProblemResponses.write(req, res, HttpStatus.UNAUTHORIZED,
						"NOT_AUTHENTICATED", "Please sign in", "You need to be signed in to do this."))
				.accessDeniedHandler((req, res, ex) -> ProblemResponses.write(req, res, HttpStatus.FORBIDDEN,
						"FORBIDDEN", "Not allowed", "This request was refused. Refresh the page and try again.")))
			.logout(l -> l
				.logoutUrl("/api/v1/auth/logout")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
				.invalidateHttpSession(true)
				.deleteCookies("ANV_SESSION"));
		return http.build();
	}

	/** Only the frontend origin, with credentials; never "*" (TECHSPEC §5.6). */
	@Bean
	CorsConfigurationSource corsConfigurationSource(AppProperties props) {
		var c = new CorsConfiguration();
		c.setAllowedOrigins(List.of(props.appOrigin()));
		c.setAllowCredentials(true);
		c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
		c.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key", "If-Match", "Last-Event-ID"));
		c.setExposedHeaders(List.of("ETag", "Location", "Retry-After"));
		c.setMaxAge(3600L);
		var source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", c);
		return source;
	}

	/** Makes sure the XSRF-TOKEN cookie is written on every response (e.g. the first GET /me). */
	static final class CsrfCookieFilter extends OncePerRequestFilter {

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
				token.getToken();
			}
			chain.doFilter(request, response);
		}

	}

}
