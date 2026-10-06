package com.chessedu.platform.identity.auth.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {
    public static TokenResponse of(String access, String refresh, long expiresInSeconds) {
        return new TokenResponse(access, refresh, "Bearer", expiresInSeconds);
    }
}
