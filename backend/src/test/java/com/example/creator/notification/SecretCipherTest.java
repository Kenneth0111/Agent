package com.example.creator.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {
    @Test
    void ciphertextNeedsTheDeploymentKeyAndRejectsTampering() {
        var cipher = new SecretCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        var encrypted = cipher.encrypt("smtp-secret");
        assertThat(encrypted).startsWith("v1:").doesNotContain("smtp-secret");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("smtp-secret");
        assertThatThrownBy(() -> new SecretCipher("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=")
                .decrypt(encrypted)).hasMessage("NOTIFICATION_SECRET_INVALID");
        assertThatThrownBy(() -> new SecretCipher("").decrypt(encrypted))
                .hasMessage("NOTIFICATION_KEY_UNAVAILABLE");
    }
}
