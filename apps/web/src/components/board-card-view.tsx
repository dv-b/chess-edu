"use client";

import { useEffect, useMemo, useState } from "react";
import { Chess } from "chess.js";
import { Chessboard } from "react-chessboard";

/**
 * Read-only viewer for a single authored chess content block, shared between
 * the admin homework preview ({@code Task #27}) and the student homework
 * viewer ({@code Task #23}).
 *
 * <p>The block is whatever the backend returned — we parse the raw payload
 * defensively so even a malformed block (shouldn't happen past the validator
 * but worth guarding) still renders a useful empty-board fallback rather
 * than crashing the surface.
 *
 * <p>Interactions are deliberately minimal:
 * <ul>
 *   <li>Step-through replay over the prefix moves with ◀ / ▶ buttons and a
 *       clickable move list (jump to any ply).</li>
 *   <li>When {@code canRevealSolution} is true <em>and</em> a solution is
 *       present, a "Show solution" disclosure steps through the solution
 *       continuation on top of the final prefix position.</li>
 *   <li>No drag-and-drop — this is a viewer, not an editor.</li>
 * </ul>
 *
 * <p>This component is i18n-agnostic: labels come in via the {@link labels}
 * prop so the same tree can be mounted from the admin (uses
 * {@code admin.classes.homework.boards.*}) and the student shell (uses
 * {@code student.homework.boards.*}).
 */

export const STARTING_FEN =
  "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

export type BoardCardBlock = {
  /** Stable list key — id from the backend is fine. */
  id: string;
  title: string | null;
  startingFen: string;
  moves: string[];
  orientation: "white" | "black";
  /**
   * Optional teacher solution. Strictly present only on admin / preview
   * payloads — the backend strips this for students, so callers with
   * {@code canRevealSolution=false} can pass {@code null} freely.
   */
  solution: string[] | null;
};

export type BoardCardLabels = {
  /** Rendered above the board when the block has no author-provided title. */
  untitled: string;
  movesHeading: string;
  /**
   * Plural-aware label for "N move(s)". Rendered as a function so the
   * caller resolves it through next-intl's {@code t(key, { count })} at
   * call time — ICU plural strings can't be post-processed with
   * {@code .replace()} without breaking the locale's plural rules.
   */
  movesCount: (count: number) => string;
  movesEmpty: string;
  start: string;
  prev: string;
  next: string;
  end: string;
  /**
   * "Position {ply} of {total}". Same rationale as {@link movesCount}:
   * next-intl requires the variables at format time.
   */
  plyLabel: (ply: number, total: number) => string;
  sideToMove: string;
  sideWhite: string;
  sideBlack: string;
  showSolution: string;
  hideSolution: string;
  solutionHeading: string;
  solutionEmpty: string;
  /** Shown when the block couldn't be parsed (bad FEN etc.). */
  invalidBlock: string;
};

type BoardPayload = {
  startingFen?: unknown;
  moves?: unknown;
  orientation?: unknown;
  solution?: unknown;
};

/**
 * Parse the raw {@code payload} from a backend {@code HomeworkResourceView}
 * into the shape this viewer uses. Unknown / malformed fields fall back to
 * sane defaults; anything that doesn't look like a BOARD block at all returns
 * null so callers can skip it.
 */
export function parseBoardBlock(resource: {
  id: string;
  kind: string;
  title: string | null;
  payload: unknown;
}): BoardCardBlock | null {
  if (resource.kind.toUpperCase() !== "BOARD") return null;
  const p = (resource.payload ?? {}) as BoardPayload;
  const fen =
    typeof p.startingFen === "string" ? (p.startingFen as string) : STARTING_FEN;
  const moves = Array.isArray(p.moves)
    ? (p.moves as unknown[])
        .filter((x): x is string => typeof x === "string")
        .map((s) => s.toLowerCase())
    : [];
  const orientation =
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
  return {
    id: resource.id,
    title: resource.title,
    startingFen: fen,
    moves,
    orientation,
    solution,
  };
}

/**
 * Replay {@code moves} on top of {@code startFen}; return the list of FENs
 * reached after each ply, with {@code startFen} at index 0. Returns null if
 * any move is illegal or the FEN is malformed — callers render an
 * "invalid block" placeholder in that case.
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

function sideToMoveLabelFor(fen: string, labels: BoardCardLabels): string {
  try {
    const c = new Chess(fen);
    return c.turn() === "w" ? labels.sideWhite : labels.sideBlack;
  } catch {
    return "";
  }
}

export function BoardCardView({
  block,
  labels,
  canRevealSolution = false,
  /**
   * Distinct key prefix used for the underlying chessboard instances.
   * Pass the admin / student surface name so multiple viewers on the same
   * page don't collide on react-chessboard's `id` option.
   */
  idPrefix = "bcv",
}: {
  block: BoardCardBlock;
  labels: BoardCardLabels;
  canRevealSolution?: boolean;
  idPrefix?: string;
}) {
  // Full line for the prefix. null = unplayable block (bad FEN or illegal
  // move in the sequence). We still render a stub so the surface doesn't
  // collapse.
  const line = useMemo(
    () => buildFenLine(block.startingFen, block.moves),
    [block.startingFen, block.moves],
  );

  const total = line ? line.length : 1;
  const [ply, setPly] = useState<number>(total - 1);

  // If the block changes shape (new move appended by a teacher while the
  // student is looking), clamp the ply so we don't point past the end.
  useEffect(() => {
    setPly((p) => Math.min(Math.max(p, 0), Math.max(total - 1, 0)));
  }, [total]);

  // Solution state — only relevant when caller opted in AND the block
  // carries a solution. Kept independent of prefix ply; showing the
  // solution jumps the display to the end of prefix, then plays solution
  // moves on top.
  const [showSolution, setShowSolution] = useState(false);
  const solutionLine = useMemo(() => {
    if (!canRevealSolution || !block.solution || block.solution.length === 0) {
      return null;
    }
    const base = line ? line[line.length - 1] : block.startingFen;
    if (!base) return null;
    return buildFenLine(base, block.solution);
  }, [canRevealSolution, block.solution, block.startingFen, line]);
  const solutionTotal = solutionLine ? solutionLine.length : 1;
  const [solPly, setSolPly] = useState<number>(0);

  useEffect(() => {
    setSolPly(0);
  }, [solutionLine]);

  if (!line) {
    return (
      <article className="bcv">
        <header className="bcv__head">
          <h4 className="bcv__title">{block.title?.trim() || labels.untitled}</h4>
        </header>
        <p className="bcv__invalid" role="alert">
          {labels.invalidBlock}
        </p>
      </article>
    );
  }

  const currentFen = line[ply] ?? block.startingFen;
  const movesCountLabel =
    block.moves.length === 0
      ? labels.movesEmpty
      : labels.movesCount(block.moves.length);

  return (
    <article className="bcv">
      <header className="bcv__head">
        <h4 className="bcv__title">{block.title?.trim() || labels.untitled}</h4>
        <span className="bcv__side">
          {labels.sideToMove}:{" "}
          <strong>{sideToMoveLabelFor(currentFen, labels)}</strong>
        </span>
      </header>

      <div className="bcv__board">
        <Chessboard
          options={{
            id: `${idPrefix}-${block.id}-main`,
            position: currentFen,
            boardOrientation: block.orientation,
            allowDragging: false,
            allowDrawingArrows: false,
            animationDurationInMs: 150,
          }}
        />
      </div>

      {block.moves.length > 0 ? (
        <div className="bcv__controls">
          <div className="bcv__stepper" role="group" aria-label={labels.movesHeading}>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => setPly(0)}
              disabled={ply === 0}
              aria-label={labels.start}
            >
              ⏮
            </button>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => setPly((p) => Math.max(0, p - 1))}
              disabled={ply === 0}
              aria-label={labels.prev}
            >
              ◀
            </button>
            <span className="bcv__ply" aria-live="polite">
              {labels.plyLabel(ply, total - 1)}
            </span>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => setPly((p) => Math.min(total - 1, p + 1))}
              disabled={ply === total - 1}
              aria-label={labels.next}
            >
              ▶
            </button>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => setPly(total - 1)}
              disabled={ply === total - 1}
              aria-label={labels.end}
            >
              ⏭
            </button>
          </div>

          <div className="bcv__moves">
            <span className="bcv__moves-count">{movesCountLabel}</span>
            <ol className="bcv__move-list">
              {block.moves.map((m, i) => {
                const target = i + 1;
                const active = ply === target;
                return (
                  <li key={`${i}-${m}`}>
                    <button
                      type="button"
                      className={
                        "bcv__move" + (active ? " bcv__move--active" : "")
                      }
                      onClick={() => setPly(target)}
                      aria-current={active ? "true" : undefined}
                    >
                      <code>{m}</code>
                    </button>
                  </li>
                );
              })}
            </ol>
          </div>
        </div>
      ) : null}

      {canRevealSolution && block.solution && block.solution.length > 0 ? (
        <section className="bcv__solution">
          <div className="bcv__solution-head">
            <h5 className="bcv__solution-heading">{labels.solutionHeading}</h5>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => setShowSolution((v) => !v)}
              aria-expanded={showSolution}
            >
              {showSolution ? labels.hideSolution : labels.showSolution}
            </button>
          </div>
          {showSolution ? (
            solutionLine ? (
              <>
                <div className="bcv__board">
                  <Chessboard
                    options={{
                      id: `${idPrefix}-${block.id}-sol`,
                      position:
                        solutionLine[solPly] ??
                        solutionLine[solutionLine.length - 1] ??
                        block.startingFen,
                      boardOrientation: block.orientation,
                      allowDragging: false,
                      allowDrawingArrows: false,
                      animationDurationInMs: 150,
                    }}
                  />
                </div>
                <div className="bcv__controls">
                  <div
                    className="bcv__stepper"
                    role="group"
                    aria-label={labels.solutionHeading}
                  >
                    <button
                      type="button"
                      className="btn btn--ghost btn--sm"
                      onClick={() => setSolPly(0)}
                      disabled={solPly === 0}
                      aria-label={labels.start}
                    >
                      ⏮
                    </button>
                    <button
                      type="button"
                      className="btn btn--ghost btn--sm"
                      onClick={() => setSolPly((p) => Math.max(0, p - 1))}
                      disabled={solPly === 0}
                      aria-label={labels.prev}
                    >
                      ◀
                    </button>
                    <span className="bcv__ply" aria-live="polite">
                      {labels.plyLabel(solPly, solutionTotal - 1)}
                    </span>
                    <button
                      type="button"
                      className="btn btn--ghost btn--sm"
                      onClick={() =>
                        setSolPly((p) => Math.min(solutionTotal - 1, p + 1))
                      }
                      disabled={solPly === solutionTotal - 1}
                      aria-label={labels.next}
                    >
                      ▶
                    </button>
                    <button
                      type="button"
                      className="btn btn--ghost btn--sm"
                      onClick={() => setSolPly(solutionTotal - 1)}
                      disabled={solPly === solutionTotal - 1}
                      aria-label={labels.end}
                    >
                      ⏭
                    </button>
                  </div>
                  <ol className="bcv__move-list">
                    {block.solution.map((m, i) => {
                      const target = i + 1;
                      const active = solPly === target;
                      return (
                        <li key={`sol-${i}-${m}`}>
                          <button
                            type="button"
                            className={
                              "bcv__move" +
                              (active ? " bcv__move--active" : "")
                            }
                            onClick={() => setSolPly(target)}
                            aria-current={active ? "true" : undefined}
                          >
                            <code>{m}</code>
                          </button>
                        </li>
                      );
                    })}
                  </ol>
                </div>
              </>
            ) : (
              <p className="bcv__invalid" role="alert">
                {labels.invalidBlock}
              </p>
            )
          ) : null}
        </section>
      ) : null}
    </article>
  );
}

/**
 * Convenience helper: unwrap a {@code HomeworkDetail.resources} list into
 * parsed {@link BoardCardBlock} items, skipping unknown kinds. Callers can
 * map the result straight into {@link BoardCardView} instances.
 */
export function blocksFromResourcesForView(
  resources: Array<{
    id: string;
    kind: string;
    title: string | null;
    payload: unknown;
  }>,
): BoardCardBlock[] {
  const out: BoardCardBlock[] = [];
  for (const r of resources) {
    const parsed = parseBoardBlock(r);
    if (parsed) out.push(parsed);
  }
  return out;
}
