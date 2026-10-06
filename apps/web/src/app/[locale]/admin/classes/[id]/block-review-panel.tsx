"use client";

import { useEffect, useMemo, useState, useTransition } from "react";
import { Chess } from "chess.js";
import {
  BoardCardView,
  type BoardCardBlock,
  type BoardCardLabels,
} from "@/components/board-card-view";
import {
  fetchAttemptHistoryAction,
  reviewBlockAttemptAction,
  type BlockAttemptView,
} from "./homework-actions";
import { BlockQaOverlay } from "./block-qa-overlay";

/**
 * Label bag for the admin block-review surface. Kept locale-agnostic the
 * same way {@code BoardCardLabels} is, so the shared surface stays
 * decoupled from next-intl's react layer.
 *
 * <p>Interpolation-dependent strings are exposed as functions because ICU
 * plural / variable messages must be resolved with their values at format
 * time — the same pattern used by {@link BoardCardLabels} and
 * {@code BlockAttemptLabels} on the student side.
 */
export type BlockReviewLabels = {
  sectionHeading: string;
  sectionEmpty: string;
  blockHeading: (index: number, title: string) => string;
  /** "Student A · Submitted 14 Apr 14:00". The caller formats the instant. */
  submittedMeta: (who: string, when: string) => string;
  /** Appended to the meta line when there's a review timestamp. */
  reviewedMeta: (when: string) => string;
  status: {
    SUBMITTED: string;
    REVIEWED_OK: string;
    REVIEWED_NEEDS_WORK: string;
    SOLUTION_REVEALED: string;
  };
  teacherNoteLabel: string;
  teacherNotePlaceholder: string;
  markOk: string;
  markNeedsWork: string;
  saving: string;
  /** Shown inline after a 409 — the live row moved under us. */
  staleNotice: string;
  /** History disclosure — collapsed / expanded toggle copy. */
  historyShow: (count: number) => string;
  historyHide: string;
  historyEmpty: string;
  historyLoading: string;
  /** Per-row meta on a historical attempt: "Submitted 14 Apr 14:00". */
  historyAttemptMeta: (when: string) => string;
  /** Tagged on a row that's been superseded by a later submission. */
  historyReplacedBadge: string;
  /** Tagged on the live (most-recent) attempt to distinguish from history. */
  historyLiveBadge: string;
  /** Move-list label inside a historical attempt row. */
  historyMovesLabel: string;
  /** Italicised copy shown when an attempt has zero moves (a degenerate
   *  case the backend rejects but defensively rendered). */
  historyNoMoves: string;
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
  | "SUBMITTED"
  | "REVIEWED_OK"
  | "REVIEWED_NEEDS_WORK"
  | "SOLUTION_REVEALED";

/**
 * Mirror of the student side: a live {@code REVIEWED_NEEDS_WORK} row
 * with a non-null {@code solutionRevealedAt} becomes the derived
 * "Solution revealed" display state. Teachers can still re-review it —
 * this is just a visual distinction so a teacher can tell at a glance
 * that the student already asked for the answer.
 */
function displayStatusFor(attempt: BlockAttemptView): DisplayStatus {
  if (
    attempt.status === "REVIEWED_NEEDS_WORK" &&
    attempt.solutionRevealedAt
  ) {
    return "SOLUTION_REVEALED";
  }
  return attempt.status;
}

/**
 * Compute the post-prefix FEN for a block: play the authored move prefix
 * on top of the block's starting FEN and return the resulting position.
 * Returns null if either the FEN or any move is malformed — in practice
 * the backend validates both on write, so this is defensive.
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
 * Per-attempt review card. Renders a read-only replay of the student's
 * moves (stepping only through the student's work — the authored prefix
 * is baked into the replay's starting FEN) and a tiny form with a
 * teacher-note textarea + Mark OK / Mark Needs-work buttons.
 *
 * <p>Callers (typically the {@link BlockReviewSection}) wire an
 * {@code onReviewed} callback so the parent can replace its optimistic
 * local row without a full detail refetch. The server action also kicks
 * off a {@code revalidatePath} for subsequent navigation.
 */
function AttemptReviewCard({
  classId,
  homeworkId,
  block,
  attempt,
  studentName,
  locale,
  viewerLabels,
  labels,
  formatInstant,
  onReviewed,
}: {
  classId: string;
  homeworkId: string;
  block: BoardCardBlock;
  attempt: BlockAttemptView;
  studentName: string;
  locale: string;
  viewerLabels: BoardCardLabels;
  labels: BlockReviewLabels;
  formatInstant: (iso: string) => string;
  onReviewed: (fresh: BlockAttemptView) => void;
}) {
  const status = displayStatusFor(attempt);
  const baseFen = useMemo(() => computeBaseFen(block), [block]);

  const [note, setNote] = useState<string>(attempt.teacherNote ?? "");
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  // History disclosure state — collapsed until the teacher clicks
  // "View past attempts". Lazy-fetched on first expand; subsequent
  // toggles reuse the cache.
  const [historyOpen, setHistoryOpen] = useState(false);
  const [history, setHistory] = useState<BlockAttemptView[] | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);

  // CAREFUL: don't include `historyLoading` in the dep array. Same
  // cleanup-mid-fetch race as the QA panels — `setHistoryLoading(true)`
  // would otherwise trigger a cleanup that flips `cancelled = true`
  // before the response lands. Gate keeps the flag to prevent
  // double-fetches; we just don't react to it changing.
  useEffect(() => {
    if (
      !historyOpen ||
      history !== null ||
      historyLoading ||
      historyError !== null
    ) {
      return;
    }
    let cancelled = false;
    setHistoryLoading(true);
    void fetchAttemptHistoryAction(
      classId,
      homeworkId,
      attempt.studentUserId,
      attempt.blockIndex,
    ).then((res) => {
      if (cancelled) return;
      setHistoryLoading(false);
      if (!res.ok) {
        setHistoryError(labels.errors[res.error]);
        return;
      }
      setHistory(res.history);
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [historyOpen, history, historyError, classId, homeworkId, attempt.studentUserId, attempt.blockIndex]);

  // After a fresh review lands the live row's `attempt.id` may change
  // (the student resubmitted) — invalidate the cached history so the
  // next expand re-fetches.
  useEffect(() => {
    setHistory(null);
    setHistoryError(null);
  }, [attempt.id]);

  // Synthetic block for the read-only replay. Same shape as the student
  // side — the stepper walks only the student's moves, starting from the
  // post-prefix position.
  const replayBlock = useMemo<BoardCardBlock | null>(() => {
    if (!baseFen) return null;
    return {
      id: `${block.id}-review-${attempt.id}`,
      title: block.title,
      startingFen: baseFen,
      moves: attempt.moves,
      orientation: block.orientation,
      // The teacher sees the authored solution via the preview section
      // above the review list, not inline per attempt — keeps the review
      // row compact.
      solution: null,
    };
  }, [attempt.id, attempt.moves, baseFen, block.id, block.orientation, block.title]);

  function submit(next: "REVIEWED_OK" | "REVIEWED_NEEDS_WORK") {
    if (pending) return;
    setError(null);
    const trimmed = note.trim();
    startTransition(async () => {
      const res = await reviewBlockAttemptAction(
        classId,
        homeworkId,
        attempt.id,
        next,
        trimmed === "" ? null : trimmed,
        locale,
      );
      if (!res.ok) {
        setError(
          res.error === "conflict"
            ? labels.staleNotice
            : labels.errors[res.error],
        );
        return;
      }
      onReviewed(res.attempt);
    });
  }

  return (
    <article className="hw-review-card">
      <header className="hw-review-card__head">
        <span className="hw-review-card__student">
          {labels.submittedMeta(studentName, formatInstant(attempt.createdAt))}
          {attempt.reviewedAt ? (
            <>
              {" · "}
              <span className="hw-review-card__reviewed">
                {labels.reviewedMeta(formatInstant(attempt.reviewedAt))}
              </span>
            </>
          ) : null}
        </span>
        <span
          className={
            "hw-review-card__pill hw-review-card__pill--" +
            status.toLowerCase()
          }
        >
          {labels.status[status]}
        </span>
      </header>

      {replayBlock ? (
        <BoardCardView
          block={replayBlock}
          labels={viewerLabels}
          canRevealSolution={false}
          idPrefix={`admin-review-${attempt.id}`}
        />
      ) : (
        <p className="hw-review-card__error" role="alert">
          {viewerLabels.invalidBlock}
        </p>
      )}

      <label className="hw-review-card__note">
        <span className="hw-review-card__note-label">
          {labels.teacherNoteLabel}
        </span>
        <textarea
          className="sched-field__input hw-review-card__note-input"
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder={labels.teacherNotePlaceholder}
          rows={2}
          disabled={pending}
        />
      </label>

      <div className="hw-review-card__actions">
        <button
          type="button"
          className={
            "btn btn--sm " +
            (status === "REVIEWED_OK" ? "btn--primary" : "btn--ghost")
          }
          onClick={() => submit("REVIEWED_OK")}
          disabled={pending}
          aria-pressed={status === "REVIEWED_OK"}
        >
          {pending ? labels.saving : labels.markOk}
        </button>
        <button
          type="button"
          className={
            "btn btn--sm " +
            (status === "REVIEWED_NEEDS_WORK" || status === "SOLUTION_REVEALED"
              ? "btn--primary"
              : "btn--ghost")
          }
          onClick={() => submit("REVIEWED_NEEDS_WORK")}
          disabled={pending}
          aria-pressed={
            status === "REVIEWED_NEEDS_WORK" || status === "SOLUTION_REVEALED"
          }
        >
          {pending ? labels.saving : labels.markNeedsWork}
        </button>
      </div>

      {error ? (
        <p className="hw-review-card__error" role="alert">
          {error}
        </p>
      ) : null}

      <section className="hw-review-card__history">
        <button
          type="button"
          className="hw-review-card__history-toggle"
          aria-expanded={historyOpen}
          onClick={() => setHistoryOpen((v) => !v)}
        >
          {historyOpen
            ? labels.historyHide
            : labels.historyShow(history?.length ?? 0)}
        </button>

        {historyOpen ? (
          <div className="hw-review-card__history-panel">
            {historyLoading ? (
              <p className="hw-review-card__history-loading">
                {labels.historyLoading}
              </p>
            ) : historyError ? (
              <p className="hw-review-card__error" role="alert">
                {historyError}
              </p>
            ) : history === null ? null : history.length <= 1 ? (
              <p className="hw-review-card__history-empty">
                {labels.historyEmpty}
              </p>
            ) : (
              <ol className="hw-review-card__history-list">
                {history.map((row) => {
                  const isLive = row.replacedById === null;
                  const rowStatus = displayStatusFor(row);
                  return (
                    <li
                      key={row.id}
                      className={
                        "hw-review-card__history-row" +
                        (isLive ? " hw-review-card__history-row--live" : "")
                      }
                    >
                      <header className="hw-review-card__history-head">
                        <span className="hw-review-card__history-when">
                          {labels.historyAttemptMeta(
                            formatInstant(row.createdAt),
                          )}
                        </span>
                        <span
                          className={
                            "hw-review-card__pill hw-review-card__pill--" +
                            rowStatus.toLowerCase()
                          }
                        >
                          {labels.status[rowStatus]}
                        </span>
                        {isLive ? (
                          <span className="hw-review-card__history-tag hw-review-card__history-tag--live">
                            {labels.historyLiveBadge}
                          </span>
                        ) : (
                          <span className="hw-review-card__history-tag hw-review-card__history-tag--replaced">
                            {labels.historyReplacedBadge}
                          </span>
                        )}
                      </header>

                      <div className="hw-review-card__history-moves">
                        <span className="hw-review-card__history-moves-label">
                          {labels.historyMovesLabel}
                        </span>
                        {row.moves.length === 0 ? (
                          <span className="hw-review-card__history-empty-moves">
                            {labels.historyNoMoves}
                          </span>
                        ) : (
                          <ul className="hw-review-card__history-uci">
                            {row.moves.map((m, i) => (
                              <li
                                key={`${row.id}-mv-${i}`}
                                className="hw-review-card__history-uci-chip"
                              >
                                {m}
                              </li>
                            ))}
                          </ul>
                        )}
                      </div>

                      {row.teacherNote ? (
                        <p className="hw-review-card__history-note">
                          <span className="hw-review-card__history-note-label">
                            {labels.teacherNoteLabel}:
                          </span>{" "}
                          {row.teacherNote}
                        </p>
                      ) : null}

                      {row.reviewedAt ? (
                        <p className="hw-review-card__history-reviewed">
                          {labels.reviewedMeta(formatInstant(row.reviewedAt))}
                        </p>
                      ) : null}
                    </li>
                  );
                })}
              </ol>
            )}
          </div>
        ) : null}
      </section>
    </article>
  );
}

/**
 * Block-grouped review section. For each authored BOARD block on the
 * homework, shows every student's live attempt (if any) as a stack of
 * {@link AttemptReviewCard}s. Blocks with zero attempts show a muted
 * "no attempts yet" line so the teacher can still see the block is
 * there.
 */
export function BlockReviewSection({
  classId,
  homeworkId,
  locale,
  blocks,
  attempts,
  studentNames,
  labels,
  viewerLabels,
  formatInstant,
  onAttemptReviewed,
  canEdit = true,
}: {
  classId: string;
  homeworkId: string;
  locale: string;
  /**
   * Parsed BOARD blocks as a dense array indexed by authored position.
   * Slots for unparseable / non-BOARD resources are {@code null} so the
   * index stays aligned with {@code attempt.blockIndex}; this section
   * simply skips those slots.
   */
  blocks: (BoardCardBlock | null)[];
  /** Current live attempts, as returned by the admin homework detail. */
  attempts: BlockAttemptView[];
  /** Lookup from student user id to display name. */
  studentNames: Map<string, string>;
  labels: BlockReviewLabels;
  viewerLabels: BoardCardLabels;
  formatInstant: (iso: string) => string;
  /**
   * Called when any attempt is reviewed. The parent typically splices
   * the fresh row into its own {@code attempts} array so pill + meta
   * updates land without waiting for server revalidation.
   */
  onAttemptReviewed: (fresh: BlockAttemptView) => void;
  /** Mirror of the parent's read/write gate; default true preserves
   *  existing behaviour for callers that haven't been updated yet. */
  canEdit?: boolean;
}) {
  // Group attempts by block index once so the map below stays O(n).
  const byBlock = useMemo(() => {
    const m = new Map<number, BlockAttemptView[]>();
    for (const a of attempts) {
      const list = m.get(a.blockIndex) ?? [];
      list.push(a);
      m.set(a.blockIndex, list);
    }
    // Stable order: oldest submission first, so the earliest submitter
    // ends up at the top of each block's review stack.
    for (const list of m.values()) {
      list.sort((x, y) => x.createdAt.localeCompare(y.createdAt));
    }
    return m;
  }, [attempts]);

  if (blocks.length === 0) return null;
  // A list of purely-null slots means every authored resource failed to
  // parse. Don't render an empty heading in that case.
  const anyBlock = blocks.some((b) => b !== null);
  if (!anyBlock) return null;

  return (
    <section className="hw-sheet__review">
      <h4 className="hw-sheet__review-heading">{labels.sectionHeading}</h4>
      {blocks.map((block, blockIndex) => {
        if (!block) return null;
        const list = byBlock.get(blockIndex) ?? [];
        const title = block.title?.trim() || viewerLabels.untitled;
        return (
          <section
            key={`review-block-${blockIndex}-${block.id}`}
            className="hw-review-block"
          >
            <h5 className="hw-review-block__heading">
              {labels.blockHeading(blockIndex + 1, title)}
            </h5>
            {list.length === 0 ? (
              <p className="hw-review-block__empty">{labels.sectionEmpty}</p>
            ) : (
              <div className="hw-review-block__list">
                {list.map((a) => (
                  <AttemptReviewCard
                    key={a.id}
                    classId={classId}
                    homeworkId={homeworkId}
                    block={block}
                    attempt={a}
                    studentName={
                      studentNames.get(a.studentUserId) ?? a.studentUserId
                    }
                    locale={locale}
                    viewerLabels={viewerLabels}
                    labels={labels}
                    formatInstant={formatInstant}
                    onReviewed={onAttemptReviewed}
                  />
                ))}
              </div>
            )}
            <BlockQaOverlay
              classId={classId}
              parentKind="HOMEWORK"
              parentId={homeworkId}
              blockIndex={blockIndex}
              locale={locale}
              canEdit={canEdit}
            />
          </section>
        );
      })}
    </section>
  );
}
