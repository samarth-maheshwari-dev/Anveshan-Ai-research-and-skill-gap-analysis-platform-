package dev.anveshan.identity.infra.security;

import java.util.UUID;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import dev.anveshan.identity.api.CurrentUser;

@Component
class SecurityContextCurrentUser implements CurrentUser {

	@Override
	public UUID id() {
		var auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof SessionUser user) {
			return user.userId();
		}
		throw new AuthenticationCredentialsNotFoundException("Not signed in");
	}

}
