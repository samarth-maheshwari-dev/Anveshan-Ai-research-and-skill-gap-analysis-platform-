package dev.anveshan.identity.infra.security;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import dev.anveshan.identity.app.AccountService;
import dev.anveshan.identity.app.LoginProfile;
import dev.anveshan.identity.domain.AuthProvider;

/** Google (OIDC): the ID token is validated by Spring; we map its claims and drop the token. */
public class GoogleOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

	private final OidcUserService delegate = new OidcUserService();
	private final AccountService accounts;

	public GoogleOidcUserService(AccountService accounts) {
		this.accounts = accounts;
	}

	@Override
	public OidcUser loadUser(OidcUserRequest request) {
		OidcUser google = delegate.loadUser(request);
		var profile = new LoginProfile(AuthProvider.GOOGLE, google.getSubject(), null, google.getEmail(),
				Boolean.TRUE.equals(google.getEmailVerified()), google.getFullName(), google.getPicture());
		try {
			return new SessionUser(accounts.loginOrRegister(profile));
		}
		catch (AccountService.AccountUnavailableException e) {
			throw new OAuth2AuthenticationException(new OAuth2Error("account_unavailable"), e.getMessage());
		}
	}

}
