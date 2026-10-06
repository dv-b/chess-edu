package com.chessedu.platform.classes.chess;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;

import java.util.List;

/**
 * Pure helpers shared by the homework chess validators
 * ({@link HomeworkResourceValidator} for authored content,
 * {@link BlockAttemptValidator} for student submissions).
 *
 * <p>Stateless on purpose — instantiating a new {@link Board} per call is
 * cheap, and giving callers a "position walker" style API would leak chesslib
 * into the service layer. Keep this class focused on move legality checks.
 */
public final class ChessMoves {

    private ChessMoves() {}

    /**
     * Parse {@code uci} (e.g. {@code "e2e4"} or {@code "e7e8q"}) as a move for
     * the side currently to move on {@code board} and apply it. Throws
     * {@link AppException} with {@code ctx} in the message if the move is
     * malformed or illegal. Returns the canonicalised (lowercased, trimmed)
     * UCI string so callers can persist a tidy representation.
     *
     * <p>Uses {@link MoveGenerator#generateLegalMoves(Board)} for containment
     * rather than trusting chesslib's weaker pseudo-legal acceptance — this
     * catches illegal self-check moves that {@link Board#doMove(Move)} would
     * otherwise happily play.
     */
    public static String applyLegalMove(Board board, String uci, String ctx) {
        if (uci == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, ctx + " must be a UCI string");
        }
        String trimmed = uci.trim().toLowerCase();
        if (trimmed.length() < 4 || trimmed.length() > 5) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    ctx + " is not a well-formed UCI move: '" + uci + "'");
        }
        Move move;
        try {
            move = new Move(trimmed, board.getSideToMove());
        } catch (RuntimeException e) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    ctx + " is not a well-formed UCI move: '" + uci + "'");
        }
        // Generate legal moves from the current position and check
        // containment. chesslib's Move#equals uses from/to/promotion so
        // this catches both pseudo-legal and truly illegal moves
        // (including moves that leave the king in check).
        //
        // Note: MoveGenerator.generateLegalMoves throws MoveGeneratorException,
        // which is itself a RuntimeException in chesslib 1.3.x, so catching
        // RuntimeException covers both the generator failure and any ad-hoc
        // NPE / IllegalArgument thrown while generating (e.g. from a position
        // that slipped past loadFromFen but is internally inconsistent).
        List<Move> legal;
        try {
            legal = MoveGenerator.generateLegalMoves(board);
        } catch (RuntimeException e) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    ctx + ": cannot generate legal moves for this position");
        }
        if (!legal.contains(move)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    ctx + " is not a legal move from the running position: '" + uci + "'");
        }
        try {
            board.doMove(move);
        } catch (RuntimeException e) {
            // Shouldn't happen after the containment check, but guard anyway.
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    ctx + " could not be applied: '" + uci + "'");
        }
        return trimmed;
    }
}
