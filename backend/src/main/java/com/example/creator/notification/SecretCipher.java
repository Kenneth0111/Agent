package com.example.creator.notification;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    SecretCipher(@Value("${creator.notification.encryption-key:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            key = null;
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("NOTIFICATION_KEY_INVALID");
        }
        if (bytes.length != 32) throw new IllegalStateException("NOTIFICATION_KEY_INVALID");
        key = new SecretKeySpec(bytes, "AES");
        Arrays.fill(bytes, (byte) 0);
    }

    String encrypt(String value) {
        if (key == null) throw new KeyUnavailable();
        var nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            var encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            var result = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return "v1:" + Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("NOTIFICATION_ENCRYPT_FAILED", failure);
        }
    }

    String decrypt(String value) {
        if (key == null) throw new KeyUnavailable();
        try {
            if (!value.startsWith("v1:")) throw new IllegalArgumentException();
            var data = Base64.getDecoder().decode(value.substring(3));
            if (data.length < 29) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, 12));
            return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw new IllegalStateException("NOTIFICATION_SECRET_INVALID");
        }
    }

    static final class KeyUnavailable extends RuntimeException {
        KeyUnavailable() { super("NOTIFICATION_KEY_UNAVAILABLE", null, false, false); }
    }
}
