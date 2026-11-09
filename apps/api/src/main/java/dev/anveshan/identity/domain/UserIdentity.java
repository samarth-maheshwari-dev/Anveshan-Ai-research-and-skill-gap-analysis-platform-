package dev.anveshan.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One login method (Google or GitHub account) linked to an {@link AppUser}. */
@Entity
@Table(name = "user_identity")
public class UserIdentity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AuthProvider provider;

	@Column(nullable = false)
	private String providerSubject;

	private String providerLogin;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected UserIdentity() {
	}

	public UserIdentity(UUID userId, AuthProvider provider, String providerSubject, String providerLogin) {
		this.userId = Objects.requireNonNull(userId);
		this.provider = Objects.requireNonNull(provider);
		this.providerSubject = Objects.requireNonNull(providerSubject);
		this.providerLogin = providerLogin;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public AuthProvider getProvider() {
		return provider;
	}

	public String getProviderSubject() {
		return providerSubject;
	}

	public String getProviderLogin() {
		return providerLogin;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
