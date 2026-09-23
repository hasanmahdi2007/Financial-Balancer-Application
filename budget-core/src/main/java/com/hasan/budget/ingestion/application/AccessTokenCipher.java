package com.hasan.budget.ingestion.application;

import com.hasan.budget.ingestion.domain.EncryptedToken;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts bank access tokens so that a copy of the database is not a copy of everyone's bank feed.
 *
 * <p>AES-GCM, which authenticates as well as encrypts: a ciphertext altered in the database fails to
 * decrypt rather than decrypting to something else. A fresh random nonce per encryption means the
 * same token encrypted twice produces different bytes, so the column cannot be used to tell which
 * two rows hold the same credential.
 *
 * <p>The user id is bound in as authenticated data. It is not secret and is not being hidden - it
 * makes the ciphertext belong to that user, so a token copied into another user's row is rejected
 * rather than quietly granting one person access to another's bank.
 *
 * <p>Stored with a version prefix, because the key will eventually need rotating and the only thing
 * worse than an old key is a database of values with no way to tell which key made them.
 */
public class AccessTokenCipher {

    private static final String VERSION = "v1";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final byte[] key;

    /**
     * @param base64Key 32 random bytes, base64 encoded. Blank is allowed so the application still
     *     starts without one - every other module's tests boot this context - and the failure then
     *     happens when somebody tries to connect a bank, naming the variable to set.
     */
    public AccessTokenCipher(String base64Key) {
        this.key = decodeKey(base64Key);
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("TOKEN_ENCRYPTION_KEY is not valid base64", e);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY must decode to %d bytes but was %d".formatted(KEY_BYTES, decoded.length));
        }
        return decoded;
    }

    public EncryptedToken encrypt(String accessToken, String userId) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] sealed = run(Cipher.ENCRYPT_MODE, nonce, userId, accessToken.getBytes(StandardCharsets.UTF_8));
        byte[] stored = new byte[nonce.length + sealed.length];
        System.arraycopy(nonce, 0, stored, 0, nonce.length);
        System.arraycopy(sealed, 0, stored, nonce.length, sealed.length);
        return new EncryptedToken(VERSION + ":" + Base64.getEncoder().encodeToString(stored));
    }

    public String decrypt(EncryptedToken token, String userId) {
        String[] parts = token.value().split(":", 2);
        if (parts.length != 2 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException("stored token is not in a format this version can read");
        }
        byte[] stored = decodeStored(parts[1]);
        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(stored, 0, nonce, 0, NONCE_BYTES);
        byte[] sealed = new byte[stored.length - NONCE_BYTES];
        System.arraycopy(stored, NONCE_BYTES, sealed, 0, sealed.length);
        return new String(run(Cipher.DECRYPT_MODE, nonce, userId, sealed), StandardCharsets.UTF_8);
    }

    /**
     * A stored value that is not what we wrote is a failure of its own, not an arithmetic accident.
     *
     * <p>Without this, a corrupted or hand-edited row surfaces as an {@code IllegalArgumentException}
     * from a base64 decoder, or an array index error if it is merely too short - neither of which
     * tells anyone what is actually wrong with the database.
     */
    private static byte[] decodeStored(String encoded) {
        byte[] stored;
        try {
            stored = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("stored token is not readable; it was altered after it was written", e);
        }
        if (stored.length <= NONCE_BYTES) {
            throw new IllegalStateException("stored token is too short to be a token this code wrote");
        }
        return stored;
    }

    private byte[] run(int mode, byte[] nonce, String userId, byte[] input) {
        if (key == null) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY is not set, so bank access tokens cannot be stored safely. "
                            + "Generate 32 random bytes, base64 them, and put the result in .env.");
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(userId.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            // Deliberately does not include the input: on the decrypt path that is the ciphertext,
            // and on the encrypt path it is the token itself.
            throw new IllegalStateException("access token could not be " + (mode == Cipher.ENCRYPT_MODE
                    ? "encrypted"
                    : "decrypted, which means it was altered or was written with a different key"), e);
        }
    }
}
