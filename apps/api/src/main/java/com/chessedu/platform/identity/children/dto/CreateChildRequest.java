package com.chessedu.platform.identity.children.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Parent-supplied payload for adding a child (student) to the school.
 *
 * <p>We intentionally take only the bare minimum — no email, no phone, no
 * birthday. Children are profile-only until a separate "activate login" flow
 * is added; until then the synthetic email generated server-side is all that
 * keeps the unique-email invariant satisfied.
 */
public record CreateChildRequest(
        @NotBlank @Size(max = 160) String displayName,
        @Pattern(regexp = "uz|ru|en") String preferredLocale
) {}
