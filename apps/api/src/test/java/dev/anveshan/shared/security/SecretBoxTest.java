package dev.anveshan.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.aead.PredefinedAeadParameters;

class SecretBoxTest {

	static final String AD = "ai_connection:6f1c…:owner-1";

	@Test
	@DisplayName("T-06-07 seal/open round trip; ciphertext never contains the plaintext")
	void roundTrip() throws GeneralSecurityException {
		SecretBox box = SecretBox.ephemeral();
		byte[] sealed = box.seal(Secret.of("gsk_live_example_1234567890"), AD);

		assertThat(box.open(sealed, AD).reveal()).isEqualTo("gsk_live_example_1234567890");
		assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("gsk_live");
		assertThat(box.seal(Secret.of("same"), AD)).as("random nonce").isNotEqualTo(box.seal(Secret.of("same"), AD));
	}

	@Test
	@DisplayName("T-06-07 open with wrong associated data (another row) throws")
	void wrongAssociatedDataFails() throws GeneralSecurityException {
		SecretBox box = SecretBox.ephemeral();
		byte[] sealed = box.seal(Secret.of("key-of-user-A"), "ai_connection:1:userA");

		assertThatThrownBy(() -> box.open(sealed, "ai_connection:1:userB")).isInstanceOf(GeneralSecurityException.class);
		assertThatThrownBy(() -> box.open(sealed, "ai_connection:2:userA")).isInstanceOf(GeneralSecurityException.class);
		assertThatThrownBy(() -> box.seal(Secret.of("x"), " ")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("T-06-07 a JSON keyset (tinkey format) loads; a different key can't open the data")
	void jsonKeysetAndKeyIsolation() throws GeneralSecurityException {
		SecretBox.ephemeral(); // registers AEAD
		KeysetHandle handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM, RegistryConfiguration.get());
		String json = TinkJsonProtoKeysetFormat.serializeKeyset(handle, RegistryConfiguration.get(), InsecureSecretKeyAccess.get());

		SecretBox a = SecretBox.fromKeysetJson(json);
		SecretBox sameKey = SecretBox.fromKeysetJson(json);
		byte[] sealed = a.seal(Secret.of("hello"), AD);

		assertThat(sameKey.open(sealed, AD).reveal()).isEqualTo("hello");
		assertThatThrownBy(() -> SecretBox.ephemeral().open(sealed, AD)).isInstanceOf(GeneralSecurityException.class);
		assertThat(a.toString()).doesNotContain("primaryKeyId").isEqualTo("SecretBox[****]");
	}

	@Test
	@DisplayName("T-06-08 Secret.toString() is \"****\" (also inside string concatenation); last4 for display")
	void secretNeverPrints() {
		Secret s = Secret.of("sk-proj-abcdefghijklmnop1234");

		assertThat(s.toString()).isEqualTo("****");
		assertThat("key=" + s).isEqualTo("key=****");
		assertThat(String.valueOf(s)).isEqualTo("****");
		assertThat(s.last4()).isEqualTo("1234");
		assertThat(s.reveal()).isEqualTo("sk-proj-abcdefghijklmnop1234");
		assertThatThrownBy(() -> Secret.of("  ")).isInstanceOf(IllegalArgumentException.class);
	}

}
