package com.chessedu.platform.classes.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Read shape for one content block attached to a homework. The
 * {@code payload} is shipped raw — the frontend (admin editor + student
 * viewer) knows how to interpret each {@code kind}.
 *
 * <p>In v1 the only {@code kind} we emit is {@code "BOARD"}, whose payload
 * is:
 * <pre>{@code
 * { "startingFen": "...", "moves": [...], "orientation": "white"|"black",
 *   "solution": { "uci": [...] } }
 * }</pre>
 */
public record HomeworkResourceView(
        UUID id,
        int position,
        String kind,
        String title,
        JsonNode payload
) {}
