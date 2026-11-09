package dev.anveshan.byok.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import dev.anveshan.byok.domain.ByokEnums.AiProvider;
import dev.anveshan.byok.domain.ByokEnums.ConnectionKind;
import dev.anveshan.byok.domain.ByokEnums.ConnectionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A user's saved AI / search key. The key exists here only as Tink AEAD ciphertext;
 * only {@code keyLast4} is ever shown (RULES §0 rule 4).
 */
@Entity
@Table(name = "ai_connection")
public class AiConnection {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private UUID ownerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ConnectionKind kind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AiProvider provider;

	@Column(nullable = false)
	private String label;

	@Column(nullable = false)
	private String baseUrl;

	@Column(nullable = false)
	private byte[] keyCiphertext;

	@Column(nullable = false)
	private String keyLast4;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ConnectionStatus status = ConnectionStatus.UNVERIFIED;

	private String statusDetail;

	private Instant lastTestedAt;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private Instant updatedAt;

	protected AiConnection() {
	}

	public AiConnection(UUID ownerId, ConnectionKind kind, AiProvider provider, String label, String baseUrl,
			byte[] keyCiphertext, String keyLast4) {
		this.ownerId = Objects.requireNonNull(ownerId);
		this.kind = Objects.requireNonNull(kind);
		this.provider = Objects.requireNonNull(provider);
		this.label = Objects.requireNonNull(label);
		this.baseUrl = Objects.requireNonNull(baseUrl);
		this.keyCiphertext = Objects.requireNonNull(keyCiphertext).clone();
		this.keyLast4 = Objects.requireNonNull(keyLast4);
	}

	public UUID getId() {
		return id;
	}

	public UUID getOwnerId() {
		return ownerId;
	}

	public ConnectionKind getKind() {
		return kind;
	}

	public AiProvider getProvider() {
		return provider;
	}

	public String getLabel() {
		return label;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	/** Ciphertext only — decrypting happens in the byok app layer, never here. */
	public byte[] getKeyCiphertext() {
		return keyCiphertext.clone();
	}

	public String getKeyLast4() {
		return keyLast4;
	}

	public ConnectionStatus getStatus() {
		return status;
	}

	public String getStatusDetail() {
		return statusDetail;
	}

	public Instant getLastTestedAt() {
		return lastTestedAt;
	}

	public void recordTest(ConnectionStatus status, String detail, Instant at) {
		this.status = Objects.requireNonNull(status);
		this.statusDetail = detail;
		this.lastTestedAt = Objects.requireNonNull(at);
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	/** Never print key material, not even ciphertext. */
	@Override
	public String toString() {
		return "AiConnection[id=" + id + ", provider=" + provider + ", keyLast4=" + keyLast4 + ", status=" + status + "]";
	}

}
