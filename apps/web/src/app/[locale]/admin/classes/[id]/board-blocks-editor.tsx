"use client";

import { useMemo, useState } from "react";
import { Chess } from "chess.js";
import {
  Chessboard,
  ChessboardProvider,
  SparePiece,
} from "react-chessboard";

/**
 * Author-side chess content editor for the Homework forms. Wraps
 * a list of BOARD blocks (v1's only kind); each block captures:
 * <ul>
 *   <li>a starting position (FEN)</li>
 *   <li>an optional move prefix (the tutor plays it on the board or
 *       pastes PGN; both paths produce a canonical UCI list)</li>
 *   <li>board orientation (affects the student viewer)</li>
 *   <li>an optional display title</li>
 *   <li>an optional hidden solution — a UCI continuation played from the
 *       position reached after the prefix, entered the same way.</li>
 * </ul>
 *
 * <p>The editor is a controlled component: the parent owns the {@link BoardBlock}
 * list and gets it back on every mutation. The editor never posts on its
 * own — the parent form serialises the list to JSON and submits it via the
 * existing useActionState plumbing.
 *
 * <p>All FEN / UCI handling runs through chess.js so the UI only offers
 * legal moves, but the backend validator
 * ({@code HomeworkResourceValidator}) is the ultimate source of truth —
 * anything that slips past this UI still 400s.
 */

export const STARTING_FEN =
  "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

export type BoardBlock = {
  /** Stable list key. Not sent to the backend. */
  clientId: string;
  title: string;
  startingFen: string;
  /** UCI moves played on top of startingFen. Every entry is legal. */
  moves: string[];
  orientation: "white" | "black";
  /** Optional UCI continuation played from the position after {@link moves}. */
  solution: string[] | null;
};

export type BoardBlocksEditorLabels = {
  heading: string;
  hint: string;
  addBoard: string;
  removeBoard: string;
  /** ARIA label on the "move this block up one position" button. */
  moveUp: string;
  /** ARIA label on the "move this block down one position" button. */
  moveDown: string;
  blockTitleLabel: string;
  blockTitlePlaceholder: string;
  startingFenLabel: string;
  resetToStart: string;
  useCurrentAsStart: string;
  orientationLabel: string;
  orientationWhite: string;
  orientationBlack: string;
  movesHeading: string;
  movesCount: string; // pluralised via ICU {count}
  movesEmpty: string;
  pastePgnLabel: string;
  pastePgnPlaceholder: string;
  pastePgnApply: string;
  pastePgnReplace: string;
  pastePgnAppend: string;
  undoLastMove: string;
  clearAllMoves: string;
  solutionHeading: string;
  solutionHint: string;
  enableSolution: string;
  disableSolution: string;
  solutionCount: string;
  solutionEmpty: string;
  solutionUndo: string;
  solutionClear: string;
  sideToMove: string;
  sideWhite: string;
  sideBlack: string;
  invalidFen: string;
  invalidPgn: string;
  noBlocks: string;
  /* Mode tabs */
  modeSetup: string;
  modeMoves: string;
  /* Setup-mode labels */
  setupHint: string;
  setupFillStart: string;
  setupEmpty: string;
  setupPaletteHint: string;
  setupRemoveHint: string;
  setupCastling: string;
  setupCastleK: string;
  setupCastleQ: string;
  setupCastlek: string;
  setupCastleq: string;
  setupSideToMove: string;
  setupAdvancedShow: string;
  setupAdvancedHide: string;
  setupIllegalPositionNotice: string;
  switchToSetupWarning: string;
};

// ------------------------------------------------------------------
// Helpers
// ------------------------------------------------------------------

let clientIdCounter = 0;
/** Stable list key that survives re-renders but doesn't need crypto. */
function nextClientId(): string {
  clientIdCounter += 1;
  return `bk-${Date.now().toString(36)}-${clientIdCounter}`;
}

/**
 * Play {@code moves} on top of {@code startFen}. Returns the resulting
 * chess.js instance, or null if any move is illegal / the FEN is bad.
 */
function play(startFen: string, moves: string[]): Chess | null {
  let chess: Chess;
  try {
    chess = new Chess(startFen);
  } catch {
    return null;
  }
  for (const uci of moves) {
    try {
      const mv = chess.move({
        from: uci.slice(0, 2),
        to: uci.slice(2, 4),
        promotion: uci.length === 5 ? uci[4] : undefined,
      });
      if (!mv) return null;
    } catch {
      return null;
    }
  }
  return chess;
}

/**
 * Like {@link play} but returns the FEN of the final position, or null on
 * failure. Useful for the chessboard's {@code position} prop.
 */
function fenAfter(startFen: string, moves: string[]): string | null {
  const c = play(startFen, moves);
  return c ? c.fen() : null;
}

/** True iff {@code fen} parses as a legal chess position. */
function isValidFen(fen: string): boolean {
  try {
    new Chess(fen);
    return true;
  } catch {
    return false;
  }
}

// ------------------------------------------------------------------
// FEN composition helpers (for the drag-and-drop position editor).
// These are intentionally permissive — they let the author build
// intermediate states that aren't yet legal chess positions (e.g. no
// kings while they're arranging pieces). The block's startingFen is
// ultimately validated by chess.js when the author switches to the
// "Moves" tab and by the backend on save.
// ------------------------------------------------------------------

/** react-chessboard v5 piece notation: "wK", "bP", etc. */
type PieceCode =
  | "wK" | "wQ" | "wR" | "wB" | "wN" | "wP"
  | "bK" | "bQ" | "bR" | "bB" | "bN" | "bP";

type PieceMap = Record<string, PieceCode>;

type CastleFlags = { K: boolean; Q: boolean; k: boolean; q: boolean };

const EMPTY_FEN = "8/8/8/8/8/8/8/8 w - - 0 1";
const ALL_FILES = ["a", "b", "c", "d", "e", "f", "g", "h"] as const;

/**
 * Parse a FEN into the shape the position editor wants to edit:
 * a {@code PieceMap} keyed by square, the side to move, and castling flags.
 *
 * <p>Falls back to an empty board for anything that doesn't parse as a
 * placement string. This keeps the setup editor usable even after the
 * author has typed garbage into the advanced FEN input.
 */
function parseFenForSetup(fen: string): {
  pieces: PieceMap;
  side: "w" | "b";
  castling: CastleFlags;
} {
  const parts = (fen || "").trim().split(/\s+/);
  const [placement = "", side = "w", castle = "KQkq"] = parts;
  const pieces: PieceMap = {};
  const ranks = placement.split("/");
  if (ranks.length === 8) {
    for (let r = 0; r < 8; r += 1) {
      // Safe by the length check above; assertion keeps TS happy under
      // `noUncheckedIndexedAccess`.
      const rankStr = ranks[r]!;
      let file = 0;
      for (const ch of rankStr) {
        if (/\d/.test(ch)) {
          file += Number(ch);
        } else if (/[prnbqkPRNBQK]/.test(ch)) {
          if (file > 7) break;
          const square = `${ALL_FILES[file]}${8 - r}`;
          const color = ch === ch.toUpperCase() ? "w" : "b";
          const type = ch.toUpperCase();
          pieces[square] = `${color}${type}` as PieceCode;
          file += 1;
        } else {
          // Unknown char — skip defensively.
          file += 1;
        }
      }
    }
  }
  return {
    pieces,
    side: side === "b" ? "b" : "w",
    castling: {
      K: castle.includes("K"),
      Q: castle.includes("Q"),
      k: castle.includes("k"),
      q: castle.includes("q"),
    },
  };
}

/**
 * Castling rights are only meaningful if the relevant king and rook are
 * on their starting squares. This clamp is applied every time we
 * recompose the FEN, so flipping a piece off its home square
 * automatically clears the matching flag.
 */
function sanitizeCastling(pieces: PieceMap, c: CastleFlags): CastleFlags {
  const whiteKingHome = pieces["e1"] === "wK";
  const blackKingHome = pieces["e8"] === "bK";
  return {
    K: c.K && whiteKingHome && pieces["h1"] === "wR",
    Q: c.Q && whiteKingHome && pieces["a1"] === "wR",
    k: c.k && blackKingHome && pieces["h8"] === "bR",
    q: c.q && blackKingHome && pieces["a8"] === "bR",
  };
}

/** Serialise a {@link PieceMap} + meta fields back to a FEN string. */
function composeFen(
  pieces: PieceMap,
  side: "w" | "b",
  castling: CastleFlags,
): string {
  const ranks: string[] = [];
  for (let r = 8; r >= 1; r -= 1) {
    let row = "";
    let empty = 0;
    for (let f = 0; f < 8; f += 1) {
      const sq = `${ALL_FILES[f]}${r}`;
      const piece = pieces[sq];
      if (!piece) {
        empty += 1;
        continue;
      }
      if (empty > 0) {
        row += String(empty);
        empty = 0;
      }
      // piece is a 2-char PieceCode like "wP" / "bN" — index 1 is always
      // defined. Assertion satisfies noUncheckedIndexedAccess.
      const letter = piece[1]!;
      row += piece[0] === "w" ? letter : letter.toLowerCase();
    }
    if (empty > 0) row += String(empty);
    ranks.push(row);
  }
  const placement = ranks.join("/");
  const clean = sanitizeCastling(pieces, castling);
  const castleStr =
    (clean.K ? "K" : "") +
    (clean.Q ? "Q" : "") +
    (clean.k ? "k" : "") +
    (clean.q ? "q" : "") || "-";
  return `${placement} ${side} ${castleStr} - 0 1`;
}

/** The piece order shown in each palette row, left to right. */
const PALETTE_ORDER: readonly PieceCode[] = [
  "wK", "wQ", "wR", "wB", "wN", "wP",
] as const;

/** Palette for the colour opposite {@code orientation}. */
function topPalette(orientation: "white" | "black"): readonly PieceCode[] {
  return orientation === "white"
    ? (["bK", "bQ", "bR", "bB", "bN", "bP"] as const)
    : PALETTE_ORDER;
}

/** Palette for the colour matching {@code orientation}. */
function bottomPalette(orientation: "white" | "black"): readonly PieceCode[] {
  return orientation === "white"
    ? PALETTE_ORDER
    : (["bK", "bQ", "bR", "bB", "bN", "bP"] as const);
}

/**
 * Parse a PGN (headers or raw move-text; SAN tokens separated by spaces)
 * and replay the moves against {@code startFen}, yielding a list of
 * canonicalised UCI strings. Returns null on parse failure OR on the
 * first move that isn't legal from {@code startFen}.
 *
 * <p>We deliberately don't trust the PGN's own [FEN] header: the block's
 * declared {@code startFen} is authoritative, and we want obvious
 * feedback when the author pastes a game that doesn't actually start
 * from their declared position.
 */
function pgnToUci(startFen: string, pgn: string): string[] | null {
  const trimmed = pgn.trim();
  if (!trimmed) return [];
  // Step 1: let chess.js do the full PGN parse (handles headers,
  // comments, move numbers, NAGs, variations, etc.) and hand us back a
  // sequence of SAN tokens.
  const parser = new Chess();
  try {
    parser.loadPgn(trimmed, { strict: false });
  } catch {
    return null;
  }
  const sanTokens = parser.history();
  if (sanTokens.length === 0) return [];
  // Step 2: replay SAN tokens on top of the block's declared FEN. Any
  // move that isn't legal here is a parse failure from the author's POV.
  let player: Chess;
  try {
    player = new Chess(startFen);
  } catch {
    return null;
  }
  const uciMoves: string[] = [];
  for (const san of sanTokens) {
    let mv;
    try {
      mv = player.move(san);
    } catch {
      return null;
    }
    if (!mv) return null;
    const promo = mv.promotion ? mv.promotion : "";
    uciMoves.push(`${mv.from}${mv.to}${promo}`);
  }
  return uciMoves;
}

function createEmptyBlock(): BoardBlock {
  return {
    clientId: nextClientId(),
    title: "",
    startingFen: STARTING_FEN,
    moves: [],
    orientation: "white",
    solution: null,
  };
}

/**
 * Hydrate blocks from a backend {@code HomeworkDetail.resources} list so
 * the edit form can pre-fill. Unknown kinds and malformed BOARD blocks are
 * skipped rather than crashing the editor.
 */
export function blocksFromResources(
  resources: Array<{
    kind: string;
    title: string | null;
    payload: Record<string, unknown>;
  }>,
): BoardBlock[] {
  const out: BoardBlock[] = [];
  for (const r of resources) {
    if (r.kind.toUpperCase() !== "BOARD") continue;
    const p = r.payload ?? {};
    const fen =
      typeof p.startingFen === "string" ? (p.startingFen as string) : STARTING_FEN;
    const mv = Array.isArray(p.moves)
      ? (p.moves as unknown[])
          .filter((x): x is string => typeof x === "string")
          .map((s) => s.toLowerCase())
      : [];
    const orient =
      typeof p.orientation === "string" &&
      (p.orientation as string).toLowerCase() === "black"
        ? "black"
        : "white";
    let solution: string[] | null = null;
    const sol = p.solution as { uci?: unknown } | undefined;
    if (sol && Array.isArray(sol.uci)) {
      solution = (sol.uci as unknown[])
        .filter((x): x is string => typeof x === "string")
        .map((s) => s.toLowerCase());
    }
    out.push({
      clientId: nextClientId(),
      title: r.title ?? "",
      startingFen: fen,
      moves: mv,
      orientation: orient,
      solution,
    });
  }
  return out;
}

/**
 * Pack the editor state into the shape the server action + backend expect.
 * {@code null}/empty title drops off; an empty moves array is preserved
 * (the validator normalises it anyway).
 */
export function resourcesFromBlocks(
  blocks: BoardBlock[],
): Array<{
  kind: "BOARD";
  title: string | null;
  payload: Record<string, unknown>;
}> {
  return blocks.map((b) => {
    const payload: Record<string, unknown> = {
      startingFen: b.startingFen,
      moves: b.moves,
      orientation: b.orientation,
    };
    if (b.solution && b.solution.length > 0) {
      payload.solution = { uci: b.solution };
    }
    return {
      kind: "BOARD",
      title: b.title.trim() ? b.title.trim() : null,
      payload,
    };
  });
}

// ------------------------------------------------------------------
// Editor
// ------------------------------------------------------------------

export function BoardBlocksEditor({
  blocks,
  onChange,
  labels,
}: {
  blocks: BoardBlock[];
  onChange: (next: BoardBlock[]) => void;
  labels: BoardBlocksEditorLabels;
}) {
  function update(idx: number, patch: Partial<BoardBlock>): void {
    const next = blocks.map((b, i) => (i === idx ? { ...b, ...patch } : b));
    onChange(next);
  }
  function remove(idx: number): void {
    onChange(blocks.filter((_, i) => i !== idx));
  }
  function add(): void {
    onChange([...blocks, createEmptyBlock()]);
  }
  /**
   * Swap an adjacent pair of blocks. The new ordering persists on the
   * next save via the existing replace-on-write `resources` PATCH path
   * — the backend re-derives `position` from the list index, so we
   * don't need to write to it here.
   */
  function move(from: number, to: number): void {
    if (from === to || from < 0 || to < 0) return;
    if (from >= blocks.length || to >= blocks.length) return;
    const next = blocks.slice();
    const [pulled] = next.splice(from, 1);
    if (!pulled) return;
    next.splice(to, 0, pulled);
    onChange(next);
  }

  return (
    <section className="hw-boards">
      <header className="hw-boards__header">
        <h4 className="hw-boards__title">{labels.heading}</h4>
        <p className="hw-boards__hint">{labels.hint}</p>
      </header>
      {blocks.length === 0 ? (
        <p className="hw-boards__empty">{labels.noBlocks}</p>
      ) : (
        <ol className="hw-boards__list">
          {blocks.map((b, i) => (
            <li key={b.clientId} className="hw-boards__item">
              <div className="hw-boards__reorder">
                <button
                  type="button"
                  className="hw-boards__reorder-btn"
                  onClick={() => move(i, i - 1)}
                  disabled={i === 0}
                  aria-label={labels.moveUp}
                  title={labels.moveUp}
                >
                  ↑
                </button>
                <button
                  type="button"
                  className="hw-boards__reorder-btn"
                  onClick={() => move(i, i + 1)}
                  disabled={i === blocks.length - 1}
                  aria-label={labels.moveDown}
                  title={labels.moveDown}
                >
                  ↓
                </button>
              </div>
              <BoardBlockCard
                index={i}
                block={b}
                labels={labels}
                onChange={(patch) => update(i, patch)}
                onRemove={() => remove(i)}
              />
            </li>
          ))}
        </ol>
      )}
      <div className="hw-boards__footer">
        <button
          type="button"
          className="btn btn--ghost btn--sm"
          onClick={add}
        >
          {labels.addBoard}
        </button>
      </div>
    </section>
  );
}

function BoardBlockCard({
  index,
  block,
  labels,
  onChange,
  onRemove,
}: {
  index: number;
  block: BoardBlock;
  labels: BoardBlocksEditorLabels;
  onChange: (patch: Partial<BoardBlock>) => void;
  onRemove: () => void;
}) {
  // Which editing mode is active: "setup" (piece palette, drag-and-drop
  // to place pieces) vs "moves" (existing legal-move recorder).
  //
  // We default to setup when there are no moves yet (common for a fresh
  // block) and to moves otherwise, so an author re-opening an existing
  // puzzle lands on the move list they were working on.
  const [mode, setMode] = useState<"setup" | "moves">(
    block.moves.length === 0 ? "setup" : "moves",
  );
  const [advancedFenOpen, setAdvancedFenOpen] = useState(false);

  // Local, draft-style state for the FEN input so the board doesn't explode
  // while the user is mid-typing. We commit to the canonical block.startingFen
  // only once the draft parses cleanly.
  const [fenDraft, setFenDraft] = useState<string>(block.startingFen);
  const [fenDirty, setFenDirty] = useState<boolean>(false);
  const fenDraftValid = useMemo(() => isValidFen(fenDraft), [fenDraft]);

  // Paste PGN editor UI state (kept local — we only emit on apply).
  const [pgnDraft, setPgnDraft] = useState<string>("");
  const [pgnError, setPgnError] = useState<string | null>(null);

  // Current position after the move prefix has been played. Drives the
  // board's `position` prop and also tells chess.js whose turn it is so
  // onPieceDrop can accept / reject drops.
  const prefixFen = useMemo(
    () => fenAfter(block.startingFen, block.moves) ?? block.startingFen,
    [block.startingFen, block.moves],
  );

  const prefixChess = useMemo(() => {
    try {
      return new Chess(prefixFen);
    } catch {
      return null;
    }
  }, [prefixFen]);

  const sideToMoveLabel = !prefixChess
    ? ""
    : prefixChess.turn() === "w"
      ? labels.sideWhite
      : labels.sideBlack;

  // The FEN used to position the solution board. If the solution is
  // enabled, we play the solution moves on top of prefixFen.
  const solutionFen = useMemo(() => {
    if (!block.solution) return null;
    return fenAfter(prefixFen, block.solution) ?? prefixFen;
  }, [prefixFen, block.solution]);

  const solutionChess = useMemo(() => {
    if (!solutionFen) return null;
    try {
      return new Chess(solutionFen);
    } catch {
      return null;
    }
  }, [solutionFen]);

  const solutionSideLabel = !solutionChess
    ? ""
    : solutionChess.turn() === "w"
      ? labels.sideWhite
      : labels.sideBlack;

  // --- callbacks ---

  function commitFenDraft(): void {
    if (!fenDirty) return;
    if (isValidFen(fenDraft)) {
      onChange({ startingFen: fenDraft, moves: [], solution: null });
      setFenDirty(false);
    }
  }

  function resetToStart(): void {
    setFenDraft(STARTING_FEN);
    setFenDirty(false);
    onChange({ startingFen: STARTING_FEN, moves: [], solution: null });
  }

  /**
   * Snapshot the current (prefix-applied) position as the new starting FEN.
   * Useful when the author plays a few scaffolding moves then decides the
   * position itself is the puzzle start.
   */
  function useCurrentAsStart(): void {
    const nextFen = prefixFen;
    setFenDraft(nextFen);
    setFenDirty(false);
    onChange({ startingFen: nextFen, moves: [], solution: null });
  }

  /**
   * Commit a setup-mode position edit. All edits through the palette /
   * side / castling controls flow through here so the semantics are
   * consistent: changing the starting position invalidates any recorded
   * moves (chess.js can't replay them on top of a new FEN), so we clear
   * them.
   */
  function commitSetupFen(nextFen: string): void {
    setFenDraft(nextFen);
    setFenDirty(false);
    onChange({ startingFen: nextFen, moves: [], solution: null });
  }

  function setupEmpty(): void {
    commitSetupFen(EMPTY_FEN);
  }

  function setupFillStart(): void {
    commitSetupFen(STARTING_FEN);
  }

  function switchMode(next: "setup" | "moves"): void {
    if (next === mode) return;
    // Switching to Setup is informational — we don't clear moves just
    // because the author peeked at the palette. Only an actual palette
    // edit clears moves (via commitSetupFen).
    setMode(next);
  }

  function handlePieceDrop({
    sourceSquare,
    targetSquare,
  }: {
    sourceSquare: string;
    targetSquare: string | null;
  }): boolean {
    if (!targetSquare) return false;
    if (!prefixChess) return false;
    // Clone so a rejected move doesn't leave chess.js in a weird state.
    let clone: Chess;
    try {
      clone = new Chess(prefixChess.fen());
    } catch {
      return false;
    }
    let result;
    try {
      result = clone.move({
        from: sourceSquare,
        to: targetSquare,
        promotion: "q",
      });
    } catch {
      return false;
    }
    if (!result) return false;
    const promo = result.promotion ? result.promotion : "";
    const uci = `${result.from}${result.to}${promo}`;
    onChange({ moves: [...block.moves, uci] });
    return true;
  }

  function undoLast(): void {
    if (block.moves.length === 0) return;
    onChange({ moves: block.moves.slice(0, -1) });
  }

  function clearAll(): void {
    onChange({ moves: [], solution: block.solution ? [] : null });
  }

  function applyPgn(mode: "replace" | "append"): void {
    const start =
      mode === "replace"
        ? block.startingFen
        : prefixFen;
    const uci = pgnToUci(start, pgnDraft);
    if (uci === null) {
      setPgnError(labels.invalidPgn);
      return;
    }
    setPgnError(null);
    if (mode === "replace") {
      onChange({ moves: uci, solution: block.solution ? [] : null });
    } else {
      onChange({ moves: [...block.moves, ...uci] });
    }
    setPgnDraft("");
  }

  // Solution drop handler — same idea but writes into solution list.
  function handleSolutionDrop({
    sourceSquare,
    targetSquare,
  }: {
    sourceSquare: string;
    targetSquare: string | null;
  }): boolean {
    if (!targetSquare || !solutionChess || !block.solution) return false;
    let clone: Chess;
    try {
      clone = new Chess(solutionChess.fen());
    } catch {
      return false;
    }
    let result;
    try {
      result = clone.move({
        from: sourceSquare,
        to: targetSquare,
        promotion: "q",
      });
    } catch {
      return false;
    }
    if (!result) return false;
    const promo = result.promotion ? result.promotion : "";
    onChange({ solution: [...block.solution, `${result.from}${result.to}${promo}`] });
    return true;
  }

  function undoSolutionLast(): void {
    if (!block.solution || block.solution.length === 0) return;
    onChange({ solution: block.solution.slice(0, -1) });
  }

  function clearSolution(): void {
    if (!block.solution) return;
    onChange({ solution: [] });
  }

  function toggleSolution(): void {
    onChange({ solution: block.solution ? null : [] });
  }

  // --- render ---

  // The board's starting FEN may be invalid mid-edit (e.g. the author is
  // still arranging pieces and hasn't placed a king). We still render the
  // setup UI — the moves tab is what requires a legal position.
  const startingPositionValid = isValidFen(block.startingFen);

  return (
    <div className="hw-board-card">
      <div className="hw-board-card__head">
        <strong className="hw-board-card__num">#{index + 1}</strong>
        <label className="sched-field hw-board-card__title">
          <span className="sched-field__label">{labels.blockTitleLabel}</span>
          <input
            type="text"
            value={block.title}
            maxLength={200}
            placeholder={labels.blockTitlePlaceholder}
            onChange={(e) => onChange({ title: e.target.value })}
          />
        </label>
        <fieldset className="hw-board-card__orientation">
          <legend className="sched-field__label">{labels.orientationLabel}</legend>
          <label>
            <input
              type="radio"
              name={`orient-${block.clientId}`}
              value="white"
              checked={block.orientation === "white"}
              onChange={() => onChange({ orientation: "white" })}
            />
            {labels.orientationWhite}
          </label>
          <label>
            <input
              type="radio"
              name={`orient-${block.clientId}`}
              value="black"
              checked={block.orientation === "black"}
              onChange={() => onChange({ orientation: "black" })}
            />
            {labels.orientationBlack}
          </label>
        </fieldset>
        <button
          type="button"
          className="btn btn--ghost btn--sm hw-board-card__remove"
          onClick={onRemove}
        >
          {labels.removeBoard}
        </button>
      </div>

      <div
        role="tablist"
        aria-label={labels.heading}
        className="hw-board-card__tabs"
      >
        <button
          type="button"
          role="tab"
          aria-selected={mode === "setup"}
          className={
            "hw-board-card__tab" +
            (mode === "setup" ? " hw-board-card__tab--active" : "")
          }
          onClick={() => switchMode("setup")}
        >
          {labels.modeSetup}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={mode === "moves"}
          className={
            "hw-board-card__tab" +
            (mode === "moves" ? " hw-board-card__tab--active" : "")
          }
          onClick={() => switchMode("moves")}
        >
          {labels.modeMoves}
        </button>
      </div>

      {mode === "setup" ? (
        <SetupPanel
          block={block}
          labels={labels}
          commitSetupFen={commitSetupFen}
          setupEmpty={setupEmpty}
          setupFillStart={setupFillStart}
          advancedOpen={advancedFenOpen}
          setAdvancedOpen={setAdvancedFenOpen}
          fenDraft={fenDraft}
          setFenDraft={setFenDraft}
          fenDirty={fenDirty}
          setFenDirty={setFenDirty}
          fenDraftValid={fenDraftValid}
          commitFenDraft={commitFenDraft}
        />
      ) : (
        <>
          {!startingPositionValid ? (
            <p className="hw-board-card__notice" role="alert">
              {labels.setupIllegalPositionNotice}
            </p>
          ) : null}
          <div className="hw-board-card__body">
            <div className="hw-board-card__sidebar">
              <div className="hw-board-card__fen-actions">
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  onClick={resetToStart}
                >
                  {labels.resetToStart}
                </button>
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  onClick={useCurrentAsStart}
                  disabled={block.moves.length === 0}
                >
                  {labels.useCurrentAsStart}
                </button>
              </div>
              <div className="hw-board-card__side-to-move">
                {labels.sideToMove}: <strong>{sideToMoveLabel}</strong>
              </div>
            </div>

            <div className="hw-board-card__board">
              <div className="hw-board-card__board-inner">
                <Chessboard
                  options={{
                    id: `board-${block.clientId}`,
                    position: prefixFen,
                    boardOrientation: block.orientation,
                    allowDragging: startingPositionValid,
                    onPieceDrop: handlePieceDrop,
                    animationDurationInMs: 120,
                  }}
                />
              </div>
            </div>
          </div>

          <div className="hw-board-card__moves">
            <div className="hw-board-card__moves-head">
              <h5 className="hw-board-card__moves-heading">{labels.movesHeading}</h5>
              <span className="hw-board-card__count">
                {block.moves.length === 0
                  ? labels.movesEmpty
                  : labels.movesCount.replace("{count}", String(block.moves.length))}
              </span>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={undoLast}
                disabled={block.moves.length === 0}
              >
                {labels.undoLastMove}
              </button>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={clearAll}
                disabled={block.moves.length === 0}
              >
                {labels.clearAllMoves}
              </button>
            </div>
            {block.moves.length > 0 ? (
              <ol className="hw-board-card__move-list">
                {block.moves.map((m, i) => (
                  <li key={`${i}-${m}`}>
                    <code>{m}</code>
                  </li>
                ))}
              </ol>
            ) : null}
            <div className="hw-board-card__pgn">
              <label className="sched-field">
                <span className="sched-field__label">{labels.pastePgnLabel}</span>
                <textarea
                  rows={2}
                  value={pgnDraft}
                  placeholder={labels.pastePgnPlaceholder}
                  onChange={(e) => {
                    setPgnDraft(e.target.value);
                    if (pgnError) setPgnError(null);
                  }}
                />
              </label>
              <div className="hw-board-card__pgn-actions">
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  onClick={() => applyPgn("replace")}
                  disabled={pgnDraft.trim() === ""}
                >
                  {labels.pastePgnReplace}
                </button>
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  onClick={() => applyPgn("append")}
                  disabled={pgnDraft.trim() === ""}
                >
                  {labels.pastePgnAppend}
                </button>
                {pgnError ? (
                  <span className="sched-field-error" role="alert">
                    {pgnError}
                  </span>
                ) : null}
              </div>
            </div>
          </div>

          <div className="hw-board-card__solution">
            <div className="hw-board-card__solution-head">
              <h5 className="hw-board-card__moves-heading">{labels.solutionHeading}</h5>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={toggleSolution}
              >
                {block.solution ? labels.disableSolution : labels.enableSolution}
              </button>
            </div>
            <p className="hw-board-card__solution-hint">{labels.solutionHint}</p>

            {block.solution ? (
              <div className="hw-board-card__body">
                <div className="hw-board-card__sidebar hw-board-card__sidebar--solution">
                  <span className="hw-board-card__count">
                    {block.solution.length === 0
                      ? labels.solutionEmpty
                      : labels.solutionCount.replace(
                          "{count}",
                          String(block.solution.length),
                        )}
                  </span>
                  <button
                    type="button"
                    className="btn btn--ghost btn--sm"
                    onClick={undoSolutionLast}
                    disabled={block.solution.length === 0}
                  >
                    {labels.solutionUndo}
                  </button>
                  <button
                    type="button"
                    className="btn btn--ghost btn--sm"
                    onClick={clearSolution}
                    disabled={block.solution.length === 0}
                  >
                    {labels.solutionClear}
                  </button>
                  <div className="hw-board-card__side-to-move">
                    {labels.sideToMove}: <strong>{solutionSideLabel}</strong>
                  </div>
                  {block.solution.length > 0 ? (
                    <ol className="hw-board-card__move-list">
                      {block.solution.map((m, i) => (
                        <li key={`sol-${i}-${m}`}>
                          <code>{m}</code>
                        </li>
                      ))}
                    </ol>
                  ) : null}
                </div>
                <div className="hw-board-card__board">
                  <div className="hw-board-card__board-inner">
                    <Chessboard
                      options={{
                        id: `sol-${block.clientId}`,
                        position: solutionFen ?? prefixFen,
                        boardOrientation: block.orientation,
                        allowDragging: true,
                        onPieceDrop: handleSolutionDrop,
                        animationDurationInMs: 120,
                      }}
                    />
                  </div>
                </div>
              </div>
            ) : null}
          </div>
        </>
      )}
    </div>
  );
}

// ------------------------------------------------------------------
// SetupPanel — drag-and-drop position editor.
//
// The layout is:
//     [ top palette    ]       (pieces of the colour opposite orientation)
//     [     board      ]       (drag/drop target; right-click clears)
//     [ bottom palette ]       (pieces matching orientation)
//     [ side to move + castling + advanced FEN                 ]
//
// react-chessboard v5's `Chessboard` component detects when it's already
// nested inside a `ChessboardProvider` and skips its own provider layer
// (see `isWrapped` in the library). We rely on that to put `SparePiece`
// components inside the same DndContext as the board, so drag-from-
// palette-onto-board works out of the box.
// ------------------------------------------------------------------

function SetupPanel({
  block,
  labels,
  commitSetupFen,
  setupEmpty,
  setupFillStart,
  advancedOpen,
  setAdvancedOpen,
  fenDraft,
  setFenDraft,
  fenDirty,
  setFenDirty,
  fenDraftValid,
  commitFenDraft,
}: {
  block: BoardBlock;
  labels: BoardBlocksEditorLabels;
  commitSetupFen: (nextFen: string) => void;
  setupEmpty: () => void;
  setupFillStart: () => void;
  advancedOpen: boolean;
  setAdvancedOpen: (v: boolean) => void;
  fenDraft: string;
  setFenDraft: (v: string) => void;
  fenDirty: boolean;
  setFenDirty: (v: boolean) => void;
  fenDraftValid: boolean;
  commitFenDraft: () => void;
}) {
  const parsed = useMemo(
    () => parseFenForSetup(block.startingFen),
    [block.startingFen],
  );

  // The board expects a placement string; we pass the full FEN because
  // react-chessboard's `fenStringToPositionObject` only consumes the
  // placement part anyway. Using a recomposed FEN guarantees the board
  // sync even if the author typed garbage in the advanced box.
  const placementFen = useMemo(
    () => composeFen(parsed.pieces, parsed.side, parsed.castling),
    [parsed],
  );

  function handleDrop({
    piece,
    sourceSquare,
    targetSquare,
  }: {
    piece: { pieceType: string; isSparePiece: boolean };
    sourceSquare: string;
    targetSquare: string | null;
  }): boolean {
    const isSpare = piece.isSparePiece;
    const pieceType = piece.pieceType as PieceCode;
    const next: PieceMap = { ...parsed.pieces };

    if (!targetSquare) {
      // Dropped off board.
      if (isSpare) {
        // Nothing to do — spare pieces live on the palette.
        return false;
      }
      delete next[sourceSquare];
    } else {
      if (!isSpare) {
        delete next[sourceSquare];
      }
      next[targetSquare] = pieceType;
    }
    commitSetupFen(composeFen(next, parsed.side, parsed.castling));
    return true;
  }

  function handleSquareRightClick({ square }: { square: string }): void {
    if (!parsed.pieces[square]) return;
    const next: PieceMap = { ...parsed.pieces };
    delete next[square];
    commitSetupFen(composeFen(next, parsed.side, parsed.castling));
  }

  function setSide(nextSide: "w" | "b"): void {
    if (nextSide === parsed.side) return;
    commitSetupFen(composeFen(parsed.pieces, nextSide, parsed.castling));
  }

  function toggleCastle(kind: keyof CastleFlags): void {
    const nextCastling: CastleFlags = {
      ...parsed.castling,
      [kind]: !parsed.castling[kind],
    };
    commitSetupFen(composeFen(parsed.pieces, parsed.side, nextCastling));
  }

  // Availability checks for each castling flag — greyed out in the UI
  // when the king or rook can't possibly be castle-eligible.
  const castleAvailable = {
    K: parsed.pieces["e1"] === "wK" && parsed.pieces["h1"] === "wR",
    Q: parsed.pieces["e1"] === "wK" && parsed.pieces["a1"] === "wR",
    k: parsed.pieces["e8"] === "bK" && parsed.pieces["h8"] === "bR",
    q: parsed.pieces["e8"] === "bK" && parsed.pieces["a8"] === "bR",
  } as const;

  const topPieces = topPalette(block.orientation);
  const bottomPieces = bottomPalette(block.orientation);

  return (
    <div className="hw-board-card__setup">
      <div className="hw-board-card__setup-actions">
        <button
          type="button"
          className="btn btn--ghost btn--sm"
          onClick={setupFillStart}
        >
          {labels.setupFillStart}
        </button>
        <button
          type="button"
          className="btn btn--ghost btn--sm"
          onClick={setupEmpty}
        >
          {labels.setupEmpty}
        </button>
        <p className="hw-board-card__setup-hint">{labels.setupPaletteHint}</p>
      </div>

      <ChessboardProvider
        options={{
          id: `setup-${block.clientId}`,
          position: placementFen,
          boardOrientation: block.orientation,
          allowDragging: true,
          allowDragOffBoard: true,
          allowDrawingArrows: false,
          showAnimations: false,
          animationDurationInMs: 0,
          onPieceDrop: handleDrop,
          onSquareRightClick: handleSquareRightClick,
        }}
      >
        <div
          className="hw-board-card__palette hw-board-card__palette--top"
          aria-label={labels.setupPaletteHint}
        >
          {topPieces.map((p) => (
            <div key={p} className="hw-board-card__palette-slot">
              <SparePiece pieceType={p} />
            </div>
          ))}
        </div>
        <div className="hw-board-card__setup-board">
          <Chessboard />
        </div>
        <div
          className="hw-board-card__palette hw-board-card__palette--bottom"
          aria-label={labels.setupPaletteHint}
        >
          {bottomPieces.map((p) => (
            <div key={p} className="hw-board-card__palette-slot">
              <SparePiece pieceType={p} />
            </div>
          ))}
        </div>
      </ChessboardProvider>

      <p className="hw-board-card__setup-remove-hint">
        {labels.setupRemoveHint}
      </p>

      <div className="hw-board-card__setup-meta">
        <fieldset className="hw-board-card__side">
          <legend className="sched-field__label">{labels.setupSideToMove}</legend>
          <label>
            <input
              type="radio"
              name={`side-${block.clientId}`}
              checked={parsed.side === "w"}
              onChange={() => setSide("w")}
            />
            {labels.sideWhite}
          </label>
          <label>
            <input
              type="radio"
              name={`side-${block.clientId}`}
              checked={parsed.side === "b"}
              onChange={() => setSide("b")}
            />
            {labels.sideBlack}
          </label>
        </fieldset>

        <fieldset className="hw-board-card__castling">
          <legend className="sched-field__label">{labels.setupCastling}</legend>
          <label className={castleAvailable.K ? "" : "is-disabled"}>
            <input
              type="checkbox"
              checked={parsed.castling.K && castleAvailable.K}
              onChange={() => toggleCastle("K")}
              disabled={!castleAvailable.K}
            />
            {labels.setupCastleK}
          </label>
          <label className={castleAvailable.Q ? "" : "is-disabled"}>
            <input
              type="checkbox"
              checked={parsed.castling.Q && castleAvailable.Q}
              onChange={() => toggleCastle("Q")}
              disabled={!castleAvailable.Q}
            />
            {labels.setupCastleQ}
          </label>
          <label className={castleAvailable.k ? "" : "is-disabled"}>
            <input
              type="checkbox"
              checked={parsed.castling.k && castleAvailable.k}
              onChange={() => toggleCastle("k")}
              disabled={!castleAvailable.k}
            />
            {labels.setupCastlek}
          </label>
          <label className={castleAvailable.q ? "" : "is-disabled"}>
            <input
              type="checkbox"
              checked={parsed.castling.q && castleAvailable.q}
              onChange={() => toggleCastle("q")}
              disabled={!castleAvailable.q}
            />
            {labels.setupCastleq}
          </label>
        </fieldset>
      </div>

      <details
        className="hw-board-card__advanced"
        open={advancedOpen}
        onToggle={(e) => setAdvancedOpen((e.target as HTMLDetailsElement).open)}
      >
        <summary>
          {advancedOpen ? labels.setupAdvancedHide : labels.setupAdvancedShow}
        </summary>
        <label className="sched-field">
          <span className="sched-field__label">{labels.startingFenLabel}</span>
          <input
            type="text"
            className="hw-board-card__fen"
            value={fenDraft}
            spellCheck={false}
            onChange={(e) => {
              setFenDraft(e.target.value);
              setFenDirty(true);
            }}
            onBlur={commitFenDraft}
          />
          {fenDirty && !fenDraftValid ? (
            <span className="sched-field-error" role="alert">
              {labels.invalidFen}
            </span>
          ) : null}
        </label>
      </details>
    </div>
  );
}
