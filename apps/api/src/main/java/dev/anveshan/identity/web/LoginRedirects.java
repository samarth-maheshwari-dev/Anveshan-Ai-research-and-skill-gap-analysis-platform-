package dev.anveshan.identity.web;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Where the browser goes after login (TECHSPEC §5.3). Only paths on our own frontend that match a
 * fixed allow-list are accepted from {@code ?next=}; anything else lands on {@code /dashboard}.
 * The target is always {@code appOrigin + path}, so an off-site redirect is impossible.
 */
final class LoginRedirects implements AuthenticationSuccessHandler {

	static final String DEFAULT_PATH = "/dashboard";
	static final String SESSION_ATTR = "anveshan.login.next";

	/** First segment from the app's route list (PRD §16); later segments: plain URL-safe chars only. */
	private static final Pattern SAFE_PATH = Pattern
			.compile("^/(dashboard|research|reports|sources|knowledge|graph|skills|w|settings)(/[A-Za-z0-9._~-]+)*/?$");

	private final String appOrigin;

	LoginRedirects(String appOrigin) {
		this.appOrigin = appOrigin;
	}

	static Optional<String> safeNext(String next) {
		if (next == null || next.length() > 200 || !SAFE_PATH.matcher(next).matches()) {
			return Optional.empty();
		}
		for (String segment : next.split("/")) {
			if (segment.equals(".") || segment.equals("..")) {
				return Optional.empty(); // "/dashboard/../x" would escape the allow-list
			}
		}
		return Optional.of(next);
	}

	String target(String next) {
		return appOrigin + safeNext(next).orElse(DEFAULT_PATH);
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		HttpSession session = request.getSession(false);
		String next = null;
		if (session != null) {
			next = (String) session.getAttribute(SESSION_ATTR);
			session.removeAttribute(SESSION_ATTR);
		}
		response.sendRedirect(target(next));
	}

	/** Remembers a safe {@code ?next=} in the session when the login starts. */
	static OAuth2AuthorizationRequestResolver capturingResolver(ClientRegistrationRepository registrations) {
		var delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
		return new OAuth2AuthorizationRequestResolver() {

			@Override
			public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
				return capture(request, delegate.resolve(request));
			}

			@Override
			public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
				return capture(request, delegate.resolve(request, registrationId));
			}

			private OAuth2AuthorizationRequest capture(HttpServletRequest request, OAuth2AuthorizationRequest auth) {
				if (auth != null) {
					var safe = safeNext(request.getParameter("next"));
					HttpSession session = request.getSession();
					if (safe.isPresent()) {
						session.setAttribute(SESSION_ATTR, safe.get());
					}
					else {
						session.removeAttribute(SESSION_ATTR);
					}
				}
				return auth;
			}
		};
	}

}
