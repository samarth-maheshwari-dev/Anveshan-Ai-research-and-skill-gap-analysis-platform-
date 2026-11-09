package dev.anveshan.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** MCP bearer token. Only its SHA-256 is stored; the plain token is shown once. */
@Entity
@Table(name = "personal_token")
public class PersonalToken {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private UUID ownerId;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String tokenPrefix;

	@Column(nullable = false)
	private byte[] tokenHash;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(nullable = false)
	private List<String> scopes = List.of("mcp.read");

	private Instant lastUsedAt;

	private Instant expiresAt;

	private Instant revokedAt;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected PersonalToken() {
	}

	public PersonalToken(UUID ownerId, String name, String tokenPrefix, byte[] tokenHash, List<String> scopes,
			Instant expiresAt) {
		this.ownerId = Objects.requireNonNull(ownerId);
		this.name = Objects.requireNonNull(name);
		this.tokenPrefix = Objects.requireNonNull(tokenPrefix);
		this.tokenHash = Objects.requireNonNull(tokenHash).clone();
		this.scopes = List.copyOf(scopes);
		this.expiresAt = expiresAt;
	}

	public UUID getId() {
		return id;
	}

	public UUID getOwnerId() {
		return ownerId;
	}

	public String getName() {
		return name;
	}

	public String getTokenPrefix() {
		return tokenPrefix;
	}

	public byte[] getTokenHash() {
		return tokenHash.clone();
	}

	public List<String> getScopes() {
		return List.copyOf(scopes);
	}

	public Instant getLastUsedAt() {
		return lastUsedAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public void revoke(Instant at) {
		this.revokedAt = Objects.requireNonNull(at);
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
