package com.chessedu.platform.identity.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/login}.
 *
 * <p>{@code login} is an email <em>or</em> a username (the service tells
 * them apart by the presence of '@'). {@code email} is the pre-V014 field
 * name and is still accepted so older clients keep working; exactly one of
 * the two must be present.
 */
public record LoginRequest(
        @Size(max = 240) String login,
        @Size(max = 240) String email,
        @NotBlank @Size(min = 8, max = 200) String password
) {
    /** Whichever identifier the client sent. */
    public String identifier() {
        return login != null && !login.isBlank() ? login : email;
    }

    @AssertTrue(message = "login or email is required")
    public boolean isIdentifierPresent() {
        return identifier() != null && !identifier().isBlank();
    }
}
