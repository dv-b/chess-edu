package com.chessedu.platform.classes.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Student-side request body for
 * {@code POST /api/v1/me/homework/{id}/blocks/{blockIndex}/attempts}.
 *
 * <p>{@link #moves} is the raw JSON the client sent — normally a JSON array
 * of UCI strings. We deliberately accept it as a {@link JsonNode} so the
 * service-level {@link com.chessedu.platform.classes.chess.BlockAttemptValidator}
 * can produce a coherent single error message instead of Spring unwrapping a
 * list-of-strings deserialisation failure. The validator enforces:
 * non-null, array shape, at least one move, each entry a non-empty UCI
 * string, each move legal from the block's running position.
 */
public record SubmitBlockAttemptRequest(JsonNode moves) {}
