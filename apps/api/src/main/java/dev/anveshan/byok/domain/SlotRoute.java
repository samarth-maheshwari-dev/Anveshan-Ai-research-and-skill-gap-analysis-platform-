package dev.anveshan.byok.domain;

import java.util.Objects;
import java.util.UUID;

import dev.anveshan.byok.api.TaskSlot;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** Which connection + model serves a slot, at a fallback position 0..4. */
@Entity
@Table(name = "slot_route")
public class SlotRoute {

	@Embeddable
	public record Key(UUID ownerId, @Enumerated(EnumType.STRING) TaskSlot slot, short position) {

		public Key {
			Objects.requireNonNull(ownerId);
			Objects.requireNonNull(slot);
			if (position < 0 || position > 4) {
				throw new IllegalArgumentException("position must be 0..4");
			}
		}

	}

	@EmbeddedId
	private Key key;

	@Column(nullable = false)
	private UUID connectionId;

	@Column(nullable = false)
	private String model;

	protected SlotRoute() {
	}

	public SlotRoute(Key key, UUID connectionId, String model) {
		this.key = Objects.requireNonNull(key);
		this.connectionId = Objects.requireNonNull(connectionId);
		this.model = Objects.requireNonNull(model);
	}

	public Key getKey() {
		return key;
	}

	public UUID getConnectionId() {
		return connectionId;
	}

	public String getModel() {
		return model;
	}

}
