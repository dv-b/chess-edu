"use client";

import { useEffect, useMemo, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { Chess } from "chess.js";
import { Chessboard } from "react-chessboard";

/**
 * Shared, locale-agnostic walkthrough viewer used by:
 *
 * <ul>
 *   <li>The student lesson detail page — fed via
 *       {@link parseWalkthroughBlockFromWire} from the backend's
 *       {@code LessonResourceView} payload.</li>
 *   <li>The admin lesson editor's BOARD_WALKTHROUGH "Preview" tab —
 *       fed directly from the in-progress editor state so authors
 *       can step through their walkthrough before saving.</li>
 * </ul>
 *
 * <p>Both call sites render the exact same chrome (board + stepper +
 * move-list rail + ply-anchored annotations rail) with identical
 * keyboard-shortcut behaviour (←/→ Home/End on the focused card).
 * The label bag pattern (see {@link WalkthroughLabels}) keeps the
 * component free of next-intl imports so it can be reused without
 * wiring a `t` prop through.
 */

export const STARTING_FEN =
  "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

export type LessonAnnotation = { ply: number; text: string };

/** Normalised walkthrough shape consumed by the renderer. */
export type WalkthroughBlock = {
  /** Stable id for board mounting + key. */
  id: string;
  title: string | null;
  startingFen: string;
  /** UCI moves, e.g. "e2e4". */
  moves: string[];
  orientation: "white" | "black";
  annotations: LessonAnnotation[];
};

/**
 * Locale-agnostic label bag. Static strings come through verbatim;
 * the two interpolated labels are exposed as functions because ICU
 * plurals + variable substitution have to be resolved at format time
 * with their values. Same pattern as `BoardCardLabels` in
 * `@/components/board-card-view`.
 */
export type WalkthroughLabels = {
  /** Title shown when no `block.title` is set. */
  untitled: string;
  /** Falls back when the FEN doesn't parse / moves are illegal. */
  invalidBlock: string;
  /** Eyebrow above the side-to-move pill ("To move:"). */
  sideToMove: string;
  /** Side-to-move pill — white. */
  sideWhite: string;
  /** Side-to-move pill — black. */
  sideBlack: string;
  /** Stepper aria-labels. */
  start: string;
  prev: string;
  next: string;
  end: string;
  /** "{ply} of {total}" — interpolated. */
  plyLabel: (values: { ply: number; total: number }) => string;
  /** Move-list heading on the rail. */
  movesHeading: string;
  /** Hint when {@code moves.length === 0}. */
  movesEmpty: string;
  /** Annotations-list heading on the rail. */
  annotationsHeading: string;
  /** Anchor for an annotation pinned at ply 0. */
  annotationAtStart: string;
  /** "After move {ply}" — interpolated. */
  annotationAtPly: (values: { ply: number }) => string;
};

// -----------------------------------------------------------------
// Parsing
// -----------------------------------------------------------------

type WirePayload = Record<string, unknown>;

/**
 * Pull a walkthrough block off a wire payload, validating just
 * enough to render safely. Payload is authored + validated server-
 * side, so this is strictly defensive — we don't reject content
 * the backend would accept.
 */
export function parseWalkthroughBlockFromWire(input: {
  id: string;
  title: string | null;
  payload: WirePayload | undefined | null;
}): WalkthroughBlock | null {
  const p = input.payload ?? {};
  const fen =
    typeof p.startingFen === "string" && p.startingFen.trim().length > 0
      ? (p.startingFen as string)
      : STARTING_FEN;
  const rawMoves = Array.isArray(p.moves) ? (p.moves as unknown[]) : [];
  const moves: string[] = rawMoves.filter(
    (m): m is string => typeof m === "string" && m.length >= 4,
  );
  const orientation =
    p.orientation === "black"
      ? "black"
      : p.orientation === "white"
        ? "white"
        : "white";
  const rawAnns = Array.isArray(p.annotations)
    ? (p.annotations as unknown[])
    : [];
  const annotations: LessonAnnotation[] = [];
  for (const a of rawAnns) {
    if (!a || typeof a !== "object") continue;
    const rec = a as Record<string, unknown>;
    const ply =
      typeof rec.ply === "number" && Number.isInteger(rec.ply) ? rec.ply : null;
    const text = typeof rec.text === "string" ? rec.text : null;
    if (ply === null || text === null) continue;
    if (ply < 0 || ply > moves.length) continue;
    annotations.push({ ply, text });
  }
  annotations.sort((a, b) => a.ply - b.ply);
  return {
    id: input.id,
    title: input.title,
    startingFen: fen,
    moves,
    orientation,
    annotations,
  };
}

/**
 * Replay the UCI move list against {@code startingFen} and return
 * the FEN after every position — index 0 is the starting position,
 * index N is the position after the Nth move. Returns null on any
 * illegality so callers can fall back to the invalid-block copy.
 */
function buildFenLine(startFen: string, moves: string[]): string[] | null {
  let chess: Chess;
  try {
    chess = new Chess(startFen);
  } catch {
    return null;
  }
  const line: string[] = [chess.fen()];
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
    line.push(chess.fen());
  }
  return line;
}

function sideToMoveLabelFor(fen: string, labels: WalkthroughLabels): string {
  try {
    const c = new Chess(fen);
    return c.turn() === "w" ? labels.sideWhite : labels.sideBlack;
  } catch {
    return labels.sideWhite;
  }
}

// -----------------------------------------------------------------
// Component
// -----------------------------------------------------------------

type Props = {
  block: WalkthroughBlock | null;
  labels: WalkthroughLabels;
  /** Prefix for the chess board id; uniqueness matters when multiple
   *  walkthroughs render on the same page. */
  idPrefix: string;
};

export function LessonWalkthroughView({ block, labels, idPrefix }: Props) {
  const fenLine = useMemo(
    () => (block ? buildFenLine(block.startingFen, block.moves) : null),
    [block],
  );

  const totalPlies = fenLine?.length ?? 0;
  const [ply, setPly] = useState(0);

  // Clamp ply back into range if the fen line shrinks (block edited
  // mid-render) — harmless in the common case.
  useEffect(() => {
    setPly((p) => Math.min(Math.max(p, 0), Math.max(totalPlies - 1, 0)));
  }, [totalPlies]);

  if (!block || !fenLine || fenLine.length === 0) {
    return (
      <article className="lesson-viewer__block lesson-viewer__block--walkthrough">
        {block?.title ? (
          <h3 className="lesson-viewer__block-title">{block.title}</h3>
        ) : null}
        <p className="lesson-viewer__invalid">{labels.invalidBlock}</p>
      </article>
    );
  }

  const lastPly = fenLine.length - 1;
  const safePly = Math.min(Math.max(ply, 0), lastPly);
  const currentFen = fenLine[safePly] ?? block.startingFen;
  const sideLabel = sideToMoveLabelFor(currentFen, labels);
  const title = block.title ?? labels.untitled;

  // Keyboard navigation: ←/→ step a ply, Home/End jump to start/end.
  // Only fires when the article itself has focus (`tabIndex=0`); page-
  // level arrow scroll is preserved otherwise. Multiple walkthroughs
  // on one page are independent — focus is per-card.
  const onCardKeyDown = (e: ReactKeyboardEvent<HTMLElement>) => {
    if (e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;
    switch (e.key) {
      case "ArrowLeft":
        e.preventDefault();
        setPly((p) => Math.max(0, p - 1));
        break;
      case "ArrowRight":
        e.preventDefault();
        setPly((p) => Math.min(lastPly, p + 1));
        break;
      case "Home":
        e.preventDefault();
        setPly(0);
        break;
      case "End":
        e.preventDefault();
        setPly(lastPly);
        break;
      default:
        break;
    }
  };

  return (
    <article
      className="lesson-viewer__block lesson-viewer__block--walkthrough"
      tabIndex={0}
      onKeyDown={onCardKeyDown}
      aria-label={title}
    >
      <header className="lesson-walkthrough__head">
        <h3 className="lesson-viewer__block-title">{title}</h3>
        <span className="lesson-walkthrough__side">
          {labels.sideToMove} {sideLabel}
        </span>
      </header>

      <div className="lesson-walkthrough__body">
        <div className="lesson-walkthrough__board-wrap">
          <Chessboard
            options={{
              id: `${idPrefix}-${block.id}`,
              position: currentFen,
              boardOrientation: block.orientation,
              allowDragging: false,
              allowDrawingArrows: false,
              animationDurationInMs: 150,
            }}
          />
          <div className="lesson-walkthrough__stepper">
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => setPly(0)}
              disabled={safePly === 0}
              aria-label={labels.start}
            >
              ⏮
            </button>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => setPly((p) => Math.max(0, p - 1))}
              disabled={safePly === 0}
              aria-label={labels.prev}
            >
              ◀
            </button>
            <span className="lesson-walkthrough__ply-label">
              {labels.plyLabel({ ply: safePly, total: lastPly })}
            </span>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => setPly((p) => Math.min(lastPly, p + 1))}
              disabled={safePly === lastPly}
              aria-label={labels.next}
            >
              ▶
            </button>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => setPly(lastPly)}
              disabled={safePly === lastPly}
              aria-label={labels.end}
            >
              ⏭
            </button>
          </div>
        </div>

        <div className="lesson-walkthrough__rail">
          <h4 className="lesson-walkthrough__rail-heading">
            {labels.movesHeading}
          </h4>
          {block.moves.length === 0 ? (
            <p className="lesson-walkthrough__rail-empty">
              {labels.movesEmpty}
            </p>
          ) : (
            <ol className="lesson-walkthrough__move-list">
              {block.moves.map((uci, i) => {
                const targetPly = i + 1;
                const active = safePly === targetPly;
                return (
                  <li key={`mv-${i}`}>
                    <button
                      type="button"
                      className={
                        "lesson-walkthrough__move" +
                        (active ? " lesson-walkthrough__move--active" : "")
                      }
                      onClick={() => setPly(targetPly)}
                      aria-current={active ? "true" : undefined}
                    >
                      {uci}
                    </button>
                  </li>
                );
              })}
            </ol>
          )}

          {block.annotations.length > 0 ? (
            <>
              <h4 className="lesson-walkthrough__rail-heading lesson-walkthrough__rail-heading--second">
                {labels.annotationsHeading}
              </h4>
              <ul className="lesson-walkthrough__annotations">
                {block.annotations.map((ann, i) => {
                  const active = safePly === ann.ply;
                  const anchorLabel =
                    ann.ply === 0
                      ? labels.annotationAtStart
                      : labels.annotationAtPly({ ply: ann.ply });
                  return (
                    <li
                      key={`ann-${i}`}
                      className={
                        "lesson-walkthrough__annotation" +
                        (active
                          ? " lesson-walkthrough__annotation--active"
                          : "")
                      }
                    >
                      <button
                        type="button"
                        className="lesson-walkthrough__annotation-anchor"
                        onClick={() => setPly(ann.ply)}
                        aria-current={active ? "true" : undefined}
                      >
                        {anchorLabel}
                      </button>
                      <p className="lesson-walkthrough__annotation-text">
                        {ann.text}
                      </p>
                    </li>
                  );
                })}
              </ul>
            </>
          ) : null}
        </div>
      </div>
    </article>
  );
}
