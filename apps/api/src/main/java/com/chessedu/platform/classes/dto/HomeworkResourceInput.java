package com.chessedu.platform.classes.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Write shape for one content block on a homework. The client sends a full
 * ordered list of these on create / update and the server replaces the
 * stored list wholesale — there's no per-block CRUD endpoint in v1 because
 * the admin UI always edits the whole stack anyway.
 *
 * <p>Position is not sent by the client; the service recomputes it from
 * the list index on every write so we never have gap / duplicate bugs.
 *
 * <p>The {@code payload} is validated per-{@code kind} by the service.
 * For {@code BOARD}:
 * <ul>
 *   <li>{@code startingFen} must parse as a legal position,</li>
 *   <li>{@code moves} (if present) must each be legal from the running
 *       position,</li>
 *   <li>{@code solution.uci} (if present) must be legal starting from the
 *       position reached after {@code moves}.</li>
 * </ul>
 */
public record HomeworkResourceInput(
        @NotBlank @Size(max = 24) String kind,
        @Size(max = 200) String title,
        @NotNull JsonNode payload
) {}
