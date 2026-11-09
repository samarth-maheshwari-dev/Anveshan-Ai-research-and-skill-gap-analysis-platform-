package dev.anveshan.identity.app;

import java.util.Objects;

import dev.anveshan.identity.domain.AuthProvider;

/**
 * What a provider told us about the person after a successful OAuth/OIDC login.
 *
 * @param emailVerified true only if the provider itself vouches for the email
 *                      (Google {@code email_verified}, GitHub {@code /user/emails} verified + primary)
 */
public record LoginProfile(AuthProvider provider, String subject, String login, String email,
		boolean emailVerified, String name, String avatarUrl) {

	public LoginProfile {
		Objects.requireNonNull(provider, "provider");
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("provider subject is required");
		}
		email = email == null || email.isBlank() ? null : email.strip();
		emailVerified = emailVerified && email != null;
	}

}
