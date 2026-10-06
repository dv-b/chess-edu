"use client";

import { useMemo, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { Chess } from "chess.js";
import { Chessboard } from "react-chessboard";
import {
  BoardCardView,
  type BoardCardBlock,
  type BoardCardLabels,
} from "@/components/board-card-view";
import {
  revealSolutionAction,
  submitBlockAttemptAction,
  type BlockAttemptView,
} from "../../actions";

/**
 * Label bag for the per-block attempt panel. Kept i18n-agnostic the same
 * way {@link BoardCardLabels} is, so the parent surface maps next-intl
 * strings into the shape once and passes them down.
 *
 * <p>{@code ticker} is a map of display-status keys to ready-to-render
 * labels (already pluralised etc. by the caller). The five keys cover every
 * state the student can be in for a given block — see
 * {@link displayStatusFor}.
 */
export type BlockAttemptLabels = {
  ticker: {
    NOT_ATTEMPTED: string;
    SUBMITTED: string;
    REVIEWED_OK: string;
    REVIEWED_NEEDS_WORK: string;
    SOLUTION_REVEALED: string;
  };
  yourMovesHeading: string;
  playInstruction: string;
  retryInstruction: string;
  /**
   * Rendered above the interactive area once the authored solution has been
   * revealed. Different from {@link retryInstruction} because the student is
   * now reproducing a known line rather than guessing.
   */
  solutionRevealedInstruction: string;
  submitButton: string;
  submitting: string;
  undoLast: string;
  resetMoves: string;
  noMovesYet: string;
  /** Button that triggers {@link revealSolutionAction}. */
  showSolution: string;
  /** Transient label while the reveal request is in flight. */
  revealing: string;
  teacherNoteHeading: string;
  /**
   * "Submitted {when}" — resolved through next-intl's {@code t(key, { when })}
   * because next-intl requires interpolation values at format time, not
   * after {@code .replace()}. See also {@link lastReviewedAt}.
   */
  lastSubmittedAt: (when: string) => string;
  /** "Reviewed {when}" — see {@link lastSubmittedAt}. */
  lastReviewedAt: (when: string) => string;
  illegalMoveNotice: string;
  /** Error messages keyed by the same union the action returns. */
  errors: {
    invalid: string;
    unauthorised: string;
    forbidden: string;
    conflict: string;
    notfound: string;
    network: string;
    unknown: string;
  };
};

type DisplayStatus =
  | "NOT_ATTEMPTED"
  | "SUBMITTED"
  | "REVIEWED_OK"
  | "REVIEWED_NEEDS_WORK"
  | "SOLUTION_REVEALED";

/**
 * Collapse the backend's three persisted statuses + the optional
 * {@code solutionRevealedAt} timestamp into the five display states the
 * student UI cares about. Absence of an attempt means "Not attempted";
 * a NEEDS_WORK attempt with a reveal timestamp becomes the derived
 * "Solution revealed" — mirrors the design doc's state machine.
 */
function displayStatusFor(attempt: BlockAttemptView | null): DisplayStatus {
  if (!attempt) return "NOT_ATTEMPTED";
  if (attempt.status === "REVIEWED_NEEDS_WORK" && attempt.solutionRevealedAt) {
    return "SOLUTION_REVEALED";
  }
  return attempt.status;
}

/**
 * Apply the block's authored move prefix on top of its starting FEN and
 * return the resulting FEN. Returns null if the block is malformed so the
 * panel can render a fallback — this path should never trigger in practice
 * because the backend validates both before accepting the resource, but
 * defensive parsing keeps a single bad row from nuking the whole surface.
 */
function computeBaseFen(block: BoardCardBlock): string | null {
  let chess: Chess;
  try {
    chess = new Chess(block.startingFen);
  } catch {
    return null;
  }
  for (const uci of block.moves) {
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
  return chess.fen();
}

/**
 * Single-block panel that either lets the student play moves and submit, or
 * replays their most recent submission for read-only review. The parent
 * (detail page) owns the block + attempt list and wires a callback so the
 * optimistic state returned by {@link submitBlockAttemptAction} can be
 * merged into the page-level view without waiting for a router refresh.
 *
 * <p>Interaction rules (from the design doc and #38's scope):
 * <ul>
 *   <li>{@code NOT_ATTEMPTED} or {@code REVIEWED_NEEDS_WORK} → interactive.
 *       The board accepts drags on the post-prefix position; moves are
 *       validated client-side with chess.js and appended to a local UCI
 *       list. Undo / reset / submit chips sit beside the move list.</li>
 *   <li>Everything else → read-only. We render the attempt's moves with
 *       the shared {@link BoardCardView} using the post-prefix FEN as the
 *       stepping start, so the stepper shows only the student's work —
 *       not the authored prefix.</li>
 * </ul>
 *
 * <p>Reveal + try-again UX (#39) is NOT in this sub-task; we surface the
 * teacher's note read-only for NEEDS_WORK so the retry is at least
 * informed.
 */
export function BlockAttemptPanel({
  block,
  blockIndex,
  homeworkId,
  attempt,
  viewerLabels,
  attemptLabels,
  locale,
  onSubmitted,
  onRevealed,
  formatInstant,
}: {
  block: BoardCardBlock;
  blockIndex: number;
  homeworkId: string;
  attempt: BlockAttemptView | null;
  viewerLabels: BoardCardLabels;
  attemptLabels: BlockAttemptLabels;
  locale: string;
  /**
   * Notify the parent when a submission lands so it can replace its local
   * attempts entry and re-render without a router round-trip. The parent
   * is free to ignore this and rely on the server-side revalidation — the
   * callback exists purely for snappier feedback.
   */
  onSubmitted: (attempt: BlockAttemptView) => void;
  /**
   * Notify the parent when a reveal lands so it can stash the fresh attempt
   * (with a non-null {@code solutionRevealedAt}). The authored solution moves
   * themselves arrive on the next detail fetch — the parent runs
   * {@code router.refresh()} inside a transition to bring them in.
   */
  onRevealed: (attempt: BlockAttemptView) => void;
  /** Shared instant formatter so locale + timezone stay consistent. */
  formatInstant: (iso: string) => string;
}) {
  const router = useRouter();
  const status = displayStatusFor(attempt);
  // SOLUTION_REVEALED stays interactive: the student has seen the authored
  // line and should be able to reproduce it and resubmit right away. The
  // live row is still REVIEWED_NEEDS_WORK underneath, so POSTing a fresh
  // attempt overwrites it (and strips the solution back off the wire until
  // another reveal). REVIEWED_OK is the only terminal-ish status that locks
  // the board; SUBMITTED is "awaiting review" and also locks.
  const interactive =
    status === "NOT_ATTEMPTED" ||
    status === "REVIEWED_NEEDS_WORK" ||
    status === "SOLUTION_REVEALED";

  const baseFen = useMemo(() => computeBaseFen(block), [block]);

  // Local draft moves the student is accumulating. Always starts empty —
  // even for a retry, the student is building a fresh attempt on top of the
  // authored prefix, not editing the prior submission in place.
  const [draftMoves, setDraftMoves] = useState<string[]>([]);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  // Reveal lives in its own transition so a pending reveal doesn't block
  // the submit button from reading pending=false (and vice versa). Both
  // ultimately gate user input, but the error slots are separate so the
  // copy under each button only reacts to its own action.
  const [revealError, setRevealError] = useState<string | null>(null);
  const [revealing, startRevealTransition] = useTransition();

  // Position shown on the board: post-prefix + draft moves. Computed
  // fresh each render — the move list is short so this is cheap.
  const draftFen = useMemo(() => {
    if (!baseFen) return null;
    let chess: Chess;
    try {
      chess = new Chess(baseFen);
    } catch {
      return null;
    }
    for (const uci of draftMoves) {
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
    return chess.fen();
  }, [baseFen, draftMoves]);

  function handlePieceDrop({
    sourceSquare,
    targetSquare,
  }: {
    sourceSquare: string;
    targetSquare: string | null;
  }): boolean {
    if (!targetSquare || !draftFen) return false;
    let clone: Chess;
    try {
      clone = new Chess(draftFen);
    } catch {
      return false;
    }
    let result;
    try {
      // Always propose queen promotion — the UI doesn't yet offer a
      // picker. If the move isn't a promotion, chess.js ignores the flag.
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
    setDraftMoves((prev) => [...prev, uci]);
    setSubmitError(null);
    return true;
  }

  function undoLast() {
    setDraftMoves((prev) => prev.slice(0, -1));
    setSubmitError(null);
  }

  function resetDraft() {
    setDraftMoves([]);
    setSubmitError(null);
  }

  function onSubmitClick() {
    if (draftMoves.length === 0 || pending) return;
    startTransition(async () => {
      const result = await submitBlockAttemptAction(
        homeworkId,
        blockIndex,
        draftMoves,
        // The student layout enforces a valid AppLocale upstream, but the
        // server action re-validates so the cast here is safe.
        locale as Parameters<typeof submitBlockAttemptAction>[3],
      );
      if (result.ok) {
        onSubmitted(result.attempt);
        setDraftMoves([]);
        setSubmitError(null);
      } else {
        setSubmitError(attemptLabels.errors[result.error]);
      }
    });
  }

  function onShowSolutionClick() {
    if (revealing || pending) return;
    setRevealError(null);
    startRevealTransition(async () => {
      const result = await revealSolutionAction(
        homeworkId,
        blockIndex,
        locale as Parameters<typeof revealSolutionAction>[2],
      );
      if (result.ok) {
        // The backend un-strips the solution on the next fetch of the
        // student detail — kick a refresh so the panel re-renders with the
        // authored solution populated. Page-level revalidation already
        // happened inside the action; router.refresh is belt-and-suspenders
        // to surface the new payload now rather than on next navigation.
        onRevealed(result.attempt);
        router.refresh();
      } else {
        // 409 maps to "conflict", which covers both "no live NEEDS_WORK
        // attempt to reveal against" and "block has no authored solution".
        // Both land on the generic error copy — the wire doesn't let us
        // distinguish them and the user's action is the same either way
        // (wait / ask the teacher).
        setRevealError(attemptLabels.errors[result.error]);
      }
    });
  }

  const solutionUci = block.solution;
  const hasSolution = !!solutionUci && solutionUci.length > 0;

  // Synthetic block for the revealed-solution viewer. Stepping-start is the
  // post-prefix FEN so the stepper walks only the authored continuation —
  // the prefix moves are already baked into the starting position. We lean
  // on {@link BoardCardView} here rather than rolling a bespoke stepper to
  // keep the UX (controls, move list, side-to-move pill) consistent with
  // the admin preview.
  const solutionBlock = useMemo<BoardCardBlock | null>(() => {
    if (!hasSolution || !baseFen || !solutionUci) return null;
    return {
      id: `${block.id}-solution`,
      title: null,
      startingFen: baseFen,
      moves: solutionUci,
      orientation: block.orientation,
      solution: null,
    };
  }, [block.id, block.orientation, baseFen, hasSolution, solutionUci]);

  // Synthetic "attempt replay" block for the shared BoardCardView:
  // stepping-start is the post-prefix FEN, moves are the student's work.
  // Separate instance per attempt so the stepper resets cleanly after a
  // resubmit.
  const replayBlock = useMemo<BoardCardBlock | null>(() => {
    if (!attempt || !baseFen) return null;
    return {
      id: `${block.id}-attempt-${attempt.id}`,
      title: block.title,
      startingFen: baseFen,
      moves: attempt.moves,
      orientation: block.orientation,
      // Solution goes through the #39 reveal flow, not this panel.
      solution: null,
    };
  }, [attempt, baseFen, block.id, block.title, block.orientation]);

  return (
    <article className="student-hw-attempt">
      <header className="student-hw-attempt__head">
        <h4 className="student-hw-attempt__title">
          {block.title?.trim() || viewerLabels.untitled}
        </h4>
        <span
          className={
            "student-hw-attempt__pill student-hw-attempt__pill--" +
            status.toLowerCase()
          }
        >
          {attemptLabels.ticker[status]}
        </span>
      </header>

      {attempt ? (
        <p className="student-hw-attempt__meta">
          {attemptLabels.lastSubmittedAt(formatInstant(attempt.createdAt))}
          {attempt.reviewedAt
            ? " · " +
              attemptLabels.lastReviewedAt(formatInstant(attempt.reviewedAt))
            : null}
        </p>
      ) : null}

      {/*
       * Teacher note sticks around through the derived SOLUTION_REVEALED
       * state too — it's still the same live row underneath, just with
       * {@code solutionRevealedAt} stamped. Students benefit from seeing
       * the original feedback next to the authored solution.
       */}
      {(status === "REVIEWED_NEEDS_WORK" || status === "SOLUTION_REVEALED") &&
      attempt?.teacherNote ? (
        <section
          className="student-hw-attempt__note"
          aria-label={attemptLabels.teacherNoteHeading}
        >
          <h5 className="student-hw-attempt__note-heading">
            {attemptLabels.teacherNoteHeading}
          </h5>
          <p>{attempt.teacherNote}</p>
        </section>
      ) : null}

      {!interactive && replayBlock ? (
        // Non-editable view of the student's submitted work.
        <BoardCardView
          block={replayBlock}
          labels={viewerLabels}
          canRevealSolution={false}
          idPrefix={`student-att-${blockIndex}`}
        />
      ) : null}

      {interactive && baseFen ? (
        <div className="student-hw-attempt__play">
          <p className="student-hw-attempt__play-hint">
            {status === "SOLUTION_REVEALED"
              ? attemptLabels.solutionRevealedInstruction
              : status === "REVIEWED_NEEDS_WORK"
                ? attemptLabels.retryInstruction
                : attemptLabels.playInstruction}
          </p>
          <div className="student-hw-attempt__board">
            <Chessboard
              options={{
                id: `student-play-${blockIndex}-${block.id}`,
                position: draftFen ?? baseFen,
                boardOrientation: block.orientation,
                // Only allow dragging if we have a valid base and we
                // haven't driven the position into an invalid state
                // somehow (shouldn't happen — chess.js gates each move).
                allowDragging: !pending && !!draftFen,
                onPieceDrop: handlePieceDrop,
                allowDrawingArrows: false,
                animationDurationInMs: 150,
              }}
            />
          </div>

          <div className="student-hw-attempt__moves">
            <div className="student-hw-attempt__moves-head">
              <h5 className="student-hw-attempt__moves-heading">
                {attemptLabels.yourMovesHeading}
              </h5>
              <span className="student-hw-attempt__count">
                {draftMoves.length === 0
                  ? attemptLabels.noMovesYet
                  : viewerLabels.movesCount(draftMoves.length)}
              </span>
            </div>
            <ol className="student-hw-attempt__move-list">
              {draftMoves.map((m, i) => (
                <li key={`${i}-${m}`}>
                  <code className="student-hw-attempt__move">{m}</code>
                </li>
              ))}
            </ol>
            <div className="student-hw-attempt__actions">
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={undoLast}
                disabled={draftMoves.length === 0 || pending}
              >
                {attemptLabels.undoLast}
              </button>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={resetDraft}
                disabled={draftMoves.length === 0 || pending}
              >
                {attemptLabels.resetMoves}
              </button>
              <button
                type="button"
                className="btn btn--primary btn--sm"
                onClick={onSubmitClick}
                disabled={draftMoves.length === 0 || pending}
              >
                {pending
                  ? attemptLabels.submitting
                  : attemptLabels.submitButton}
              </button>
            </div>
            {submitError ? (
              <p className="student-hw-attempt__error" role="alert">
                {submitError}
              </p>
            ) : null}

            {/*
             * "Show solution" escape hatch. Only appears after a Needs-work
             * review when the authored solution hasn't been pulled in yet.
             * Once the reveal lands, {@code block.solution} populates on the
             * next fetch and this button is swapped for the solution panel
             * underneath. A second reveal is idempotent server-side so a
             * double-click isn't destructive, but we gate with `revealing`
             * to avoid the stutter.
             */}
            {status === "REVIEWED_NEEDS_WORK" && !hasSolution ? (
              <div className="student-hw-attempt__reveal">
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  onClick={onShowSolutionClick}
                  disabled={revealing || pending}
                >
                  {revealing
                    ? attemptLabels.revealing
                    : attemptLabels.showSolution}
                </button>
                {revealError ? (
                  <p className="student-hw-attempt__error" role="alert">
                    {revealError}
                  </p>
                ) : null}
              </div>
            ) : null}
          </div>
        </div>
      ) : null}

      {interactive && !baseFen ? (
        <p className="student-hw-attempt__error" role="alert">
          {viewerLabels.invalidBlock}
        </p>
      ) : null}

      {/*
       * Revealed-solution viewer. Renders for any status where the
       * backend has un-stripped the authored solution — in practice
       * that's SOLUTION_REVEALED (live NEEDS_WORK attempt with a
       * non-null {@code solutionRevealedAt}). The main board above stays
       * interactive so the student can reproduce the line, then submit.
       * On a successful resubmit the backend flips the row back to
       * SUBMITTED, the solution gets stripped on next fetch, and this
       * panel disappears.
       */}
      {solutionBlock ? (
        <section
          className="student-hw-attempt__solution"
          aria-label={viewerLabels.solutionHeading}
        >
          <h5 className="student-hw-attempt__solution-heading">
            {viewerLabels.solutionHeading}
          </h5>
          <BoardCardView
            block={solutionBlock}
            labels={viewerLabels}
            canRevealSolution={false}
            idPrefix={`student-sol-${blockIndex}`}
          />
        </section>
      ) : null}
    </article>
  );
}
