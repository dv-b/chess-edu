package com.chessedu.platform.classes.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Write shape for one content block on a lesson. Mirrors
 * {@link HomeworkResourceInput}: the client always sends the full
 * ordered list on create / update and the server replaces the stored
 * list wholesale — there's no per-block CRUD endpoint in v1.
 *
 * <p>Position is not sent by the client; the service recomputes it from
 * the list index on every write so gap / duplicate bugs can't sneak in.
 *
 * <p>The {@code kind} column on the entity is 32 chars wide (vs. 24 on
 * homework) so {@code "BOARD_WALKTHROUGH"} fits — we mirror the same
 * bound here. Per-kind payload validation lives in
 * {@link com.chessedu.platform.classes.chess.LessonResourceValidator}.
 */
public record LessonResourceInput(
        @NotBlank @Size(max = 32) String kind,
        @Size(max = 200) String title,
        @NotNull JsonNode payload
) {}
