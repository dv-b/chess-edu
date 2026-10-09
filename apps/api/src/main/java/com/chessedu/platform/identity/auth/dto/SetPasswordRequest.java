package com.chessedu.platform.identity.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/v1/auth/account/password}. {@code currentPassword}
 * is required when the user already has one (change) and ignored when they
 * don't (Telegram-signup users setting their first password).
 */
public record SetPasswordRequest(
        @Size(max = 200) String currentPassword,
        @NotBlank @Size(min = 8, max = 200) String newPassword
) {
}
