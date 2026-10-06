"use client";

import { useMemo, useState } from "react";
import { Chess } from "chess.js";
import { renderProseMarkdown } from "@/components/prose-render";
import {
  LessonWalkthroughView,
  type WalkthroughBlock,
  type WalkthroughLabels,
} from "@/components/lesson-walkthrough-view";
import {
  Chessboard,
  ChessboardProvider,
  SparePiece,
} from "react-chessboard";

/**
 * Author-side content editor for Lesson blocks. Covers all four
 * backend-supported kinds:
 *
 * <ul>
 *   <li><b>PROSE</b> — a single markdown textarea, ≤50,000 chars. Sanitisation
 *       of dangerous HTML runs client-side at render time (rehype-sanitize);
 *       the server only enforces size.</li>
 *   <li><b>BOARD_WALKTHROUGH</b> — mirrors the homework BOARD block's setup /
 *       move-recording UX, dropping the hidden solution section and adding an
 *       annotations list of {@code (ply, text)} pairs. Annotation {@code ply}
 *       is bounded by {@code [0, moves.length]} inclusive on the backend; this
 *       editor clamps the input into that range before emitting.</li>
 *   <li><b>MCQ</b> — multiple-choice question with 2-4 options, a correct-
 *       answer radio picking one of them, and an optional explanation that
 *       the viewer (Task #58) reveals after the student picks an answer.
 *       Backend caps: question ≤500 chars, each option ≤200 chars,
 *       explanation ≤2,000 chars. Options are deduplicated case-insensitively
 *       by the server — this editor surfaces the same warning inline so the
 *       author catches the mistake before submit.</li>
 *   <li><b>VIDEO</b> — external embed. The URL must be {@code https} and its
 *       host must appear in a tight allowlist (YouTube / Vimeo variants). The
 *       backend derives {@code provider} from the host, so this editor sends
 *       only {@code url} — no "provider" field; whatever the author typed is
 *       irrelevant, the server strips it.</li>
 * </ul>
 *
 * <p>Many of the FEN / UCI / PGN helpers below duplicate
 * {@code ./board-blocks-editor.tsx}. Kept duplicated for now because
 * BOARD_WALKTHROUGH diverges enough from homework BOARD (annotations in,
 * hidden solution out) that a shared component would be awkward — a future
 * refactor should extract the chess helpers into a shared module once both
 * editors stabilise. The copy here is intentionally byte-identical so a
 * future diff picks it up easily.
 */

export const STARTING_FEN =
  "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

export type LessonAnnotation = {
  /**
   * 0 = the starting position (before move 0); {@code moves.length} = the
   * position after the last move. The backend validator rejects anything
   * outside this inclusive range.
   */
  ply: number;
  text: string;
};

export type LessonProseBlock = {
  clientId: string;
  kind: "PROSE";
  /** Authored markdown, max 50,000 chars (backend cap). */
  markdown: string;
};

export type LessonWalkthroughBlock = {
  clientId: string;
  kind: "BOARD_WALKTHROUGH";
  title: string;
  startingFen: string;
  /** UCI moves played on top of startingFen. Every entry is legal. */
  moves: string[];
  orientation: "white" | "black";
  /** Ordered list of ply-anchored explanatory notes. Max 64 rows. */
  annotations: LessonAnnotation[];
};

export type LessonMcqBlock = {
  clientId: string;
  kind: "MCQ";
  /** Prompt text. Backend cap: 500 chars. */
  question: string;
  /**
   * 2-4 non-blank option strings. The server dedups them case-
   * insensitively; this editor surfaces the same check inline.
   */
  options: string[];
  /** Index into {@link options}; always bounded to {@code [0, options.length - 1]}. */
  correctIndex: number;
  /** Optional post-answer explanation. Backend cap: 2,000 chars. */
  explanation: string;
};

export type LessonVideoBlock = {
  clientId: string;
  kind: "VIDEO";
  /**
   * External URL. Must be {@code https} and from an allowlisted host
   * (YouTube / Vimeo). The editor previews the derived provider + inline
   * errors at blur time; the server still revalidates on write.
   */
  url: string;
};

export type LessonBlock =
  | LessonProseBlock
  | LessonWalkthroughBlock
  | LessonMcqBlock
  | LessonVideoBlock;

export type LessonBlocksEditorLabels = {
  // List chrome
  heading: string;
  hint: string;
  noBlocks: string;
  addProse: string;
  addWalkthrough: string;
  addMcq: string;
  addVideo: string;
  removeBlock: string;
  /** ARIA label on the "move this block up one position" button. */
  moveUp: string;
  /** ARIA label on the "move this block down one position" button. */
  moveDown: string;
  blockKindProse: string;
  blockKindWalkthrough: string;
  blockKindMcq: string;
  blockKindVideo: string;
  // PROSE
  proseLabel: string;
  prosePlaceholder: string;
  proseCharCount: string; // with {count} / {max}
  /** Generic "Edit" tab label — shared by PROSE + VIDEO block cards. */
  tabEdit: string;
  /** Generic "Preview" tab label — shared by PROSE + VIDEO block cards. */
  tabPreview: string;
  /** Italic-muted note when the author switches to Preview with empty input. */
  prosePreviewEmpty: string;
  /** Italic-muted note when the VIDEO Preview tab can't embed the URL. */
  videoPreviewInvalid: string;
  /** Tag rendered next to the correct option in the MCQ Preview pane. */
  mcqPreviewCorrect: string;
  /** Italic-muted note when the MCQ Preview is shown with empty question. */
  mcqPreviewEmpty: string;
  /** Locale-agnostic label bag for the shared walkthrough viewer.
   *  Built in lesson-panels.tsx from the student-namespace
   *  translations so the editor preview reads identically to the
   *  student page. */
  walkthroughViewLabels: WalkthroughLabels;
  proseTooLong: string;
  // BOARD_WALKTHROUGH — mirrors homework.boards.* minus solution keys
  blockTitleLabel: string;
  blockTitlePlaceholder: string;
  startingFenLabel: string;
  resetToStart: string;
  useCurrentAsStart: string;
  orientationLabel: string;
  orientationWhite: string;
  orientationBlack: string;
  movesHeading: string;
  movesCount: string; // ICU {count}
  movesEmpty: string;
  pastePgnLabel: string;
  pastePgnPlaceholder: string;
  pastePgnReplace: string;
  pastePgnAppend: string;
  undoLastMove: string;
  clearAllMoves: string;
  sideToMove: string;
  sideWhite: string;
  sideBlack: string;
  invalidFen: string;
  invalidPgn: string;
  // Mode tabs (Setup / Moves)
  modeSetup: string;
  modeMoves: string;
  /** Third tab on the WALKTHROUGH card — switches to the rendered
   *  student-side viewer so the author can step through the line. */
  modePreview: string;
  // Setup-mode labels
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
  // Annotations
  annotationsHeading: string;
  annotationsHint: string;
  annotationsEmpty: string;
  annotationsCount: string; // ICU {count}
  annotationsAdd: string;
  annotationsAddAtStart: string;
  annotationsDelete: string;
  annotationsPlyLabel: string;
  annotationsPlyHint: string; // with {max}
  annotationsTextLabel: string;
  annotationsTextPlaceholder: string;
  annotationsMaxReached: string; // with {max}
  // MCQ
  mcqQuestionLabel: string;
  mcqQuestionPlaceholder: string;
  mcqQuestionCharCount: string; // with {count} / {max}
  mcqQuestionTooLong: string;
  mcqOptionsLabel: string;
  mcqOptionsHint: string; // with {min} / {max}
  mcqOptionLabel: string; // with {index}
  mcqOptionPlaceholder: string;
  mcqOptionCorrect: string; // radio label
  mcqOptionCorrectAria: string; // with {index} — aria-label on radio
  mcqOptionRemove: string;
  mcqOptionTooLong: string;
  mcqAddOption: string;
  mcqRemoveOption: string;
  mcqMinOptions: string; // with {min}
  mcqMaxOptions: string; // with {max}
  mcqDuplicateOption: string;
  mcqExplanationLabel: string;
  mcqExplanationPlaceholder: string;
  mcqExplanationCharCount: string; // with {count} / {max}
  mcqExplanationTooLong: string;
  // VIDEO
  videoUrlLabel: string;
  videoUrlPlaceholder: string;
  videoUrlHint: string;
  videoInvalidUrl: string;
  videoNotHttps: string;
  videoHostNotAllowed: string;
  videoProviderYoutube: string;
  videoProviderVimeo: string;
  videoProviderLabel: string; // with {provider}
  videoAllowedHosts: string;
};

// ------------------------------------------------------------------
// Helpers (duplicated from board-blocks-editor.tsx — see file header)
// ------------------------------------------------------------------

let clientIdCounter = 0;
function nextClientId(): string {
  clientIdCounter += 1;
  return `ln-${Date.now().toString(36)}-${clientIdCounter}`;
}

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

function fenAfter(startFen: string, moves: string[]): string | null {
  const c = play(startFen, moves);
  return c ? c.fen() : null;
}

function isValidFen(fen: string): boolean {
  try {
    new Chess(fen);
    return true;
  } catch {
    return false;
  }
}

type PieceCode =
  | "wK" | "wQ" | "wR" | "wB" | "wN" | "wP"
  | "bK" | "bQ" | "bR" | "bB" | "bN" | "bP";

type PieceMap = Record<string, PieceCode>;

type CastleFlags = { K: boolean; Q: boolean; k: boolean; q: boolean };

const EMPTY_FEN = "8/8/8/8/8/8/8/8 w - - 0 1";
const ALL_FILES = ["a", "b", "c", "d", "e", "f", "g", "h"] as const;

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

const PALETTE_ORDER: readonly PieceCode[] = [
  "wK", "wQ", "wR", "wB", "wN", "wP",
] as const;

function topPalette(orientation: "white" | "black"): readonly PieceCode[] {
  return orientation === "white"
    ? (["bK", "bQ", "bR", "bB", "bN", "bP"] as const)
    : PALETTE_ORDER;
}

function bottomPalette(orientation: "white" | "black"): readonly PieceCode[] {
  return orientation === "white"
    ? PALETTE_ORDER
    : (["bK", "bQ", "bR", "bB", "bN", "bP"] as const);
}

function pgnToUci(startFen: string, pgn: string): string[] | null {
  const trimmed = pgn.trim();
  if (!trimmed) return [];
  const parser = new Chess();
  try {
    parser.loadPgn(trimmed, { strict: false });
  } catch {
    return null;
  }
  const sanTokens = parser.history();
  if (sanTokens.length === 0) return [];
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

// ------------------------------------------------------------------
// Constants + constructors
// ------------------------------------------------------------------

/** Backend cap: {@code LessonResourceValidator#validateProsePayload}. */
export const PROSE_MAX_CHARS = 50_000;
/** Backend cap: {@code LessonResourceValidator#validateBoardWalkthrough}. */
export const WALKTHROUGH_MAX_ANNOTATIONS = 64;
/** Backend cap: annotation text length. */
export const WALKTHROUGH_MAX_ANNOTATION_TEXT = 200;
/** Backend cap: {@code LessonResourceValidator#MAX_QUESTION_CHARS}. */
export const MCQ_MAX_QUESTION_CHARS = 500;
/** Backend min/max: {@code LessonResourceValidator#MIN_OPTIONS/MAX_OPTIONS}. */
export const MCQ_MIN_OPTIONS = 2;
export const MCQ_MAX_OPTIONS = 4;
/** Backend cap: {@code LessonResourceValidator#MAX_OPTION_CHARS}. */
export const MCQ_MAX_OPTION_CHARS = 200;
/** Backend cap: {@code LessonResourceValidator#MAX_EXPLANATION_CHARS}. */
export const MCQ_MAX_EXPLANATION_CHARS = 2_000;
/**
 * Backend allowlist: {@code LessonResourceValidator#VIDEO_HOST_ALLOWLIST}.
 * Kept in lockstep with the server so the editor's blur-time notice matches
 * what will actually come back from the API.
 */
export const VIDEO_ALLOWED_HOSTS: readonly string[] = [
  "www.youtube.com",
  "youtube.com",
  "youtu.be",
  "www.vimeo.com",
  "vimeo.com",
  "player.vimeo.com",
];

export function createEmptyProseBlock(): LessonProseBlock {
  return {
    clientId: nextClientId(),
    kind: "PROSE",
    markdown: "",
  };
}

export function createEmptyWalkthroughBlock(): LessonWalkthroughBlock {
  return {
    clientId: nextClientId(),
    kind: "BOARD_WALKTHROUGH",
    title: "",
    startingFen: STARTING_FEN,
    moves: [],
    orientation: "white",
    annotations: [],
  };
}

export function createEmptyMcqBlock(): LessonMcqBlock {
  return {
    clientId: nextClientId(),
    kind: "MCQ",
    question: "",
    // Start at the backend's minimum — two empty option slots. The author
    // can add up to two more via the Add option button.
    options: ["", ""],
    correctIndex: 0,
    explanation: "",
  };
}

export function createEmptyVideoBlock(): LessonVideoBlock {
  return {
    clientId: nextClientId(),
    kind: "VIDEO",
    url: "",
  };
}

/**
 * Parse a URL and return the derived provider (or null if the URL isn't
 * parseable, isn't https, or isn't hosted on an allowlisted domain). This
 * mirrors the backend derivation exactly so the inline preview in the
 * VIDEO editor reflects what will ultimately get persisted.
 */
export function deriveVideoProvider(
  url: string,
): "youtube" | "vimeo" | null {
  if (!url.trim()) return null;
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return null;
  }
  if (parsed.protocol !== "https:") return null;
  const host = parsed.host.toLowerCase();
  if (
    host === "youtu.be" ||
    host === "youtube.com" ||
    host === "www.youtube.com"
  ) {
    return "youtube";
  }
  if (
    host === "vimeo.com" ||
    host === "www.vimeo.com" ||
    host === "player.vimeo.com"
  ) {
    return "vimeo";
  }
  return null;
}

/** Inspect a URL and return which allowlist check failed, if any. */
export function diagnoseVideoUrl(
  url: string,
):
  | { kind: "ok"; provider: "youtube" | "vimeo" }
  | { kind: "empty" }
  | { kind: "invalid" }
  | { kind: "not-https" }
  | { kind: "host-not-allowed"; host: string } {
  const trimmed = url.trim();
  if (!trimmed) return { kind: "empty" };
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return { kind: "invalid" };
  }
  if (parsed.protocol !== "https:") {
    return { kind: "not-https" };
  }
  const host = parsed.host.toLowerCase();
  const provider = deriveVideoProvider(trimmed);
  if (!provider) {
    return { kind: "host-not-allowed", host };
  }
  return { kind: "ok", provider };
}

/**
 * Case-insensitive dedup check on the MCQ options list, mirroring
 * {@code LessonResourceValidator#validateMcqPayload}. Returns the index of
 * the first duplicated option (the later of two matching entries) or
 * {@code -1} if no duplicates are present. Blank options are ignored so
 * they don't all read as duplicates of each other while the author is
 * still typing.
 */
export function firstDuplicateOptionIndex(options: readonly string[]): number {
  const seen = new Map<string, number>();
  for (let i = 0; i < options.length; i += 1) {
    const opt = (options[i] ?? "").trim();
    if (!opt) continue;
    const key = opt.toLowerCase();
    const prior = seen.get(key);
    if (prior !== undefined) return i;
    seen.set(key, i);
  }
  return -1;
}

/**
 * Hydrate blocks from a backend {@code LessonDetail.resources} list so
 * the edit form can pre-fill. Unknown kinds / malformed payloads are
 * skipped rather than crashing the editor.
 */
export function lessonBlocksFromResources(
  resources: Array<{
    kind: string;
    title: string | null;
    payload: Record<string, unknown>;
  }>,
): LessonBlock[] {
  const out: LessonBlock[] = [];
  for (const r of resources) {
    const kind = r.kind.toUpperCase();
    const p = r.payload ?? {};
    if (kind === "PROSE") {
      const md = typeof p.markdown === "string" ? (p.markdown as string) : "";
      out.push({
        clientId: nextClientId(),
        kind: "PROSE",
        markdown: md,
      });
    } else if (kind === "BOARD_WALKTHROUGH") {
      const fen =
        typeof p.startingFen === "string"
          ? (p.startingFen as string)
          : STARTING_FEN;
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
      const anns: LessonAnnotation[] = [];
      if (Array.isArray(p.annotations)) {
        for (const a of p.annotations as unknown[]) {
          if (!a || typeof a !== "object") continue;
          const obj = a as Record<string, unknown>;
          const plyN =
            typeof obj.ply === "number" && Number.isFinite(obj.ply)
              ? Math.floor(obj.ply)
              : null;
          const text = typeof obj.text === "string" ? (obj.text as string) : "";
          if (plyN === null || plyN < 0) continue;
          anns.push({ ply: plyN, text });
        }
      }
      out.push({
        clientId: nextClientId(),
        kind: "BOARD_WALKTHROUGH",
        title: r.title ?? "",
        startingFen: fen,
        moves: mv,
        orientation: orient,
        annotations: anns,
      });
    } else if (kind === "MCQ") {
      const question =
        typeof p.question === "string" ? (p.question as string) : "";
      const options = Array.isArray(p.options)
        ? (p.options as unknown[])
            .filter((x): x is string => typeof x === "string")
        : [];
      // Pad out to MIN_OPTIONS so the editor always has at least two rows
      // to render even if the backend row was authored via raw POST.
      while (options.length < MCQ_MIN_OPTIONS) options.push("");
      // Truncate beyond MAX_OPTIONS so a crafted payload with 5+ options
      // doesn't blow past the UI's render slots.
      const clippedOptions = options.slice(0, MCQ_MAX_OPTIONS);
      const rawIdx =
        typeof p.correctIndex === "number" && Number.isFinite(p.correctIndex)
          ? Math.floor(p.correctIndex)
          : 0;
      const correctIndex = Math.min(
        Math.max(rawIdx, 0),
        clippedOptions.length - 1,
      );
      const explanation =
        typeof p.explanation === "string" ? (p.explanation as string) : "";
      out.push({
        clientId: nextClientId(),
        kind: "MCQ",
        question,
        options: clippedOptions,
        correctIndex,
        explanation,
      });
    } else if (kind === "VIDEO") {
      const url = typeof p.url === "string" ? (p.url as string) : "";
      out.push({
        clientId: nextClientId(),
        kind: "VIDEO",
        url,
      });
    }
    // Unknown kinds are silently dropped so the editor keeps working even
    // if the backend grows a new kind before this module catches up.
  }
  return out;
}

/**
 * Pack the editor state into the shape the server action + backend expect.
 * Empty PROSE markdown is preserved as an empty string (the backend will
 * still reject it in validation, but we don't silently drop blocks the
 * author hasn't finished authoring — they'll see the validation message
 * instead of a confusing disappeared block).
 */
export function lessonResourcesFromBlocks(
  blocks: LessonBlock[],
): Array<{
  kind: string;
  title: string | null;
  payload: Record<string, unknown>;
}> {
  return blocks.map((b) => {
    if (b.kind === "PROSE") {
      return {
        kind: "PROSE",
        title: null,
        payload: { markdown: b.markdown },
      };
    }
    if (b.kind === "MCQ") {
      const payload: Record<string, unknown> = {
        question: b.question,
        options: b.options,
        correctIndex: b.correctIndex,
      };
      // Only emit `explanation` when the author has typed something. The
      // backend treats a missing field and an all-whitespace string
      // identically (both → null), but the round-trip is cleaner if we
      // don't ship empty strings the server will just drop.
      const expl = b.explanation.trim();
      if (expl) payload.explanation = expl;
      return {
        kind: "MCQ",
        title: null,
        payload,
      };
    }
    if (b.kind === "VIDEO") {
      // Server derives `provider` from the host, so we deliberately do
      // NOT send whatever the editor may have previewed — it'd be thrown
      // away anyway and shipping it muddies the wire contract.
      return {
        kind: "VIDEO",
        title: null,
        payload: { url: b.url.trim() },
      };
    }
    // BOARD_WALKTHROUGH
    const payload: Record<string, unknown> = {
      startingFen: b.startingFen,
      moves: b.moves,
      orientation: b.orientation,
    };
    if (b.annotations.length > 0) {
      payload.annotations = b.annotations.map((a) => ({
        ply: a.ply,
        text: a.text,
      }));
    }
    return {
      kind: "BOARD_WALKTHROUGH",
      title: b.title.trim() ? b.title.trim() : null,
      payload,
    };
  });
}

// ------------------------------------------------------------------
// Editor
// ------------------------------------------------------------------

export function LessonBlocksEditor({
  blocks,
  onChange,
  labels,
}: {
  blocks: LessonBlock[];
  onChange: (next: LessonBlock[]) => void;
  labels: LessonBlocksEditorLabels;
}) {
  function update(idx: number, patch: Partial<LessonBlock>): void {
    const next = blocks.map((b, i) => {
      if (i !== idx) return b;
      // Narrow by kind so TS knows `patch` is assignable to each variant.
      switch (b.kind) {
        case "PROSE":
          return { ...b, ...(patch as Partial<LessonProseBlock>) };
        case "BOARD_WALKTHROUGH":
          return { ...b, ...(patch as Partial<LessonWalkthroughBlock>) };
        case "MCQ":
          return { ...b, ...(patch as Partial<LessonMcqBlock>) };
        case "VIDEO":
          return { ...b, ...(patch as Partial<LessonVideoBlock>) };
      }
    });
    onChange(next);
  }
  function remove(idx: number): void {
    onChange(blocks.filter((_, i) => i !== idx));
  }
  /**
   * Reorder a block. Same shape as the homework BoardBlocksEditor —
   * pure local state shuffle; the backend's `position` is re-derived
   * from the list index on save via the existing replace-on-write
   * `resources` PATCH.
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
  function addProse(): void {
    onChange([...blocks, createEmptyProseBlock()]);
  }
  function addWalkthrough(): void {
    onChange([...blocks, createEmptyWalkthroughBlock()]);
  }
  function addMcq(): void {
    onChange([...blocks, createEmptyMcqBlock()]);
  }
  function addVideo(): void {
    onChange([...blocks, createEmptyVideoBlock()]);
  }

  return (
    <section className="hw-boards lesson-blocks">
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
              {b.kind === "PROSE" ? (
                <ProseBlockCard
                  index={i}
                  block={b}
                  labels={labels}
                  onChange={(patch) => update(i, patch)}
                  onRemove={() => remove(i)}
                />
              ) : b.kind === "BOARD_WALKTHROUGH" ? (
                <WalkthroughBlockCard
                  index={i}
                  block={b}
                  labels={labels}
                  onChange={(patch) => update(i, patch)}
                  onRemove={() => remove(i)}
                />
              ) : b.kind === "MCQ" ? (
                <McqBlockCard
                  index={i}
                  block={b}
                  labels={labels}
                  onChange={(patch) => update(i, patch)}
                  onRemove={() => remove(i)}
                />
              ) : (
                <VideoBlockCard
                  index={i}
                  block={b}
                  labels={labels}
                  onChange={(patch) => update(i, patch)}
                  onRemove={() => remove(i)}
                />
              )}
            </li>
          ))}
        </ol>
      )}
      <div className="hw-boards__footer lesson-blocks__footer">
        <button
          type="button"
          className="btn btn--ghost btn--sm"
          onClick={addProse}
        >
          {labels.addProse}
        </button>
        <button
          type="button"
          className="btn btn--ghost btn--sm"
          onClick={addWalkthrough}
        >
          {labels.addWalkthrough}
        </button>
        <button
          type="button"
          className="btn btn--ghost btn--sm"
          onClick={addMcq}
        >
          {labels.addMcq}
        </button>
        <button
          type="button"
          className="btn btn--ghost btn--sm"
          onClick={addVideo}
        >
          {labels.addVideo}
        </button>
      </div>
    </section>
  );
}

// ------------------------------------------------------------------
// PROSE block card
// ------------------------------------------------------------------

function ProseBlockCard({
  index,
  block,
  labels,
  onChange,
  onRemove,
}: {
  index: number;
  block: LessonProseBlock;
  labels: LessonBlocksEditorLabels;
  onChange: (patch: Partial<LessonProseBlock>) => void;
  onRemove: () => void;
}) {
  const len = block.markdown.length;
  const tooLong = len > PROSE_MAX_CHARS;
  const countLabel = labels.proseCharCount
    .replace("{count}", String(len))
    .replace("{max}", String(PROSE_MAX_CHARS));

  // Edit / Preview tab. Defaults to Edit so a freshly-added block
  // doesn't open empty in Preview mode (the author still has to type).
  const [mode, setMode] = useState<"edit" | "preview">("edit");
  const hasContent = block.markdown.trim().length > 0;

  return (
    <div className="hw-board-card lesson-prose">
      <div className="hw-board-card__head">
        <strong className="hw-board-card__num">#{index + 1}</strong>
        <span className="lesson-blocks__kind-tag">{labels.blockKindProse}</span>
        <div className="lesson-prose__tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={mode === "edit"}
            className={
              "lesson-prose__tab" +
              (mode === "edit" ? " lesson-prose__tab--on" : "")
            }
            onClick={() => setMode("edit")}
          >
            {labels.tabEdit}
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === "preview"}
            className={
              "lesson-prose__tab" +
              (mode === "preview" ? " lesson-prose__tab--on" : "")
            }
            onClick={() => setMode("preview")}
          >
            {labels.tabPreview}
          </button>
        </div>
        <button
          type="button"
          className="btn btn--ghost btn--sm hw-board-card__remove"
          onClick={onRemove}
        >
          {labels.removeBlock}
        </button>
      </div>
      {mode === "edit" ? (
        <label className="sched-field lesson-prose__field">
          <span className="sched-field__label">{labels.proseLabel}</span>
          <textarea
            className="lesson-prose__textarea"
            value={block.markdown}
            rows={8}
            placeholder={labels.prosePlaceholder}
            onChange={(e) => onChange({ markdown: e.target.value })}
          />
        </label>
      ) : (
        <div
          className="lesson-prose__preview lesson-prose"
          aria-live="polite"
        >
          {hasContent ? (
            renderProseMarkdown(block.markdown, `prose-edit-${index}`)
          ) : (
            <p className="lesson-prose__empty">{labels.prosePreviewEmpty}</p>
          )}
        </div>
      )}
      <div className="lesson-prose__meta">
        <span
          className={
            "lesson-prose__charcount" +
            (tooLong ? " lesson-prose__charcount--over" : "")
          }
        >
          {countLabel}
        </span>
        {tooLong ? (
          <span className="sched-field-error" role="alert">
            {labels.proseTooLong}
          </span>
        ) : null}
      </div>
    </div>
  );
}

// ------------------------------------------------------------------
// BOARD_WALKTHROUGH block card (shape mirrors homework BoardBlockCard)
// ------------------------------------------------------------------

function WalkthroughBlockCard({
  index,
  block,
  labels,
  onChange,
  onRemove,
}: {
  index: number;
  block: LessonWalkthroughBlock;
  labels: LessonBlocksEditorLabels;
  onChange: (patch: Partial<LessonWalkthroughBlock>) => void;
  onRemove: () => void;
}) {
  const [mode, setMode] = useState<"setup" | "moves" | "preview">(
    block.moves.length === 0 ? "setup" : "moves",
  );
  const [advancedFenOpen, setAdvancedFenOpen] = useState(false);

  const [fenDraft, setFenDraft] = useState<string>(block.startingFen);
  const [fenDirty, setFenDirty] = useState<boolean>(false);
  const fenDraftValid = useMemo(() => isValidFen(fenDraft), [fenDraft]);

  const [pgnDraft, setPgnDraft] = useState<string>("");
  const [pgnError, setPgnError] = useState<string | null>(null);

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

  function commitFenDraft(): void {
    if (!fenDirty) return;
    if (isValidFen(fenDraft)) {
      onChange({ startingFen: fenDraft, moves: [], annotations: [] });
      setFenDirty(false);
    }
  }

  function resetToStart(): void {
    setFenDraft(STARTING_FEN);
    setFenDirty(false);
    onChange({ startingFen: STARTING_FEN, moves: [], annotations: [] });
  }

  function useCurrentAsStart(): void {
    const nextFen = prefixFen;
    setFenDraft(nextFen);
    setFenDirty(false);
    onChange({ startingFen: nextFen, moves: [], annotations: [] });
  }

  function commitSetupFen(nextFen: string): void {
    setFenDraft(nextFen);
    setFenDirty(false);
    onChange({ startingFen: nextFen, moves: [], annotations: [] });
  }

  function setupEmpty(): void {
    commitSetupFen(EMPTY_FEN);
  }

  function setupFillStart(): void {
    commitSetupFen(STARTING_FEN);
  }

  function switchMode(next: "setup" | "moves" | "preview"): void {
    if (next === mode) return;
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
    // Any annotations anchored at the now-gone ply get clamped to the new
    // upper bound (moves.length - 1). This keeps us within the backend's
    // inclusive range without silently deleting the author's text.
    const nextLen = block.moves.length - 1;
    const clamped = block.annotations.map((a) =>
      a.ply > nextLen ? { ...a, ply: nextLen } : a,
    );
    onChange({ moves: block.moves.slice(0, -1), annotations: clamped });
  }

  function clearAll(): void {
    // Wiping moves also wipes annotations (their ply anchors would all be
    // out of range). Matches the "editing the starting position clears
    // moves" semantics — one coherent reset.
    onChange({ moves: [], annotations: [] });
  }

  function applyPgn(mode: "replace" | "append"): void {
    const start = mode === "replace" ? block.startingFen : prefixFen;
    const uci = pgnToUci(start, pgnDraft);
    if (uci === null) {
      setPgnError(labels.invalidPgn);
      return;
    }
    setPgnError(null);
    if (mode === "replace") {
      onChange({ moves: uci, annotations: [] });
    } else {
      onChange({ moves: [...block.moves, ...uci] });
    }
    setPgnDraft("");
  }

  // --- Annotations ---

  const maxPly = block.moves.length;

  function addAnnotationAtEnd(): void {
    if (block.annotations.length >= WALKTHROUGH_MAX_ANNOTATIONS) return;
    onChange({
      annotations: [...block.annotations, { ply: maxPly, text: "" }],
    });
  }

  function addAnnotationAtStart(): void {
    if (block.annotations.length >= WALKTHROUGH_MAX_ANNOTATIONS) return;
    onChange({
      annotations: [{ ply: 0, text: "" }, ...block.annotations],
    });
  }

  function updateAnnotation(idx: number, patch: Partial<LessonAnnotation>): void {
    const next = block.annotations.map((a, i) => (i === idx ? { ...a, ...patch } : a));
    onChange({ annotations: next });
  }

  function removeAnnotation(idx: number): void {
    onChange({ annotations: block.annotations.filter((_, i) => i !== idx) });
  }

  function clampPly(raw: string): number {
    const n = Number(raw);
    if (!Number.isFinite(n)) return 0;
    const floor = Math.floor(n);
    if (floor < 0) return 0;
    if (floor > maxPly) return maxPly;
    return floor;
  }

  // --- render ---

  const startingPositionValid = isValidFen(block.startingFen);
  const atAnnotationCap = block.annotations.length >= WALKTHROUGH_MAX_ANNOTATIONS;

  return (
    <div className="hw-board-card lesson-walkthrough">
      <div className="hw-board-card__head">
        <strong className="hw-board-card__num">#{index + 1}</strong>
        <span className="lesson-blocks__kind-tag">
          {labels.blockKindWalkthrough}
        </span>
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
          <legend className="sched-field__label">
            {labels.orientationLabel}
          </legend>
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
          {labels.removeBlock}
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
        <button
          type="button"
          role="tab"
          aria-selected={mode === "preview"}
          className={
            "hw-board-card__tab" +
            (mode === "preview" ? " hw-board-card__tab--active" : "")
          }
          onClick={() => switchMode("preview")}
        >
          {labels.modePreview}
        </button>
      </div>

      {mode === "preview" ? (
        <div className="lesson-walkthrough-preview">
          <LessonWalkthroughView
            block={
              {
                id: block.clientId,
                title: block.title.trim().length > 0 ? block.title.trim() : null,
                startingFen: block.startingFen,
                moves: block.moves,
                orientation: block.orientation,
                annotations: block.annotations.map((a) => ({
                  ply: a.ply,
                  text: a.text,
                })),
              } satisfies WalkthroughBlock
            }
            labels={labels.walkthroughViewLabels}
            idPrefix="editor-wt"
          />
        </div>
      ) : mode === "setup" ? (
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
                    id: `wt-${block.clientId}`,
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
              <h5 className="hw-board-card__moves-heading">
                {labels.movesHeading}
              </h5>
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
                <span className="sched-field__label">
                  {labels.pastePgnLabel}
                </span>
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

          <div className="lesson-walkthrough__annotations">
            <div className="lesson-walkthrough__annotations-head">
              <h5 className="hw-board-card__moves-heading">
                {labels.annotationsHeading}
              </h5>
              <span className="hw-board-card__count">
                {block.annotations.length === 0
                  ? labels.annotationsEmpty
                  : labels.annotationsCount.replace(
                      "{count}",
                      String(block.annotations.length),
                    )}
              </span>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={addAnnotationAtStart}
                disabled={atAnnotationCap}
              >
                {labels.annotationsAddAtStart}
              </button>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={addAnnotationAtEnd}
                disabled={atAnnotationCap}
              >
                {labels.annotationsAdd}
              </button>
            </div>
            <p className="hw-board-card__solution-hint">
              {labels.annotationsHint}
            </p>
            {atAnnotationCap ? (
              <p className="sched-field-error" role="alert">
                {labels.annotationsMaxReached.replace(
                  "{max}",
                  String(WALKTHROUGH_MAX_ANNOTATIONS),
                )}
              </p>
            ) : null}
            {block.annotations.length > 0 ? (
              <ul className="lesson-walkthrough__annotations-list">
                {block.annotations.map((a, i) => (
                  <li
                    key={`ann-${i}`}
                    className="lesson-walkthrough__annotation-row"
                  >
                    <label className="sched-field lesson-walkthrough__ply">
                      <span className="sched-field__label">
                        {labels.annotationsPlyLabel}
                      </span>
                      <input
                        type="number"
                        min={0}
                        max={maxPly}
                        step={1}
                        value={a.ply}
                        onChange={(e) =>
                          updateAnnotation(i, {
                            ply: clampPly(e.target.value),
                          })
                        }
                      />
                      <span className="hw-field-hint">
                        {labels.annotationsPlyHint.replace(
                          "{max}",
                          String(maxPly),
                        )}
                      </span>
                    </label>
                    <label className="sched-field lesson-walkthrough__text">
                      <span className="sched-field__label">
                        {labels.annotationsTextLabel}
                      </span>
                      <textarea
                        rows={2}
                        value={a.text}
                        maxLength={WALKTHROUGH_MAX_ANNOTATION_TEXT}
                        placeholder={labels.annotationsTextPlaceholder}
                        onChange={(e) =>
                          updateAnnotation(i, { text: e.target.value })
                        }
                      />
                    </label>
                    <button
                      type="button"
                      className="btn btn--ghost btn--sm lesson-walkthrough__annotation-remove"
                      onClick={() => removeAnnotation(i)}
                    >
                      {labels.annotationsDelete}
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        </>
      )}
    </div>
  );
}

// ------------------------------------------------------------------
// SetupPanel — drag-and-drop position editor (mirrors homework editor)
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
  block: LessonWalkthroughBlock;
  labels: LessonBlocksEditorLabels;
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
      if (isSpare) return false;
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
          <legend className="sched-field__label">
            {labels.setupSideToMove}
          </legend>
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
        onToggle={(e) =>
          setAdvancedOpen((e.target as HTMLDetailsElement).open)
        }
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

// ------------------------------------------------------------------
// MCQ block card
// ------------------------------------------------------------------

function McqBlockCard({
  index,
  block,
  labels,
  onChange,
  onRemove,
}: {
  index: number;
  block: LessonMcqBlock;
  labels: LessonBlocksEditorLabels;
  onChange: (patch: Partial<LessonMcqBlock>) => void;
  onRemove: () => void;
}) {
  const questionLen = block.question.length;
  const questionTooLong = questionLen > MCQ_MAX_QUESTION_CHARS;
  const questionCountLabel = labels.mcqQuestionCharCount
    .replace("{count}", String(questionLen))
    .replace("{max}", String(MCQ_MAX_QUESTION_CHARS));

  const dupIdx = useMemo(
    () => firstDuplicateOptionIndex(block.options),
    [block.options],
  );

  const canRemoveOption = block.options.length > MCQ_MIN_OPTIONS;
  const canAddOption = block.options.length < MCQ_MAX_OPTIONS;

  const explanationLen = block.explanation.length;
  const explanationTooLong = explanationLen > MCQ_MAX_EXPLANATION_CHARS;
  const explanationCountLabel = labels.mcqExplanationCharCount
    .replace("{count}", String(explanationLen))
    .replace("{max}", String(MCQ_MAX_EXPLANATION_CHARS));

  const optionsHint = labels.mcqOptionsHint
    .replace("{min}", String(MCQ_MIN_OPTIONS))
    .replace("{max}", String(MCQ_MAX_OPTIONS));

  // Edit / Preview tab. Defaults to Edit so a freshly-added block
  // doesn't open empty in Preview.
  const [mode, setMode] = useState<"edit" | "preview">("edit");
  const hasQuestion = block.question.trim().length > 0;

  function setOption(i: number, value: string): void {
    const nextOptions = block.options.map((o, j) => (j === i ? value : o));
    onChange({ options: nextOptions });
  }

  function addOption(): void {
    if (!canAddOption) return;
    onChange({ options: [...block.options, ""] });
  }

  function removeOption(i: number): void {
    if (!canRemoveOption) return;
    const next = block.options.filter((_, j) => j !== i);
    // If the removed option was the correct one — or sat before it — the
    // index needs to shift or fall back to 0 so we don't leave
    // {@code correctIndex} pointing past the end of the shortened array.
    let nextCorrect = block.correctIndex;
    if (i === block.correctIndex) {
      nextCorrect = 0;
    } else if (i < block.correctIndex) {
      nextCorrect = Math.max(0, block.correctIndex - 1);
    }
    onChange({ options: next, correctIndex: nextCorrect });
  }

  function setCorrect(i: number): void {
    onChange({ correctIndex: i });
  }

  return (
    <div className="hw-board-card lesson-mcq">
      <div className="hw-board-card__head">
        <strong className="hw-board-card__num">#{index + 1}</strong>
        <span className="lesson-blocks__kind-tag lesson-blocks__kind-tag--mcq">
          {labels.blockKindMcq}
        </span>
        <div className="lesson-prose__tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={mode === "edit"}
            className={
              "lesson-prose__tab" +
              (mode === "edit" ? " lesson-prose__tab--on" : "")
            }
            onClick={() => setMode("edit")}
          >
            {labels.tabEdit}
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === "preview"}
            className={
              "lesson-prose__tab" +
              (mode === "preview" ? " lesson-prose__tab--on" : "")
            }
            onClick={() => setMode("preview")}
          >
            {labels.tabPreview}
          </button>
        </div>
        <button
          type="button"
          className="btn btn--ghost btn--sm hw-board-card__remove"
          onClick={onRemove}
        >
          {labels.removeBlock}
        </button>
      </div>
      {mode === "preview" ? (
        <div className="lesson-mcq__preview" aria-live="polite">
          {hasQuestion ? (
            <>
              <p className="lesson-mcq__preview-question">{block.question}</p>
              <ol className="lesson-mcq__preview-options">
                {block.options.map((opt, i) => {
                  const isCorrect = i === block.correctIndex;
                  const trimmed = opt.trim();
                  return (
                    <li
                      key={i}
                      className={
                        "lesson-mcq__preview-option" +
                        (isCorrect ? " lesson-mcq__preview-option--correct" : "")
                      }
                    >
                      <span className="lesson-mcq__preview-option-text">
                        {trimmed.length > 0 ? trimmed : labels.mcqOptionPlaceholder}
                      </span>
                      {isCorrect ? (
                        <span className="lesson-mcq__preview-tag">
                          {labels.mcqPreviewCorrect}
                        </span>
                      ) : null}
                    </li>
                  );
                })}
              </ol>
              {block.explanation.trim().length > 0 ? (
                <div className="lesson-mcq__preview-explanation">
                  <span className="lesson-mcq__preview-explanation-label">
                    {labels.mcqExplanationLabel}
                  </span>
                  <p className="lesson-mcq__preview-explanation-body">
                    {block.explanation}
                  </p>
                </div>
              ) : null}
            </>
          ) : (
            <p className="lesson-prose__empty">{labels.mcqPreviewEmpty}</p>
          )}
        </div>
      ) : (
        <>
      <label className="sched-field lesson-mcq__question-field">
        <span className="sched-field__label">{labels.mcqQuestionLabel}</span>
        <textarea
          className="lesson-mcq__question"
          value={block.question}
          rows={3}
          placeholder={labels.mcqQuestionPlaceholder}
          onChange={(e) => onChange({ question: e.target.value })}
        />
      </label>
      <div className="lesson-mcq__meta">
        <span
          className={
            "lesson-prose__charcount" +
            (questionTooLong ? " lesson-prose__charcount--over" : "")
          }
        >
          {questionCountLabel}
        </span>
        {questionTooLong ? (
          <span className="sched-field-error" role="alert">
            {labels.mcqQuestionTooLong}
          </span>
        ) : null}
      </div>
      <fieldset className="lesson-mcq__options">
        <legend className="lesson-mcq__options-legend">
          {labels.mcqOptionsLabel}
        </legend>
        <p className="lesson-mcq__options-hint">{optionsHint}</p>
        <ul className="lesson-mcq__options-list">
          {block.options.map((opt, i) => {
            const optTooLong = opt.length > MCQ_MAX_OPTION_CHARS;
            const isDup = dupIdx === i;
            const optionLabel = labels.mcqOptionLabel.replace(
              "{index}",
              String(i + 1),
            );
            const correctAria = labels.mcqOptionCorrectAria.replace(
              "{index}",
              String(i + 1),
            );
            return (
              <li key={i} className="lesson-mcq__option-row">
                <label className="lesson-mcq__correct-radio">
                  <input
                    type="radio"
                    name={`mcq-correct-${block.clientId}`}
                    checked={block.correctIndex === i}
                    onChange={() => setCorrect(i)}
                    aria-label={correctAria}
                  />
                  <span className="lesson-mcq__correct-text">
                    {labels.mcqOptionCorrect}
                  </span>
                </label>
                <label className="lesson-mcq__option-field">
                  <span className="sched-field__label lesson-mcq__option-label">
                    {optionLabel}
                  </span>
                  <input
                    type="text"
                    className={
                      "lesson-mcq__option-input" +
                      (optTooLong || isDup
                        ? " lesson-mcq__option-input--error"
                        : "")
                    }
                    value={opt}
                    placeholder={labels.mcqOptionPlaceholder}
                    onChange={(e) => setOption(i, e.target.value)}
                  />
                </label>
                <button
                  type="button"
                  className="btn btn--ghost btn--sm lesson-mcq__option-remove"
                  onClick={() => removeOption(i)}
                  disabled={!canRemoveOption}
                  aria-label={labels.mcqRemoveOption}
                >
                  {labels.mcqOptionRemove}
                </button>
                {optTooLong ? (
                  <span
                    className="sched-field-error lesson-mcq__option-error"
                    role="alert"
                  >
                    {labels.mcqOptionTooLong}
                  </span>
                ) : null}
                {isDup ? (
                  <span
                    className="sched-field-error lesson-mcq__option-error"
                    role="alert"
                  >
                    {labels.mcqDuplicateOption}
                  </span>
                ) : null}
              </li>
            );
          })}
        </ul>
        <div className="lesson-mcq__options-footer">
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            onClick={addOption}
            disabled={!canAddOption}
          >
            {labels.mcqAddOption}
          </button>
          {!canAddOption ? (
            <span className="lesson-mcq__options-note">
              {labels.mcqMaxOptions.replace("{max}", String(MCQ_MAX_OPTIONS))}
            </span>
          ) : null}
          {!canRemoveOption ? (
            <span className="lesson-mcq__options-note">
              {labels.mcqMinOptions.replace("{min}", String(MCQ_MIN_OPTIONS))}
            </span>
          ) : null}
        </div>
      </fieldset>
      <label className="sched-field lesson-mcq__explanation-field">
        <span className="sched-field__label">{labels.mcqExplanationLabel}</span>
        <textarea
          className="lesson-mcq__explanation"
          value={block.explanation}
          rows={3}
          placeholder={labels.mcqExplanationPlaceholder}
          onChange={(e) => onChange({ explanation: e.target.value })}
        />
      </label>
      <div className="lesson-mcq__meta">
        <span
          className={
            "lesson-prose__charcount" +
            (explanationTooLong ? " lesson-prose__charcount--over" : "")
          }
        >
          {explanationCountLabel}
        </span>
        {explanationTooLong ? (
          <span className="sched-field-error" role="alert">
            {labels.mcqExplanationTooLong}
          </span>
        ) : null}
      </div>
        </>
      )}
    </div>
  );
}

// ------------------------------------------------------------------
// VIDEO block card
// ------------------------------------------------------------------

/**
 * Build an embeddable iframe `src` for the YouTube + Vimeo URLs the
 * backend validator + student viewer accept. Mirrors the same shape
 * the student-side `LessonVideoView` produces (privacy-respecting
 * `youtube-nocookie.com` for YouTube, `player.vimeo.com` for Vimeo).
 * Returns null if the URL doesn't match any known pattern — caller
 * falls back to a "paste a valid URL" hint.
 */
function buildEmbedSrc(rawUrl: string): string | null {
  const trimmed = rawUrl.trim();
  if (trimmed.length === 0) return null;
  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    return null;
  }
  if (url.protocol !== "https:") return null;
  const host = url.hostname.toLowerCase().replace(/^www\./, "");

  if (host === "youtube.com" || host === "m.youtube.com") {
    const id = url.searchParams.get("v");
    if (id && /^[A-Za-z0-9_-]{5,20}$/.test(id)) {
      return `https://www.youtube-nocookie.com/embed/${id}`;
    }
    return null;
  }
  if (host === "youtu.be") {
    const id = url.pathname.replace(/^\//, "").split("/")[0] ?? "";
    if (/^[A-Za-z0-9_-]{5,20}$/.test(id)) {
      return `https://www.youtube-nocookie.com/embed/${id}`;
    }
    return null;
  }
  if (host === "vimeo.com" || host === "player.vimeo.com") {
    const id = url.pathname.replace(/^\//, "").split("/")[0] ?? "";
    if (/^\d{5,15}$/.test(id)) {
      return `https://player.vimeo.com/video/${id}`;
    }
    return null;
  }
  return null;
}

function VideoBlockCard({
  index,
  block,
  labels,
  onChange,
  onRemove,
}: {
  index: number;
  block: LessonVideoBlock;
  labels: LessonBlocksEditorLabels;
  onChange: (patch: Partial<LessonVideoBlock>) => void;
  onRemove: () => void;
}) {
  const [dirty, setDirty] = useState(false);
  const [mode, setMode] = useState<"edit" | "preview">("edit");
  const diagnosis = useMemo(() => diagnoseVideoUrl(block.url), [block.url]);
  const embedSrc = useMemo(() => buildEmbedSrc(block.url), [block.url]);

  // Only surface the error chrome once the field has been touched, so a
  // fresh "" field doesn't paint an angry border the instant the card
  // renders.
  const showError = dirty && diagnosis.kind !== "ok" && diagnosis.kind !== "empty";
  const providerLabel =
    diagnosis.kind === "ok"
      ? labels.videoProviderLabel.replace(
          "{provider}",
          diagnosis.provider === "youtube"
            ? labels.videoProviderYoutube
            : labels.videoProviderVimeo,
        )
      : null;

  let errorMessage: string | null = null;
  if (showError) {
    if (diagnosis.kind === "invalid") {
      errorMessage = labels.videoInvalidUrl;
    } else if (diagnosis.kind === "not-https") {
      errorMessage = labels.videoNotHttps;
    } else if (diagnosis.kind === "host-not-allowed") {
      errorMessage = labels.videoHostNotAllowed;
    }
  }

  return (
    <div className="hw-board-card lesson-video">
      <div className="hw-board-card__head">
        <strong className="hw-board-card__num">#{index + 1}</strong>
        <span className="lesson-blocks__kind-tag lesson-blocks__kind-tag--video">
          {labels.blockKindVideo}
        </span>
        <div className="lesson-prose__tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={mode === "edit"}
            className={
              "lesson-prose__tab" +
              (mode === "edit" ? " lesson-prose__tab--on" : "")
            }
            onClick={() => setMode("edit")}
          >
            {labels.tabEdit}
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === "preview"}
            className={
              "lesson-prose__tab" +
              (mode === "preview" ? " lesson-prose__tab--on" : "")
            }
            onClick={() => setMode("preview")}
          >
            {labels.tabPreview}
          </button>
        </div>
        <button
          type="button"
          className="btn btn--ghost btn--sm hw-board-card__remove"
          onClick={onRemove}
        >
          {labels.removeBlock}
        </button>
      </div>
      {mode === "edit" ? (
        <>
          <label className="sched-field lesson-video__field">
            <span className="sched-field__label">{labels.videoUrlLabel}</span>
            <input
              type="url"
              inputMode="url"
              className={
                "lesson-video__input" +
                (showError ? " lesson-video__input--error" : "")
              }
              value={block.url}
              placeholder={labels.videoUrlPlaceholder}
              spellCheck={false}
              onChange={(e) => onChange({ url: e.target.value })}
              onBlur={() => setDirty(true)}
            />
          </label>
          <div className="lesson-video__meta">
            {providerLabel ? (
              <span className="lesson-video__provider">{providerLabel}</span>
            ) : (
              <span className="lesson-video__hint">{labels.videoUrlHint}</span>
            )}
            {errorMessage ? (
              <span className="sched-field-error" role="alert">
                {errorMessage}
              </span>
            ) : null}
          </div>
          <p className="lesson-video__allowed">
            {labels.videoAllowedHosts}{" "}
            <code className="lesson-video__allowed-list">
              {VIDEO_ALLOWED_HOSTS.join(", ")}
            </code>
          </p>
        </>
      ) : (
        <div className="lesson-video__preview" aria-live="polite">
          {embedSrc ? (
            <div className="lesson-video__preview-frame">
              <iframe
                src={embedSrc}
                title={labels.blockKindVideo}
                loading="lazy"
                allow="accelerometer; clipboard-write; encrypted-media; gyroscope; picture-in-picture; fullscreen"
                allowFullScreen
                referrerPolicy="strict-origin-when-cross-origin"
                className="lesson-video__preview-iframe"
              />
            </div>
          ) : (
            <p className="lesson-prose__empty">
              {labels.videoPreviewInvalid}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
