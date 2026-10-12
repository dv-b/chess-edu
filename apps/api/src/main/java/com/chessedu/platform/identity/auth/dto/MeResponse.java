package com.chessedu.platform.identity.auth.dto;

import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;

import java.util.UUID;

public record MeResponse(
        UUID userId,
        String email,
        /** Optional second login identifier (V014); null until the user picks one. */
        String username,
        String displayName,
        String preferredLocale,
        UUID schoolId,
        String schoolName,
        Role role,
        PlatformRole platformRole,
        /** False for Telegram-signup users until they set one on the account page. */
        boolean hasPassword,
        boolean telegramLinked
) {
}
