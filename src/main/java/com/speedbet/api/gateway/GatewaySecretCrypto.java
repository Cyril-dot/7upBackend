package com.speedbet.api.gateway;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM envelope for super-admin gateway secrets (their own AkwaPay
 * credentials). The key is SHA-256 of the server env secret
 * (GATEWAY_KEY_SECRET) — never stored, never logged. Each encryption
 * uses a fresh random 12-byte IV, prepended to the ciphertext, so the
 * same secret encrypts differently every time. Decryption failure
 * (wrong/rotated env secret, corrupted value) throws, and callers
 * treat that as "no branch key" and fall back to the platform key —
 * a bad stored value must never break deposits.
 */
public final class GatewaySecretCrypto {

    private GatewaySecretCrypto() {}

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static byte[] key(String keySecret) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(keySecret.getBytes(StandardCharsets.UTF_8));
    }

    public static String encrypt(String plaintext, String keySecret) throws Exception {
        var iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key(keySecret), "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        var ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        var out = new byte[IV_BYTES + ct.length];
        System.arraycopy(iv, 0, out, 0, IV_BYTES);
        System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }

    public static String decrypt(String encoded, String keySecret) throws Exception {
        var all = Base64.getDecoder().decode(encoded);
        if (all.length <= IV_BYTES) throw new IllegalArgumentException("ciphertext too short");
        var iv = new byte[IV_BYTES];
        System.arraycopy(all, 0, iv, 0, IV_BYTES);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key(keySecret), "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES),
                StandardCharsets.UTF_8);
    }
}
