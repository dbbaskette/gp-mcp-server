package com.baskettecase.gpmcp.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Service for encrypting and decrypting database credentials using AES-256-GCM.
 *
 * Encryption key must be provided via GP_MCP_ENCRYPTION_KEY environment variable.
 * The key should be a 32-byte (256-bit) base64-encoded string.
 */
@Service
@Slf4j
public class CredentialEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits
    private static final int GCM_TAG_LENGTH = 128; // 128 bits

    private final SecretKey secretKey;
    private final SecureRandom secureRandom;

    public CredentialEncryptionService(
            @Value("${gp.mcp.security.encryption-key:}") String encryptionKey) {

        if (encryptionKey == null || encryptionKey.trim().isEmpty()) {
            throw new IllegalStateException(
                "Encryption key not configured. Set GP_MCP_ENCRYPTION_KEY environment variable " +
                "to a 32-byte base64-encoded string. Generate one with: " +
                "openssl rand -base64 32"
            );
        }

        try {
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKey.trim());
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException(
                    "Encryption key must be 32 bytes (256 bits). Current length: " + keyBytes.length
                );
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            this.secureRandom = new SecureRandom();
            log.info("Credential encryption service initialized with AES-256-GCM");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid encryption key format: " + e.getMessage(), e);
        }
    }

    /**
     * Encrypts a plaintext credential using AES-256-GCM.
     * Returns base64-encoded ciphertext with embedded IV.
     *
     * @param plaintext The credential to encrypt (never null)
     * @return Base64-encoded encrypted credential
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Cannot encrypt null value");
        }

        try {
            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            // Encrypt
            byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = cipher.doFinal(plaintextBytes);

            // Combine IV + ciphertext
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            // Return base64-encoded result
            return Base64.getEncoder().encodeToString(byteBuffer.array());

        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new RuntimeException("Failed to encrypt credential", e);
        }
    }

    /**
     * Decrypts a base64-encoded ciphertext using AES-256-GCM.
     *
     * @param ciphertext Base64-encoded encrypted credential
     * @return Decrypted plaintext credential
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.trim().isEmpty()) {
            throw new IllegalArgumentException("Cannot decrypt null or empty value");
        }

        try {
            // Decode base64
            byte[] combined = Base64.getDecoder().decode(ciphertext.trim());

            if (combined.length < GCM_IV_LENGTH) {
                throw new IllegalArgumentException("Ciphertext too short");
            }

            // Extract IV and ciphertext
            ByteBuffer byteBuffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] ciphertextBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(ciphertextBytes);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            // Decrypt
            byte[] plaintextBytes = cipher.doFinal(ciphertextBytes);
            return new String(plaintextBytes, StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new RuntimeException("Failed to decrypt credential", e);
        }
    }

    /**
     * Safely encrypts a credential, returning null if input is null.
     * Used for optional fields.
     */
    public String encryptIfPresent(String plaintext) {
        return plaintext == null ? null : encrypt(plaintext);
    }

    /**
     * Safely decrypts a credential, returning null if input is null.
     * Used for optional fields.
     */
    public String decryptIfPresent(String ciphertext) {
        return (ciphertext == null || ciphertext.trim().isEmpty()) ? null : decrypt(ciphertext);
    }
}
