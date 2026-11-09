package dev.anveshan.byok.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Learned usage and cooldown for one (connection, model, day). */
@Entity
@Table(name = "route_quota")
public class RouteQuota {

	@Embeddable
	public record Key(UUID connectionId, String model, LocalDate day) {

		public Key {
			Objects.requireNonNull(connectionId);
			Objects.requireNonNull(model);
			Objects.requireNonNull(day);
		}

	}

	@EmbeddedId
	private Key key;

	@Column(nullable = false)
	private int requests;

	@Column(nullable = false)
	private int tokensIn;

	@Column(nullable = false)
	private int tokensOut;

	private Integer remainingRequestsHint;

	private Integer remainingTokensHint;

	private Instant cooldownUntil;

	protected RouteQuota() {
	}

	public RouteQuota(Key key) {
		this.key = Objects.requireNonNull(key);
	}

	public Key getKey() {
		return key;
	}

	public int getRequests() {
		return requests;
	}

	public int getTokensIn() {
		return tokensIn;
	}

	public int getTokensOut() {
		return tokensOut;
	}

	public Integer getRemainingRequestsHint() {
		return remainingRequestsHint;
	}

	public Integer getRemainingTokensHint() {
		return remainingTokensHint;
	}

	public Instant getCooldownUntil() {
		return cooldownUntil;
	}

	public void addUsage(int tokensIn, int tokensOut) {
		this.requests++;
		this.tokensIn += tokensIn;
		this.tokensOut += tokensOut;
	}

	public void coolDownUntil(Instant until) {
		this.cooldownUntil = until;
	}

}
