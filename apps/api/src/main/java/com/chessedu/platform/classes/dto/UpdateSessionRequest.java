package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.ClassSessionStatus;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Payload for editing a single session. Every field is optional: present
 * means "set to this value", absent means "leave alone". An explicit empty
 * string for {@code notes} clears it.
 *
 * <p>Editing a pattern-owned session does NOT detach it from its pattern;
 * it just marks it as a human-touched override that the pattern's regenerate
 * pass will skip from then on.
 */
public record UpdateSessionRequest(
        Instant startsAt,
        Instant endsAt,
        ClassSessionStatus status,
        @Size(max = 4000) String notes
) {}
