package com.chessedu.platform.identity.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code PUT /api/v1/auth/account/username}. Normalised + pattern-checked in the service. */
public record SetUsernameRequest(
        @NotBlank @Size(min = 3, max = 32) String username
) {
}
