package com.splitledger.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque single-use tokens: refresh tokens, invite links, and anything else handed to a
 * user once and recognised later.
 *
 * <p>The raw value is given out exactly once and never stored. Only its SHA-256 hash is
 * persisted, so a database leak yields no usable tokens. SHA-256 and not BCrypt on
 * purpose: these are 256 bits of entropy from a CSPRNG, not a human-chosen secret, so
 * there is nothing to brute-force and a deliberately slow hash would only make every
 * lookup slow.
 */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureTokens() {
    }

    /** 32 random bytes, URL-safe, so it can be pasted into a link unescaped. */
    public static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex is 64 characters, which is exactly the width of the token_hash columns. */
    public static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK", ex);
        }
    }
}
