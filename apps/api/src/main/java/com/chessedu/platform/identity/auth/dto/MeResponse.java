package com.chessedu.platform.identity.auth.dto;

import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;

import java.util.UUID;

public record MeResponse(
        UUID userId,
        String email,
        String displayName,
        String preferredLocale,
        UUID schoolId,
        String schoolName,
        Role role,
        PlatformRole platformRole
) {
}
