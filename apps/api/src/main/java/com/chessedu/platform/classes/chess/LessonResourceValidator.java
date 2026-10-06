package com.chessedu.platform.classes.chess;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.bhlangonijr.chesslib.Board;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Server-side validator for authored content blocks attached to a
 * {@link com.chessedu.platform.classes.model.Lesson}. Sibling to
 * {@link HomeworkResourceValidator}; the same conventions apply:
 *
 * <ul>
 *   <li>Pure function of the incoming payload — returns a normalised
 *       {@link JsonNode} (trimmed strings, lowercased orientation, only
 *       fields we recognise) and throws {@link AppException} with
 *       {@link ErrorCode#VALIDATION_FAILED} on the first problem hit.</li>
 *   <li>Callers persist the returned node, not the raw input.</li>
 *   <li>Adding a new {@code kind} means adding a new {@code validateXxx}
 *       method and routing it from {@link #validate(String, JsonNode)} —
 *       the schema's open-ended {@code kind} VARCHAR(32) supports it
 *       without a migration.</li>
 * </ul>
 *
 * <p>Supported kinds in v1:
 * <ul>
 *   <li>{@code "PROSE"} — markdown body. Length-capped at
 *       {@value #MAX_PROSE_CHARS} chars; rendering-time sanitisation
 *       (rehype-sanitize on the client) is the second line of defence
 *       against script injection, this layer just prevents abusive
 *       payload sizes.</li>
 *   <li>{@code "BOARD_WALKTHROUGH"} — a study-only board (no hidden
 *       solution; the moves are public). Optional per-ply
 *       {@code annotations[]} let the author pin a comment to a
 *       position reached after N moves.</li>
 *   <li>{@code "MCQ"} — multiple-choice question with 2-4 options
 *       and a correct index. Optional explanation surfaces after the
 *       student answers.</li>
 *   <li>{@code "VIDEO"} — external embed. URL host must be on a tight
 *       allowlist (YouTube / Vimeo) so we never let a teacher paste a
 *       link that loads arbitrary JavaScript inside an iframe in our
 *       origin.</li>
 * </ul>
 */
@Component
public class LessonResourceValidator {

    /** Hard cap on PROSE markdown so a single block can't DOS us. */
    private static final int MAX_PROSE_CHARS = 50_000;

    /** Same hard cap as {@link HomeworkResourceValidator} for parity. */
    private static final int MAX_BOARD_MOVES = 200;

    /** Cap on annotations + cap on each annotation's body. */
    private static final int MAX_ANNOTATIONS = 64;
    private static final int MAX_ANNOTATION_CHARS = 200;

    private static final int MAX_QUESTION_CHARS = 500;
    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 4;
    private static final int MAX_OPTION_CHARS = 200;
    private static final int MAX_EXPLANATION_CHARS = 2_000;

    /**
     * Embed allowlist. Hosts are matched case-insensitively against the
     * exact URI host (no subdomain wildcard) — this is conservative on
     * purpose: we add hosts as we need them, not retroactively.
     */
    private static final Set<String> VIDEO_HOST_ALLOWLIST = new HashSet<>(List.of(
            "www.youtube.com",
            "youtube.com",
            "youtu.be",
            "www.vimeo.com",
            "vimeo.com",
            "player.vimeo.com"
    ));

    private final ObjectMapper mapper;

    public LessonResourceValidator(ObjectMapper mapper) {
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
        String normKind = kind.trim().toUpperCase(Locale.ROOT);
        return switch (normKind) {
            case "PROSE" -> validateProsePayload(payload);
            case "BOARD_WALKTHROUGH" -> validateBoardWalkthroughPayload(payload);
            case "MCQ" -> validateMcqPayload(payload);
            case "VIDEO" -> validateVideoPayload(payload);
            default -> throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Unsupported lesson resource kind: " + kind);
        };
    }

    /**
     * The canonical, uppercased kind name as it should be persisted.
     * The DB column is open-ended VARCHAR but we want consistent casing
     * so the service layer can switch on the value without
     * defensiveness. Returns {@code null} on a blank input — caller
     * should already have bounced that with a 400 by then.
     */
    public String normaliseKind(String kind) {
        if (kind == null || kind.isBlank()) return null;
        return kind.trim().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // PROSE
    // ------------------------------------------------------------------

    /**
     * Markdown body. We do not parse the markdown server-side — the
     * client uses {@code react-markdown} + {@code remark-gfm} +
     * {@code rehype-sanitize}, which strips dangerous HTML at render
     * time. Server-side, we only enforce the size cap so a single block
     * can't blow past JSONB / network buffers.
     */
    private JsonNode validateProsePayload(JsonNode payload) {
        String markdown = requireText(payload, "markdown",
                "Prose.markdown is required");
        if (markdown.length() > MAX_PROSE_CHARS) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Prose.markdown is too long (max " + MAX_PROSE_CHARS + " chars)");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("markdown", markdown);
        return out;
    }

    // ------------------------------------------------------------------
    // BOARD_WALKTHROUGH
    // ------------------------------------------------------------------

    /**
     * Study-only board: starting FEN + optional UCI prefix (legal moves
     * walked from the starting position) + orientation + optional
     * per-ply {@code annotations[]} of the form
     * {@code [{"ply": 0, "text": "..."}]} where {@code ply} is a
     * 0-based index into the position sequence (0 = the starting
     * position itself; {@code moves.length} = the position after the
     * last move). No hidden solution — walkthroughs are public study
     * material; if the author wants a hidden answer they should be
     * authoring a {@code BOARD} homework block instead.
     */
    private JsonNode validateBoardWalkthroughPayload(JsonNode payload) {
        String startingFen = requireText(payload, "startingFen",
                "BoardWalkthrough.startingFen is required");

        Board board = new Board();
        try {
            board.loadFromFen(startingFen);
        } catch (RuntimeException e) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "BoardWalkthrough.startingFen is not a legal position");
        }

        // --- moves ---
        List<String> normMoves = new ArrayList<>();
        JsonNode movesNode = payload.get("moves");
        if (movesNode != null && !movesNode.isNull()) {
            if (!movesNode.isArray()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "BoardWalkthrough.moves must be an array of UCI strings");
            }
            if (movesNode.size() > MAX_BOARD_MOVES) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "BoardWalkthrough.moves has too many moves (max "
                                + MAX_BOARD_MOVES + ")");
            }
            for (int i = 0; i < movesNode.size(); i++) {
                String uci = asText(movesNode.get(i),
                        "BoardWalkthrough.moves[" + i + "] must be a UCI string");
                String normalised = ChessMoves.applyLegalMove(
                        board, uci, "BoardWalkthrough.moves[" + i + "]");
                normMoves.add(normalised);
            }
        }

        // --- orientation ---
        String orientation = optionalText(payload, "orientation");
        if (orientation == null) {
            orientation = "white";
        } else {
            orientation = orientation.trim().toLowerCase(Locale.ROOT);
            if (!orientation.equals("white") && !orientation.equals("black")) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "BoardWalkthrough.orientation must be 'white' or 'black'");
            }
        }

        // --- annotations (optional) ---
        // Valid ply range is [0, normMoves.size()] inclusive: ply 0 is
        // the starting position, ply == moves.length is the position
        // *after* the last move. Annotations don't have to be ordered
        // or unique-by-ply; the renderer handles dedup / ordering.
        ArrayNode annotationsOut = null;
        JsonNode annotationsNode = payload.get("annotations");
        if (annotationsNode != null && !annotationsNode.isNull()) {
            if (!annotationsNode.isArray()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "BoardWalkthrough.annotations must be an array");
            }
            if (annotationsNode.size() > MAX_ANNOTATIONS) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "BoardWalkthrough.annotations has too many entries (max "
                                + MAX_ANNOTATIONS + ")");
            }
            annotationsOut = mapper.createArrayNode();
            int maxPly = normMoves.size();
            for (int i = 0; i < annotationsNode.size(); i++) {
                JsonNode a = annotationsNode.get(i);
                String ctx = "BoardWalkthrough.annotations[" + i + "]";
                if (a == null || a.isNull() || !a.isObject()) {
                    throw new AppException(ErrorCode.VALIDATION_FAILED,
                            ctx + " must be an object");
                }
                JsonNode plyNode = a.get("ply");
                if (plyNode == null || plyNode.isNull() || !plyNode.isInt()) {
                    throw new AppException(ErrorCode.VALIDATION_FAILED,
                            ctx + ".ply must be an integer");
                }
                int ply = plyNode.asInt();
                if (ply < 0 || ply > maxPly) {
                    throw new AppException(ErrorCode.VALIDATION_FAILED,
                            ctx + ".ply must be between 0 and " + maxPly);
                }
                String text = requireText(a, "text", ctx + ".text is required");
                if (text.length() > MAX_ANNOTATION_CHARS) {
                    throw new AppException(ErrorCode.VALIDATION_FAILED,
                            ctx + ".text is too long (max "
                                    + MAX_ANNOTATION_CHARS + " chars)");
                }
                ObjectNode out = mapper.createObjectNode();
                out.put("ply", ply);
                out.put("text", text);
                annotationsOut.add(out);
            }
        }

        // --- emit a tidy, canonical object ---
        ObjectNode out = mapper.createObjectNode();
        out.put("startingFen", startingFen);
        ArrayNode moves = mapper.createArrayNode();
        for (String m : normMoves) moves.add(m);
        out.set("moves", moves);
        out.put("orientation", orientation);
        if (annotationsOut != null) {
            out.set("annotations", annotationsOut);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // MCQ
    // ------------------------------------------------------------------

    /**
     * Multiple-choice question. {@code options.length} must be in
     * {@code [MIN_OPTIONS, MAX_OPTIONS]} and {@code correctIndex} must
     * point inside that range. Options are deduplicated by trimmed
     * lowercase content — two options that read the same after
     * normalisation are a tell-tale authoring mistake (the student
     * couldn't tell them apart) so we reject at write time.
     */
    private JsonNode validateMcqPayload(JsonNode payload) {
        String question = requireText(payload, "question",
                "Mcq.question is required");
        if (question.length() > MAX_QUESTION_CHARS) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Mcq.question is too long (max " + MAX_QUESTION_CHARS + " chars)");
        }

        JsonNode optionsNode = payload.get("options");
        if (optionsNode == null || optionsNode.isNull() || !optionsNode.isArray()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Mcq.options must be an array");
        }
        int n = optionsNode.size();
        if (n < MIN_OPTIONS || n > MAX_OPTIONS) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Mcq.options must have between " + MIN_OPTIONS + " and "
                            + MAX_OPTIONS + " entries");
        }
        Set<String> seen = new HashSet<>();
        ArrayNode optionsOut = mapper.createArrayNode();
        for (int i = 0; i < n; i++) {
            String opt = asText(optionsNode.get(i),
                    "Mcq.options[" + i + "] must be a non-empty string");
            if (opt.length() > MAX_OPTION_CHARS) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Mcq.options[" + i + "] is too long (max "
                                + MAX_OPTION_CHARS + " chars)");
            }
            String key = opt.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Mcq.options[" + i + "] duplicates an earlier option");
            }
            optionsOut.add(opt);
        }

        JsonNode correctIndexNode = payload.get("correctIndex");
        if (correctIndexNode == null || correctIndexNode.isNull()
                || !correctIndexNode.isInt()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Mcq.correctIndex must be an integer");
        }
        int correctIndex = correctIndexNode.asInt();
        if (correctIndex < 0 || correctIndex >= n) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Mcq.correctIndex must be between 0 and " + (n - 1));
        }

        // Optional explanation surfaces after the student answers.
        String explanation = optionalText(payload, "explanation");
        if (explanation != null) {
            explanation = explanation.trim();
            if (explanation.isEmpty()) {
                explanation = null;
            } else if (explanation.length() > MAX_EXPLANATION_CHARS) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Mcq.explanation is too long (max "
                                + MAX_EXPLANATION_CHARS + " chars)");
            }
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("question", question);
        out.set("options", optionsOut);
        out.put("correctIndex", correctIndex);
        if (explanation != null) {
            out.put("explanation", explanation);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // VIDEO
    // ------------------------------------------------------------------

    /**
     * External video embed. URL must parse, must be {@code https}, and
     * the host must appear in {@link #VIDEO_HOST_ALLOWLIST}. We derive
     * the {@code provider} from the host so the client doesn't have to
     * trust a self-reported value — if the author claims a Vimeo URL is
     * YouTube, we fix the discrepancy here.
     *
     * <p>The allowlist exists for two reasons: (1) iframe origin
     * isolation — random domains may serve cross-origin frame-bust
     * scripts that break the surrounding page; (2) compliance — letting
     * a teacher embed arbitrary URLs lets them embed anything, full
     * stop, which we don't want to vouch for.
     */
    private JsonNode validateVideoPayload(JsonNode payload) {
        String rawUrl = requireText(payload, "url", "Video.url is required");
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Video.url is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Video.url must use https://");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Video.url must include a host");
        }
        String hostLower = host.toLowerCase(Locale.ROOT);
        if (!VIDEO_HOST_ALLOWLIST.contains(hostLower)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Video.url host is not on the embed allowlist: " + host);
        }

        // Derive provider from the host. We trust the host, not whatever
        // the author typed — a YouTube link gets a "youtube" provider
        // even if the input had {"provider": "vimeo"}.
        String provider;
        if (hostLower.equals("youtu.be")
                || hostLower.equals("youtube.com")
                || hostLower.equals("www.youtube.com")) {
            provider = "youtube";
        } else if (hostLower.equals("vimeo.com")
                || hostLower.equals("www.vimeo.com")
                || hostLower.equals("player.vimeo.com")) {
            provider = "vimeo";
        } else {
            // Unreachable thanks to the allowlist above, but keep the
            // belt-and-braces branch so future allowlist changes can't
            // silently produce a null provider.
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Video.url host has no known provider: " + host);
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("url", uri.toString());
        out.put("provider", provider);
        return out;
    }

    // ------------------------------------------------------------------
    // helpers — same shape as HomeworkResourceValidator's privates
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
        return n.asText();
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
