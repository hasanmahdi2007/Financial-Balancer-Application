package com.hasan.budget.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.ingestion.domain.EncryptedToken;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The protection on the one stored value that can read someone's entire financial life.
 */
class AccessTokenCipherTest {

    private static final String USER = "user-1";
    private static final String TOKEN = "access-sandbox-9c2f1a44-0b2e-4d1a-9b42-8c66f1f0ab12";

    private final AccessTokenCipher cipher = new AccessTokenCipher(aKey());

    @Test
    @DisplayName("what goes in comes back out")
    void encryptionIsReversibleByItsOwner() {
        EncryptedToken sealed = cipher.encrypt(TOKEN, USER);

        assertThat(cipher.decrypt(sealed, USER)).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("the stored value reveals nothing of the token and says which key made it")
    void theStoredFormIsOpaqueAndVersioned() {
        EncryptedToken sealed = cipher.encrypt(TOKEN, USER);

        assertThat(sealed.value()).doesNotContain(TOKEN).startsWith("v1:");
        // The commonest way a secret reaches a log is an object being printed for another reason.
        assertThat(sealed.toString()).doesNotContain(TOKEN).doesNotContain(sealed.value());
    }

    @Test
    @DisplayName("the same token encrypted twice looks different both times")
    void aFreshNonceEveryTime() {
        EncryptedToken once = cipher.encrypt(TOKEN, USER);
        EncryptedToken twice = cipher.encrypt(TOKEN, USER);

        // Otherwise the column would show which two accounts hold the same credential.
        assertThat(once.value()).isNotEqualTo(twice.value());
        assertThat(cipher.decrypt(twice, USER)).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("a token copied into another user's row does not decrypt")
    void theCiphertextBelongsToItsOwner() {
        EncryptedToken sealed = cipher.encrypt(TOKEN, USER);

        assertThatThrownBy(() -> cipher.decrypt(sealed, "someone-else"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("altered");
    }

    @Test
    @DisplayName("an altered ciphertext fails loudly rather than decrypting to something else")
    void tamperingIsDetected() {
        String sealed = cipher.encrypt(TOKEN, USER).value();
        String flipped = sealed.substring(0, sealed.length() - 2)
                + (sealed.endsWith("A=") ? "B=" : "A=");

        assertThatThrownBy(() -> cipher.decrypt(new EncryptedToken(flipped), USER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a stored value that was edited by hand fails as a stored-value problem")
    void aCorruptedRowSaysWhatIsWrongWithIt() {
        // Not arithmetic accidents: a decoder complaining about padding, or an array index error on a
        // truncated value, tells whoever is reading the log nothing about the database.
        assertThatThrownBy(() -> cipher.decrypt(new EncryptedToken("v1:not-base64-!!"), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("altered");
        assertThatThrownBy(() -> cipher.decrypt(new EncryptedToken("v1:c2hvcnQ="), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    @Test
    @DisplayName("a value written by an older scheme is refused rather than guessed at")
    void anUnknownFormatIsNotDecrypted() {
        assertThatThrownBy(() -> cipher.decrypt(new EncryptedToken("just-a-token"), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("format");
    }

    @Test
    @DisplayName("without a key the application still starts, and says what is missing when used")
    void aMissingKeyFailsLateAndClearly() {
        AccessTokenCipher unconfigured = new AccessTokenCipher("");

        assertThatThrownBy(() -> unconfigured.encrypt(TOKEN, USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOKEN_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("a key of the wrong size is rejected when it is configured, not when it is used")
    void aBadKeyIsCaughtAtStartup() {
        assertThatThrownBy(() -> new AccessTokenCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new AccessTokenCipher("not base64 at all !!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    private static String aKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
