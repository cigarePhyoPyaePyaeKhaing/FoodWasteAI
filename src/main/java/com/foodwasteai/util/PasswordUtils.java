package com.foodwasteai.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Salted password hashing and verification.
 * Uses PBKDF2 with HMAC-SHA256, 16-byte random salt, and 600,000 iterations for new passwords.
 */
public final class PasswordUtils {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 600000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PasswordUtils() {}

    /**
     * Generates a secure salted hash of the provided password using PBKDF2.
     * Stored format: PBKDF2$iterations$saltBase64$hashBase64
     */
    public static String hashPassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be empty");
        }

        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);

        byte[] hash = pbkdf2(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);

        return "PBKDF2$" + ITERATIONS + "$" +
                Base64.getEncoder().encodeToString(salt) + "$" +
                Base64.getEncoder().encodeToString(hash);
    }

    /**
     * Verifies an incoming password against the stored password hash.
     * Supports PBKDF2 formatted hashes with timing-attack resistant comparison.
     * Rejects plaintext and unrecognized hash formats.
     */
    public static boolean verifyPassword(String password, String storedHash) {
        if (password == null || storedHash == null || storedHash.isEmpty()) {
            return false;
        }

        if (storedHash.startsWith("PBKDF2$")) {
            String[] parts = storedHash.split("\\$");
            if (parts.length != 4) {
                return false;
            }

            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expectedHash = Base64.getDecoder().decode(parts[3]);
                if (iterations < 1 || iterations > 2000000 || salt.length < 16 || expectedHash.length != 32) {
                    return false;
                }

                byte[] actualHash = pbkdf2(password.toCharArray(), salt, iterations, expectedHash.length * 8);

                return MessageDigest.isEqual(expectedHash, actualHash);
            } catch (Exception e) {
                return false;
            }
        }

        // 2. Backward compatibility fallback for SHA-256 hex hashes (64 hex characters)
        if (storedHash.length() == 64 && storedHash.matches("^[a-fA-F0-9]{64}$")) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                if (MessageDigest.isEqual(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        storedHash.toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                    return true;
                }
            } catch (Exception ignored) {}
        }

        // Never accept plaintext or unrecognized hashes as passwords.
        return false;
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLength) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLength);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Error generating PBKDF2 hash: " + e.getMessage(), e);
        }
    }
}
