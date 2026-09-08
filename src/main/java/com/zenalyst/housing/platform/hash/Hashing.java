package com.zenalyst.housing.platform.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SHA-256 and HMAC-SHA-256, always returning lowercase hexadecimal.
 *
 * <p>Lowercase hex is not a detail. Every hash this system produces is published and is meant to
 * be recomputed by someone else; a hash that differs only in case reads as a mismatch and
 * invites the accusation the value was fabricated. One representation, everywhere.
 */
public final class Hashing {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** The chain's first link: the {@code prev_hash} of the genesis audit event. */
    public static final String ZERO_HASH = "0".repeat(64);

    private Hashing() {
    }

    public static String sha256Hex(String input) {
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] input) {
        return toHex(sha256(input));
    }

    /** Raw digest, for callers that go on to hash the digest itself — see the Merkle tree. */
    public static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM specification; its absence is not a runtime concern.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static byte[] fromHex(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("hex string must have an even length");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    public static String hmacSha256Hex(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable or key rejected", e);
        }
    }

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
