package dev.anveshan.identity.app;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.anveshan.byok.api.ConnectionQuery;
import dev.anveshan.identity.domain.AppUser;
import dev.anveshan.identity.domain.UserIdentity;
import dev.anveshan.identity.infra.AppUserRepository;
import dev.anveshan.identity.infra.UserIdentityRepository;

/**
 * Account model and linking rules (TECHSPEC §5.4).
 *
 * <ol>
 * <li>Known (provider, subject) → that user.</li>
 * <li>Unknown, but the provider <b>verified</b> the email and a live user with that verified
 * email exists → link a new identity to that user.</li>
 * <li>Otherwise → new user.</li>
 * </ol>
 * Unverified emails are never stored as the account email and never used for linking, so
 * nobody can pre-claim or take over someone else's account with an email they don't own.
 */
@Service
public class AccountService {

	private final AppUserRepository users;
	private final UserIdentityRepository identities;
	private final ConnectionQuery connections;

	AccountService(AppUserRepository users, UserIdentityRepository identities, ConnectionQuery connections) {
		this.users = users;
		this.identities = identities;
		this.connections = connections;
	}

	@Transactional
	public UUID loginOrRegister(LoginProfile p) {
		var known = identities.findByProviderAndProviderSubject(p.provider(), p.subject());
		if (known.isPresent()) {
			AppUser user = users.findLive(known.get().getUserId())
					.orElseThrow(() -> new AccountUnavailableException("This account was deleted."));
			user.fillProfile(p.name(), p.avatarUrl());
			return user.getId();
		}

		AppUser user = (p.emailVerified() ? users.findLiveVerifiedByEmail(p.email()) : Optional.<AppUser>empty())
				.orElseGet(() -> users.save(new AppUser(accountEmail(p), p.emailVerified(), p.name())));
		user.fillProfile(p.name(), p.avatarUrl());
		identities.save(new UserIdentity(user.getId(), p.provider(), p.subject(), p.login()));
		return user.getId();
	}

	@Transactional(readOnly = true)
	public Optional<MeView> me(UUID userId) {
		return users.findLive(userId).map(u -> new MeView(u.getId(), u.getEmail(), u.getName(), u.getAvatarUrl(),
				u.getLocale(), u.getTimezone(),
				identities.findByUserIdOrderByCreatedAt(u.getId()).stream().map(i -> i.getProvider().name()).toList(),
				new MeView.Onboarding(connections.hasVerifiedConnection(u.getId()), false)));
	}

	/** Verified email as-is; otherwise a unique placeholder that can never match a real address. */
	static String accountEmail(LoginProfile p) {
		if (p.emailVerified()) {
			return p.email();
		}
		return p.provider().name().toLowerCase(Locale.ROOT) + "-" + p.subject() + "@unverified.invalid";
	}

	/** Login refused: the account is deleted (hard delete pending). */
	public static class AccountUnavailableException extends RuntimeException {

		AccountUnavailableException(String message) {
			super(message);
		}

	}

}
