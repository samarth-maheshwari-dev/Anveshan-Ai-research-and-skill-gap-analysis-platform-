package dev.anveshan.byok.app;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import dev.anveshan.byok.app.ProviderModels.ResolvedConnection;
import dev.anveshan.byok.domain.ByokEnums.AiProvider;
import dev.anveshan.byok.domain.ProviderPreset;
import dev.anveshan.byok.infra.ByokProperties;
import dev.anveshan.shared.security.Secret;
import dev.anveshan.shared.security.SecretBox;

/**
 * The only place user keys are encrypted or decrypted (TECHSPEC §6.2). A decrypted key lives in a
 * {@link Secret} inside a {@link ResolvedConnection} for the duration of one provider call.
 */
@Component
public class KeyVault {

	private final SecretBox box;
	private final ByokProperties props;
	private final Map<ProviderPreset.Transport, LlmTransport> transports;

	KeyVault(SecretBox box, ByokProperties props, List<LlmTransport> transports) {
		this.box = box;
		this.props = props;
		this.transports = transports.stream().collect(Collectors.toMap(LlmTransport::kind, Function.identity()));
	}

	/** Binds the ciphertext to its row and owner: copied elsewhere it can't be decrypted. */
	static String associatedData(UUID connectionId, UUID ownerId) {
		return "ai_connection:" + connectionId + ":" + ownerId;
	}

	byte[] seal(UUID connectionId, UUID ownerId, Secret key) {
		try {
			return box.seal(key, associatedData(connectionId, ownerId));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Encryption failed", e);
		}
	}

	/** @throws KeyUnreadableException if the stored ciphertext can't be opened (e.g. keyset changed) */
	ResolvedConnection resolve(UUID connectionId, UUID ownerId, AiProvider provider, String baseUrl, byte[] ciphertext) {
		try {
			Secret key = box.open(ciphertext, associatedData(connectionId, ownerId));
			return new ResolvedConnection(connectionId, ownerId, provider, props.preset(provider), URI.create(baseUrl),
					provider == AiProvider.CUSTOM, key);
		}
		catch (GeneralSecurityException e) {
			throw new KeyUnreadableException();
		}
	}

	LlmTransport transportFor(ProviderPreset preset) {
		return transports.get(preset.transport());
	}

	static final class KeyUnreadableException extends RuntimeException {

		KeyUnreadableException() {
			super("The saved key can't be read anymore. Please enter it again.");
		}

	}

}
