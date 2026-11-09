package dev.anveshan.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One person. Soft-deleted via {@code deletedAt}; hard delete happens within 24 h (SCHEMA §10.1). */
@Entity
@Table(name = "app_user")
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private String email;

	@Column(nullable = false)
	private boolean emailVerified;

	private String name;

	private String avatarUrl;

	@Column(nullable = false)
	private String locale = "en";

	@Column(nullable = false)
	private String timezone = "Asia/Kolkata";

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private Instant updatedAt;

	private Instant deletedAt;

	protected AppUser() {
	}

	public AppUser(String email, boolean emailVerified, String name) {
		this.email = Objects.requireNonNull(email, "email").strip();
		this.emailVerified = emailVerified;
		this.name = name;
	}

	public UUID getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public boolean isEmailVerified() {
		return emailVerified;
	}

	public String getName() {
		return name;
	}

	public String getAvatarUrl() {
		return avatarUrl;
	}

	public void setAvatarUrl(String avatarUrl) {
		this.avatarUrl = avatarUrl;
	}

	public String getLocale() {
		return locale;
	}

	public String getTimezone() {
		return timezone;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public void markDeleted(Instant at) {
		this.deletedAt = Objects.requireNonNull(at);
	}

}
