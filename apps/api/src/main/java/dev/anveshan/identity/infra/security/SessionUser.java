package dev.anveshan.identity.infra.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The principal kept in the server-side session. Deliberately tiny: only our user id.
 * No provider tokens, ID tokens or profile data are stored in the session table.
 */
public final class SessionUser implements OidcUser, Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_USER"));

	private final UUID userId;

	public SessionUser(UUID userId) {
		this.userId = userId;
	}

	public UUID userId() {
		return userId;
	}

	/** Principal name = our user id (also stored in SPRING_SESSION.PRINCIPAL_NAME). */
	@Override
	public String getName() {
		return userId.toString();
	}

	@Override
	public Map<String, Object> getAttributes() {
		return Map.of("sub", userId.toString());
	}

	@Override
	public Map<String, Object> getClaims() {
		return getAttributes();
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return AUTHORITIES;
	}

	@Override
	public OidcUserInfo getUserInfo() {
		return null;
	}

	@Override
	public OidcIdToken getIdToken() {
		return null;
	}

	@Override
	public String toString() {
		return "SessionUser[" + userId + "]";
	}

}
