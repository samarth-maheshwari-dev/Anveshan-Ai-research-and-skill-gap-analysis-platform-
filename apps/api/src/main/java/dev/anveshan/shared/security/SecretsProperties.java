package dev.anveshan.shared.security;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the Tink master keyset comes from (TECHSPEC §6.1). Exactly one of: inline JSON
 * ({@code ANV_TINK_KEYSET}), a file ({@code ANV_TINK_KEYSET_FILE}, e.g. a Docker secret), or an
 * ephemeral in-memory key ({@code ANV_TINK_EPHEMERAL=true}, tests/CI only).
 */
@ConfigurationProperties("anveshan.secrets")
public record SecretsProperties(String tinkKeyset, Path tinkKeysetFile, @DefaultValue("false") boolean tinkEphemeral) {

	/** Never print the keyset. */
	@Override
	public String toString() {
		return "SecretsProperties[tinkKeyset=" + (tinkKeyset == null || tinkKeyset.isBlank() ? "unset" : "****")
				+ ", tinkKeysetFile=" + tinkKeysetFile + ", tinkEphemeral=" + tinkEphemeral + "]";
	}

}
