"use client";

import { useActionState, useMemo, useState } from "react";
import { useTranslations } from "next-intl";
import {
  parseBoardBlock,
  type BoardCardBlock,
  type BoardCardLabels,
} from "@/components/board-card-view";
import {
  updateMyHomeworkAction,
  type BlockAttemptView,
  type StudentActionState,
} from "../../actions";
import {
  BlockAttemptPanel,
  type BlockAttemptLabels,
} from "./block-attempt-panel";
import { StudentBlockQa } from "../../qa-block-panel";

type HomeworkResource = {
  id: string;
  position: number;
  kind: string;
  title: string | null;
  payload: Record<string, unknown>;
};

export type StudentHomeworkDetail = {
  id: string;
  classId: string;
  className: string | null;
  sessionId: string | null;
  title: string;
  instructions: string | null;
  dueAt: string | null;
  createdAt: string;
  status: "NOT_STARTED" | "DONE" | "SKIPPED" | null;
  studentNote: string | null;
  submittedAt: string | null;
  markedAt: string | null;
  resources: HomeworkResource[];
  /**
   * The caller's own live block-level attempts (one per block they've
   * submitted on so far). Absence means "Not attempted" for that block.
   * Added server-side in Task #33.
   */
  attempts: BlockAttemptView[];
};

type TFn = {
  (key: string): string;
  (key: string, values: Record<string, string | number | Date>): string;
};

const INITIAL: StudentActionState = {};

/**
 * Build the i18n-agnostic label bag the shared {@link BoardCardView} wants.
 * Lives here so the shared component stays locale-free.
 */
function buildViewerLabels(t: TFn): BoardCardLabels {
  return {
    untitled: t("viewer.untitled"),
    movesHeading: t("viewer.movesHeading"),
    // ICU plural + interpolation need their variables at format time;
    // next-intl emits FORMATTING_ERROR if we resolve these eagerly.
    movesCount: (count: number) => t("viewer.movesCount", { count }),
    movesEmpty: t("viewer.movesEmpty"),
    start: t("viewer.start"),
    prev: t("viewer.prev"),
    next: t("viewer.next"),
    end: t("viewer.end"),
    plyLabel: (ply: number, total: number) =>
      t("viewer.plyLabel", { ply, total }),
    sideToMove: t("viewer.sideToMove"),
    sideWhite: t("viewer.sideWhite"),
    sideBlack: t("viewer.sideBlack"),
    // Students don't see the solution, but the labels are required on the
    // shape. Any strings work — the disclosure never renders for them.
    showSolution: t("viewer.showSolution"),
    hideSolution: t("viewer.hideSolution"),
    solutionHeading: t("viewer.solutionHeading"),
    solutionEmpty: t("viewer.solutionEmpty"),
    invalidBlock: t("viewer.invalidBlock"),
  };
}

/**
 * Matching label bag for the interactive per-block panel. Mirrors the
 * layout of {@link buildViewerLabels} so the student shell's i18n story
 * stays "one component -> one label builder".
 */
function buildAttemptLabels(t: TFn): BlockAttemptLabels {
  return {
    ticker: {
      NOT_ATTEMPTED: t("attempt.statusNotAttempted"),
      SUBMITTED: t("attempt.statusSubmitted"),
      REVIEWED_OK: t("attempt.statusReviewedOk"),
      REVIEWED_NEEDS_WORK: t("attempt.statusNeedsWork"),
      SOLUTION_REVEALED: t("attempt.statusSolutionRevealed"),
    },
    yourMovesHeading: t("attempt.yourMovesHeading"),
    playInstruction: t("attempt.playInstruction"),
    retryInstruction: t("attempt.retryInstruction"),
    solutionRevealedInstruction: t("attempt.solutionRevealedInstruction"),
    submitButton: t("attempt.submitButton"),
    submitting: t("attempt.submitting"),
    undoLast: t("attempt.undoLast"),
    resetMoves: t("attempt.resetMoves"),
    noMovesYet: t("attempt.noMovesYet"),
    showSolution: t("attempt.showSolution"),
    revealing: t("attempt.revealing"),
    teacherNoteHeading: t("attempt.teacherNoteHeading"),
    // Same reason as the viewer labels — next-intl requires the `when`
    // interpolation variable at format time.
    lastSubmittedAt: (when: string) => t("attempt.lastSubmittedAt", { when }),
    lastReviewedAt: (when: string) => t("attempt.lastReviewedAt", { when }),
    illegalMoveNotice: t("attempt.illegalMoveNotice"),
    errors: {
      invalid: t("error.invalid"),
      unauthorised: t("error.unauthorised"),
      forbidden: t("error.forbidden"),
      conflict: t("error.conflict"),
      notfound: t("error.notfound"),
      network: t("error.network"),
      unknown: t("error.unknown"),
    },
  };
}

/**
 * Pair each BOARD resource with the caller's matching live attempt (if any).
 * Backend orders resources by position; we use that position as the block
 * index both for matching and for POSTing. Non-BOARD resources are skipped
 * so future block kinds (quiz, PGN trainer, etc.) don't break the surface
 * until they get their own panel.
 */
function pairBlocksWithAttempts(
  resources: HomeworkResource[],
  attempts: BlockAttemptView[],
): Array<{
  block: BoardCardBlock;
  blockIndex: number;
  attempt: BlockAttemptView | null;
}> {
  const out: Array<{
    block: BoardCardBlock;
    blockIndex: number;
    attempt: BlockAttemptView | null;
  }> = [];
  for (const r of resources) {
    const block = parseBoardBlock(r);
    if (!block) continue;
    const attempt =
      attempts.find((a) => a.blockIndex === r.position) ?? null;
    out.push({ block, blockIndex: r.position, attempt });
  }
  return out;
}

const DISPLAY_TZ = "Asia/Tashkent";

function formatInstant(iso: string, locale: string): string {
  const d = new Date(iso);
  const fmt = new Intl.DateTimeFormat(locale, {
    timeZone: DISPLAY_TZ,
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
  return fmt.format(d);
}

/**
 * Client-only detail panels for a single homework from the student's point
 * of view. Receives the pre-fetched detail from the server page and renders
 * the interactive per-block panels plus the mark-as-done / mark-as-skipped
 * / clear buttons. Revalidation happens server-side inside each action.
 */
export function StudentHomeworkDetailPanels({
  detail,
  locale,
}: {
  detail: StudentHomeworkDetail;
  locale: string;
}) {
  const t = useTranslations("student");
  const viewerLabels = buildViewerLabels(t);
  const attemptLabels = buildAttemptLabels(t);

  // Local mirror of the server's attempts list so a successful submission
  // can rebuild the UI without waiting for revalidatePath to round-trip.
  // Keyed by blockIndex; a fresh attempt supersedes any previous one in
  // the same slot (matches the backend's live-row semantics).
  const [liveAttempts, setLiveAttempts] = useState<BlockAttemptView[]>(
    detail.attempts ?? [],
  );

  const pairs = useMemo(
    () => pairBlocksWithAttempts(detail.resources, liveAttempts),
    [detail.resources, liveAttempts],
  );

  const [state, formAction, pending] = useActionState(
    updateMyHomeworkAction,
    INITIAL,
  );

  const status = detail.status ?? "NOT_STARTED";

  function onAttemptSubmitted(fresh: BlockAttemptView) {
    setLiveAttempts((prev) => {
      // Replace any existing row for the same block, or append if new.
      const next = prev.filter((a) => a.blockIndex !== fresh.blockIndex);
      next.push(fresh);
      return next;
    });
  }

  /**
   * Reveal handler: same shape as {@link onAttemptSubmitted}. The reveal
   * endpoint returns the same live row with {@code solutionRevealedAt}
   * stamped, so we just re-use the replace-in-place semantics. The
   * authored {@code solution.uci} itself arrives on the router.refresh
   * the panel kicks inside its transition.
   */
  function onAttemptRevealed(fresh: BlockAttemptView) {
    setLiveAttempts((prev) => {
      const next = prev.filter((a) => a.blockIndex !== fresh.blockIndex);
      next.push(fresh);
      return next;
    });
  }

  return (
    <section className="student-hw-detail">
      <div className="student-hw-detail__status">
        <div className="student-hw-detail__status-line">
          <span className="student-hw-detail__status-label">
            {t("detail.yourStatus")}:
          </span>
          <span
            className={
              "student-hw-detail__status-pill student-hw-detail__status-pill--" +
              status.toLowerCase()
            }
          >
            {t(`status.${status}`)}
          </span>
          {detail.submittedAt ? (
            <span className="student-hw-detail__submitted">
              {t("detail.submittedAt", {
                when: formatInstant(detail.submittedAt, locale),
              })}
            </span>
          ) : null}
        </div>

        <form action={formAction} className="student-hw-detail__actions">
          <input type="hidden" name="homeworkId" value={detail.id} />
          <input type="hidden" name="locale" value={locale} />
          <button
            type="submit"
            name="status"
            value="DONE"
            className={
              "btn btn--sm " +
              (status === "DONE" ? "btn--primary" : "btn--ghost")
            }
            disabled={pending}
            aria-pressed={status === "DONE"}
          >
            {t("detail.markDone")}
          </button>
          <button
            type="submit"
            name="status"
            value="SKIPPED"
            className={
              "btn btn--sm " +
              (status === "SKIPPED" ? "btn--primary" : "btn--ghost")
            }
            disabled={pending}
            aria-pressed={status === "SKIPPED"}
          >
            {t("detail.markSkipped")}
          </button>
          {status !== "NOT_STARTED" ? (
            <button
              type="submit"
              name="status"
              value=""
              className="btn btn--ghost btn--sm"
              disabled={pending}
            >
              {t("detail.clear")}
            </button>
          ) : null}
          {state.error ? (
            <span className="sched-form__error" role="alert">
              {t(`error.${state.error}`)}
            </span>
          ) : null}
        </form>
      </div>

      {detail.instructions ? (
        <div className="student-hw-detail__instructions">
          <h3 className="student-hw-detail__heading">
            {t("detail.instructions")}
          </h3>
          <p>{detail.instructions}</p>
        </div>
      ) : null}

      {pairs.length > 0 ? (
        <div className="student-hw-detail__boards">
          <h3 className="student-hw-detail__heading">
            {t("detail.boardsHeading")}
          </h3>
          <div className="student-hw-detail__boards-list">
            {pairs.map(({ block, blockIndex, attempt }) => (
              <div
                key={`block-${blockIndex}-${block.id}`}
                className="student-hw-detail__block"
              >
                <BlockAttemptPanel
                  block={block}
                  blockIndex={blockIndex}
                  homeworkId={detail.id}
                  attempt={attempt}
                  viewerLabels={viewerLabels}
                  attemptLabels={attemptLabels}
                  locale={locale}
                  onSubmitted={onAttemptSubmitted}
                  onRevealed={onAttemptRevealed}
                  formatInstant={(iso) => formatInstant(iso, locale)}
                />
                <StudentBlockQa
                  parentKind="HOMEWORK"
                  parentId={detail.id}
                  blockIndex={blockIndex}
                  locale={locale}
                />
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </section>
  );
}
