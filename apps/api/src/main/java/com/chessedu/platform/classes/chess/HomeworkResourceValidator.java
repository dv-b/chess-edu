package com.chessedu.platform.classes.chess;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.bhlangonijr.chesslib.Board;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side validator for authored chess content attached to a homework.
 *
 * <p>The entity layer stores {@code payload} as raw JSONB — schema
 * conformance and chess legality live here so we never persist garbage a
 * student's client would choke on.
 *
 * <p>The validator is a pure function of the incoming payload: it returns
 * a normalised {@link JsonNode} (trimmed strings, lowercased orientation,
 * only the fields we understand) and throws {@link AppException} with a
 * {@link ErrorCode#VALIDATION_FAILED} code on the first problem it hits.
 * Callers should persist the returned node, not the raw input.
 *
 * <p>Supported kinds in v1:
 * <ul>
 *   <li>{@code "BOARD"}: chess position with optional move sequence and
 *       optional hidden solution.</li>
 * </ul>
 *
 * <p>Adding a new kind means adding a new {@code validateXxxPayload}
 * method and routing to it from {@link #validate(String, JsonNode)} — no
 * migration needed.
 */
@Component
public class HomeworkResourceValidator {

    /** Hard cap per block so a single row can't DOS us. */
    private static final int MAX_MOVES = 200;
    private static final int MAX_SOLUTION_MOVES = 50;

    private final ObjectMapper mapper;

    public HomeworkResourceValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Validate and normalise a {@code (kind, payload)} pair. Returns a new
     * {@link JsonNode} containing only the fields we recognise. Throws on
     * any malformed / illegal content.
     */
    public JsonNode validate(String kind, JsonNode payload) {
        if (kind == null || kind.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "Resource kind is required");
        }
        if (payload == null || payload.isNull() || !payload.isObject()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Resource payload must be a JSON object");
        }
        String normKind = kind.trim().toUpperCase();
        return switch (normKind) {
            case "BOARD" -> validateBoardPayload(payload);
            default -> throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Unsupported resource kind: " + kind);
        };
    }

    // ------------------------------------------------------------------
    // BOARD
    // ------------------------------------------------------------------

    /**
     * Board block: a starting FEN, an optional UCI move list played on top
     * of it, an orientation, and an optional hidden {@code solution.uci}
     * continuation played from the position reached after {@code moves}.
     */
    private JsonNode validateBoardPayload(JsonNode payload) {
        String startingFen = requireText(payload, "startingFen",
                "Board.startingFen is required");

        Board board = new Board();
        try {
            board.loadFromFen(startingFen);
        } catch (RuntimeException e) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Board.startingFen is not a legal position");
        }

        // --- moves ---
        List<String> normMoves = new ArrayList<>();
        JsonNode movesNode = payload.get("moves");
        if (movesNode != null && !movesNode.isNull()) {
            if (!movesNode.isArray()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Board.moves must be an array of UCI strings");
            }
            if (movesNode.size() > MAX_MOVES) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Board.moves has too many moves (max " + MAX_MOVES + ")");
            }
            for (int i = 0; i < movesNode.size(); i++) {
                String uci = asText(movesNode.get(i),
                        "Board.moves[" + i + "] must be a UCI string");
                String normalised = ChessMoves.applyLegalMove(board, uci, "Board.moves[" + i + "]");
                normMoves.add(normalised);
            }
        }

        // --- orientation ---
        String orientation = optionalText(payload, "orientation");
        if (orientation == null) {
            orientation = "white";
        } else {
            orientation = orientation.trim().toLowerCase();
            if (!orientation.equals("white") && !orientation.equals("black")) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Board.orientation must be 'white' or 'black'");
            }
        }

        // --- solution (optional) ---
        ObjectNode solutionOut = null;
        JsonNode solutionNode = payload.get("solution");
        if (solutionNode != null && !solutionNode.isNull()) {
            if (!solutionNode.isObject()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Board.solution must be an object");
            }
            JsonNode uciNode = solutionNode.get("uci");
            if (uciNode == null || uciNode.isNull()) {
                // Empty solution block — treat as "no solution".
            } else {
                if (!uciNode.isArray()) {
                    throw new AppException(ErrorCode.VALIDATION_FAILED,
                            "Board.solution.uci must be an array of UCI strings");
                }
                if (uciNode.size() > MAX_SOLUTION_MOVES) {
                    throw new AppException(ErrorCode.VALIDATION_FAILED,
                            "Board.solution.uci has too many moves (max "
                                    + MAX_SOLUTION_MOVES + ")");
                }
                // Walk the solution off the *current* board state (which
                // already has the prefix moves applied above). We mutate a
                // defensive clone so the prefix stays clean for callers
                // that want to reuse the board object — here we throw it
                // away, but the cleanliness matters if we ever inline.
                Board probe = new Board();
                probe.loadFromFen(board.getFen());
                ArrayNode outUci = mapper.createArrayNode();
                for (int i = 0; i < uciNode.size(); i++) {
                    String uci = asText(uciNode.get(i),
                            "Board.solution.uci[" + i + "] must be a UCI string");
                    String normalised =
                            ChessMoves.applyLegalMove(probe, uci, "Board.solution.uci[" + i + "]");
                    outUci.add(normalised);
                }
                solutionOut = mapper.createObjectNode();
                solutionOut.set("uci", outUci);
            }
        }

        // --- emit a tidy, canonical object ---
        ObjectNode out = mapper.createObjectNode();
        out.put("startingFen", startingFen);
        ArrayNode moves = mapper.createArrayNode();
        for (String m : normMoves) moves.add(m);
        out.set("moves", moves);
        out.put("orientation", orientation);
        if (solutionOut != null) {
            out.set("solution", solutionOut);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String requireText(JsonNode obj, String field, String message) {
        JsonNode n = obj.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, message);
        }
        String v = n.asText().trim();
        if (v.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, message);
        }
        return v;
    }

    private static String optionalText(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null || n.isNull()) return null;
        if (!n.isTextual()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    field + " must be a string");
        }
        String v = n.asText();
        return v == null ? null : v;
    }

    private static String asText(JsonNode n, String message) {
        if (n == null || n.isNull() || !n.isTextual()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, message);
        }
        String v = n.asText().trim();
        if (v.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, message);
        }
        return v;
    }
}
