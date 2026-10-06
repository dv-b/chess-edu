package com.chessedu.platform.classes.chess;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.bhlangonijr.chesslib.Board;
import org.springframework.stereotype.Component;

/**
 * Server-side validator for a student's submitted moves on a single board
 * block (the work unit introduced by Task #28). Counterpart to
 * {@link HomeworkResourceValidator}, which protects authored content on the
 * write path — this one protects the <em>student</em>'s write path.
 *
 * <p>A submission's moves are played from the position reached <em>after</em>
 * the authored prefix. The validator:
 * <ol>
 *   <li>Reconstructs the running position from the stored block payload
 *       ({@code startingFen} + {@code moves} prefix).</li>
 *   <li>Applies the student's UCI moves one by one, rejecting the first
 *       malformed or illegal move.</li>
 *   <li>Returns a tidy {@link ArrayNode} of lowercased UCI strings suitable
 *       for persistence in {@code homework_block_attempt.moves}.</li>
 * </ol>
 *
 * <p>Because the stored block payload has already been validated on write
 * (see {@link HomeworkResourceValidator}), we don't re-check its structure
 * here — a payload that can't be replayed is treated as a 500-class
 * server-side inconsistency, not a student error.
 */
@Component
public class BlockAttemptValidator {

    /**
     * Hard cap on how many moves a student can submit per attempt. Matches
     * the authored-solution cap so an attempt can always be at least as long
     * as the recorded solution without hitting the limit.
     */
    private static final int MAX_ATTEMPT_MOVES = 50;

    private final ObjectMapper mapper;

    public BlockAttemptValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Validate {@code attemptMoves} as a legal continuation of the block
     * described by {@code blockPayload}. Returns a canonical
     * {@link ArrayNode} (lowercase, trimmed) that the caller should persist
     * as the attempt row's {@code moves} column.
     *
     * @param blockPayload the authored BOARD payload pulled from the
     *                     {@code homework_resource} row; must include
     *                     {@code startingFen} and may include a {@code moves}
     *                     prefix.
     * @param attemptMoves the raw JSON the student (or their client) POSTed;
     *                     must be a non-empty array of UCI strings.
     */
    public ArrayNode validate(JsonNode blockPayload, JsonNode attemptMoves) {
        if (blockPayload == null || !blockPayload.isObject()) {
            // Shouldn't reach the wire — but guard so a corrupt row produces a
            // clear error rather than an NPE inside chesslib.
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Block payload is missing or malformed");
        }
        if (attemptMoves == null || !attemptMoves.isArray()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Attempt.moves must be an array of UCI strings");
        }
        if (attemptMoves.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Attempt.moves must contain at least one move");
        }
        if (attemptMoves.size() > MAX_ATTEMPT_MOVES) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Attempt.moves has too many moves (max "
                            + MAX_ATTEMPT_MOVES + ")");
        }

        // --- rebuild the running position from the block payload ---------
        Board board = replayPrefix(blockPayload);

        // --- apply the student's moves -----------------------------------
        ArrayNode out = mapper.createArrayNode();
        for (int i = 0; i < attemptMoves.size(); i++) {
            JsonNode n = attemptMoves.get(i);
            if (n == null || n.isNull() || !n.isTextual()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Attempt.moves[" + i + "] must be a UCI string");
            }
            String uci = n.asText();
            if (uci == null || uci.trim().isEmpty()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Attempt.moves[" + i + "] must be a UCI string");
            }
            String normalised = ChessMoves.applyLegalMove(
                    board, uci, "Attempt.moves[" + i + "]");
            out.add(normalised);
        }
        return out;
    }

    /**
     * Rebuild the position at which the student begins playing — i.e.
     * {@code startingFen} with every authored prefix move applied in order.
     * Treats any failure as a server-side inconsistency (the payload was
     * validated on write).
     */
    private Board replayPrefix(JsonNode blockPayload) {
        JsonNode fenNode = blockPayload.get("startingFen");
        if (fenNode == null || !fenNode.isTextual()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Block payload missing startingFen");
        }
        Board board = new Board();
        try {
            board.loadFromFen(fenNode.asText());
        } catch (RuntimeException e) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Block startingFen is not a legal position");
        }
        JsonNode prefix = blockPayload.get("moves");
        if (prefix != null && prefix.isArray()) {
            for (int i = 0; i < prefix.size(); i++) {
                JsonNode mv = prefix.get(i);
                if (mv == null || !mv.isTextual()) {
                    throw new AppException(ErrorCode.VALIDATION_FAILED,
                            "Block prefix move " + i + " is malformed");
                }
                // Use the same helper so the failure message format is
                // consistent; the ctx label flags these as prefix replays
                // rather than student input.
                ChessMoves.applyLegalMove(board, mv.asText(),
                        "Block.moves[" + i + "] (replay)");
            }
        }
        return board;
    }
}
