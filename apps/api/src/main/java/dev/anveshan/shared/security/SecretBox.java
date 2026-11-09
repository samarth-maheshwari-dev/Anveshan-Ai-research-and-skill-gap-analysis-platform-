package dev.anveshan.shared.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.GeneralSecurityException;
import java.util.Objects;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.Configuration;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;

/**
 * Encrypts user secrets at rest with Tink AEAD (AES-256-GCM) (TECHSPEC §6.2).
 *
 * <p>The associated data binds a ciphertext to its row, e.g.
 * {@code "ai_connection:" + id + ":" + ownerId}: copying ciphertext to another row makes it
 * undecryptable. Only the byok/skills modules may call {@link #open}.
 *
 * <p>Only the AEAD primitives are registered with Tink ({@code AeadConfig.register()}).
 */
public final class SecretBox {

	static {
		try {
			AeadConfig.register();
		}
		catch (GeneralSecurityException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private final Aead aead;

	private SecretBox(KeysetHandle handle, Configuration config) throws GeneralSecurityException {
		this.aead = handle.getPrimitive(config, Aead.class);
	}

	/** From a Tink JSON keyset (as created by {@code tinkey create-keyset --key-template AES256_GCM}). */
	public static SecretBox fromKeysetJson(String keysetJson) throws GeneralSecurityException {
		Objects.requireNonNull(keysetJson, "keysetJson");
		Configuration config = RegistryConfiguration.get();
		return new SecretBox(TinkJsonProtoKeysetFormat.parseKeyset(keysetJson, config, InsecureSecretKeyAccess.get()),
				config);
	}

	/** A random in-memory key. Data sealed with it is lost on restart — tests/CI only. */
	public static SecretBox ephemeral() throws GeneralSecurityException {
		Configuration config = RegistryConfiguration.get();
		return new SecretBox(KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM, config), config);
	}

	public byte[] seal(Secret secret, String associatedData) throws GeneralSecurityException {
		return aead.encrypt(secret.reveal().getBytes(UTF_8), requireAd(associatedData));
	}

	public Secret open(byte[] ciphertext, String associatedData) throws GeneralSecurityException {
		return Secret.of(new String(aead.decrypt(ciphertext, requireAd(associatedData)), UTF_8));
	}

	private static byte[] requireAd(String associatedData) {
		if (associatedData == null || associatedData.isBlank()) {
			throw new IllegalArgumentException("associated data is required (bind the ciphertext to its row)");
		}
		return associatedData.getBytes(UTF_8);
	}

	@Override
	public String toString() {
		return "SecretBox[****]";
	}

}
