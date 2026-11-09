package dev.anveshan.identity.infra.security;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import dev.anveshan.identity.app.AccountService;
import dev.anveshan.identity.app.LoginProfile;
import dev.anveshan.identity.domain.AuthProvider;

/**
 * GitHub (plain OAuth 2.0). The email only counts as verified if GitHub's {@code /user/emails}
 * lists it as both {@code primary} and {@code verified} (TECHSPEC §5.4). The access token is
 * used for these two calls only and then dropped.
 */
public class GitHubOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

	private static final ParameterizedTypeReference<List<Map<String, Object>>> EMAIL_LIST = new ParameterizedTypeReference<>() {
	};

	private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
	private final AccountService accounts;
	private final RestClient github;

	public GitHubOAuth2UserService(AccountService accounts, String githubApiBase) {
		this.accounts = accounts;
		var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		var factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(Duration.ofSeconds(10));
		this.github = RestClient.builder()
				.baseUrl(githubApiBase)
				.requestFactory(factory)
				.defaultHeader("Accept", "application/vnd.github+json")
				.defaultHeader("X-GitHub-Api-Version", "2022-11-28")
				.build();
	}

	@Override
	public OAuth2User loadUser(OAuth2UserRequest request) {
		if (!"github".equals(request.getClientRegistration().getRegistrationId())) {
			throw new OAuth2AuthenticationException(new OAuth2Error("unsupported_provider"));
		}
		OAuth2User gh = delegate.loadUser(request);
		Object id = gh.getAttribute("id");
		if (id == null) {
			throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info"), "GitHub returned no user id");
		}
		String verifiedPrimary = verifiedPrimaryEmail(request.getAccessToken().getTokenValue());
		var profile = new LoginProfile(AuthProvider.GITHUB, String.valueOf(id), gh.getAttribute("login"),
				verifiedPrimary, verifiedPrimary != null, gh.getAttribute("name"), gh.getAttribute("avatar_url"));
		try {
			return new SessionUser(accounts.loginOrRegister(profile));
		}
		catch (AccountService.AccountUnavailableException e) {
			throw new OAuth2AuthenticationException(new OAuth2Error("account_unavailable"), e.getMessage());
		}
	}

	/** The primary email if GitHub marks it verified; otherwise null (treated as "no verified email"). */
	String verifiedPrimaryEmail(String accessToken) {
		List<Map<String, Object>> emails;
		try {
			emails = github.get().uri("/user/emails")
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(EMAIL_LIST);
		}
		catch (RestClientException e) {
			return null; // scope not granted or GitHub down: log in without a verified email
		}
		if (emails == null) {
			return null;
		}
		return emails.stream()
				.filter(e -> Boolean.TRUE.equals(e.get("primary")) && Boolean.TRUE.equals(e.get("verified")))
				.map(e -> e.get("email"))
				.filter(String.class::isInstance)
				.map(String.class::cast)
				.findFirst()
				.orElse(null);
	}

}
