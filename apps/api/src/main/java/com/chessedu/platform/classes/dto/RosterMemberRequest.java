package com.chessedu.platform.classes.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Payload for POST /api/v1/admin/classes/{id}/teachers and /students.
 * The role is implied by which endpoint is hit; the service validates that
 * the user actually has the matching membership role in the school.
 */
public record RosterMemberRequest(
        @NotNull UUID userId
) {}
