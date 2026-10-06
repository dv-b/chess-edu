package com.chessedu.platform.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound-email identity. Spring handles SMTP transport via
 * {@code spring.mail.*}; this captures the envelope (from address / display
 * name) we want users to see.
 */
@ConfigurationProperties(prefix = "chessedu.mail")
public record MailProperties(String fromAddress, String fromName) {

    public String fromAddress() {
        return fromAddress == null || fromAddress.isBlank() ? "noreply@chess-edu.local" : fromAddress;
    }

    public String fromName() {
        return fromName == null || fromName.isBlank() ? "Kingside" : fromName;
    }
}
