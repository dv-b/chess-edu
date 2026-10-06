package com.chessedu.platform.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the web application facing the backend. Used to build absolute
 * URLs in emails (invitations, password resets, etc).
 */
@ConfigurationProperties(prefix = "chessedu.web")
public record WebProperties(String baseUrl) {

    public String baseUrl() {
        return baseUrl == null ? "http://localhost:3100" : stripTrailingSlash(baseUrl);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
