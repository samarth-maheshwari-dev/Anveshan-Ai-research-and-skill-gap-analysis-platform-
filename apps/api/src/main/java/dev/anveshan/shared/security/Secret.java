package dev.anveshan.shared.security;

import java.util.Objects;

/**
 * A decrypted secret (e.g. a user's API key) that can't leak by accident: {@code toString()} is
 * always {@code "****"}. Call {@link #reveal()} only at the moment the value is put on the wire.
 */
public final class Secret {

	private final String value;

	private Secret(String value) {
		this.value = value;
	}

	public static Secret of(String value) {
		Objects.requireNonNull(value, "value");
		if (value.isBlank()) {
			throw new IllegalArgumentException("secret is blank");
		}
		return new Secret(value);
	}

	public String reveal() {
		return value;
	}

	/** Last 4 characters, the only part ever shown to users (A-FR-9). */
	public String last4() {
		return value.length() <= 4 ? value : value.substring(value.length() - 4);
	}

	@Override
	public String toString() {
		return "****";
	}

	/** Identity only: secrets are not compared by value outside constant-time code. */
	@Override
	public boolean equals(Object o) {
		return this == o;
	}

	@Override
	public int hashCode() {
		return System.identityHashCode(this);
	}

}
