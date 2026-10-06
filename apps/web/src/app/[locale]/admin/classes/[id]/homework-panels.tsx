"use client";

import { useActionState, useEffect, useMemo, useState, useTransition } from "react";
import { useRouter, useSearchParams, usePathname } from "next/navigation";
import type { Route } from "next";
import { useTranslations } from "next-intl";
import {
  archiveHomeworkAction,
  createHomeworkAction,
  readHomeworkDetailAction,
  updateHomeworkAction,
  updateHomeworkSubmissionAction,
  type BlockAttemptView,
  type HomeworkActionState,
  type HomeworkDetail,
  type HomeworkSubmissionStatus,
  type HomeworkSummary,
} from "./homework-actions";
import { bucketByTimestamp, isOnDay } from "@/lib/class-detail-buckets";
import { BucketedList } from "./bucketed-list";
import { DayFilterBanner } from "./day-filter-banner";
import {
  BoardBlocksEditor,
  blocksFromResources,
  resourcesFromBlocks,
  type BoardBlock,
  type BoardBlocksEditorLabels,
} from "./board-blocks-editor";
import {
  BoardCardView,
  blocksFromResourcesForView,
  parseBoardBlock,
  type BoardCardBlock,
  type BoardCardLabels,
} from "@/components/board-card-view";
import {
  BlockReviewSection,
  type BlockReviewLabels,
} from "./block-review-panel";
import type { ScheduleSession } from "./schedule-panels";

type TFn = {
  (key: string): string;
  (key: string, values: Record<string, string | number | Date>): string;
};

const INITIAL: HomeworkActionState = {};

const DISPLAY_TZ = "Asia/Tashkent";

const SUBMISSION_STATUSES: HomeworkSubmissionStatus[] = ["DONE", "SKIPPED"];

/**
 * Strict YYYY-MM-DD match. A crafted URL like `?day=yesterday` or
 * `?day=2026-5-3` should fall back silently rather than blowing up the
 * filter pipeline — matches the same guard the Phase 2b calendar strip
 * uses when reading {@code ?week} / {@code ?day}.
 */
function isValidYmd(s: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(s);
}

/**
 * Admin / teacher-facing Homework panel. Sits below the Schedule section on
 * the class detail page and handles the full surface:
 *
 * <ul>
 *   <li>Active / archived tabs (the "archived" view is opt-in).</li>
 *   <li>Inline "New homework" form with optional session anchor and due date.</li>
 *   <li>Per-row "Edit" dialog (actually a toggled inline form) that covers
 *       PATCH including clear-dueAt / clear-session.</li>
 *   <li>"Archive" with a native confirm prompt to avoid a dependency on a
 *       dialog primitive we don't yet have.</li>
 *   <li>Expandable submission sheet per row with DONE/SKIPPED/clear chips,
 *       matching the attendance UX.</li>
 * </ul>
 *
 * <p>When {@code canEdit} is false (unassigned teacher reading the page)
 * the whole panel still renders read-only — the backend enforces the real
 * authorisation so the flags here are purely cosmetic.
 */
export function HomeworkPanels({
  classId,
  locale,
  active,
  archived,
  sessions,
  canEdit,
}: {
  classId: string;
  locale: string;
  /** Active (non-archived) homework, already sorted newest-first by API. */
  active: HomeworkSummary[];
  /** Archived homework, same sort. Empty unless the backend returned any. */
  archived: HomeworkSummary[];
  /** Upcoming sessions available as optional anchors on the new form. */
  sessions: ScheduleSession[];
  canEdit: boolean;
}) {
  const t = useTranslations("admin.classes.homework");
  const tBuckets = useTranslations("admin.classes.buckets");
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [showArchived, setShowArchived] = useState(false);
  const [showAdd, setShowAdd] = useState(false);
  const [query, setQuery] = useState("");

  // Read the Phase 2b calendar strip's day-pin param. A crafted URL falls
  // back silently (see {@link isValidYmd}). The day filter is only meaningful
  // on the active tab — the archived review surface is already scoped down to
  // rows the user chose to review, so we ignore {@code ?day} there.
  const rawDay = searchParams.get("day");
  const dayFilter =
    !showArchived && rawDay != null && isValidYmd(rawDay) ? rawDay : null;

  const archivedCount = archived.length;
  const rows = showArchived ? archived : active;

  // Lowercase match on title + instructions; dense rows mean typos shouldn't
  // hide results, so fall back to raw substring rather than a fuzzy matcher.
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((h) => {
      const title = h.title.toLowerCase();
      const inst = (h.instructions ?? "").toLowerCase();
      return title.includes(q) || inst.includes(q);
    });
  }, [rows, query]);

  // Day-filter layered on top of the query filter so search + pinned-day
  // compose. Items without a due date never match a specific day (homework
  // with a null {@code dueAt} can't be scoped to a calendar cell).
  const dayFiltered = useMemo(() => {
    if (!dayFilter) return filtered;
    return filtered.filter((h) => isOnDay(h.dueAt ?? null, dayFilter));
  }, [filtered, dayFilter]);

  // Bucket active rows by due date. Archived stays a flat list — the
  // "archived" view is review-only and newest-first is already the right
  // order there. When the day filter is active the buckets reflect only
  // rows that landed on the pinned day (typically one bucket).
  const buckets = useMemo(
    () =>
      showArchived
        ? null
        : bucketByTimestamp(dayFiltered, (h) => h.dueAt ?? null),
    [dayFiltered, showArchived],
  );

  // Strip {@code ?day} from the URL while preserving any other params
  // (e.g. the strip's {@code ?week}, a future {@code ?tab}, etc.).
  // {@link Route} cast matches the same pattern used by calendar-strip's
  // {@code pushParams} helper — Next's {@code typedRoutes: true} wants a
  // known-at-compile-time shape, and our URL is runtime-constructed.
  function onClearDay() {
    const params = new URLSearchParams(searchParams.toString());
    params.delete("day");
    const qs = params.toString();
    const url = (qs ? `${pathname}?${qs}` : pathname) as Route;
    router.replace(url, { scroll: false });
  }

  return (
    <article className="sched-card hw-card">
      <header className="sched-card__header">
        <h2 className="sched-card__title">{t("title")}</h2>
        <div className="hw-header__actions">
          {archivedCount > 0 ? (
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => setShowArchived((v) => !v)}
              aria-pressed={showArchived}
            >
              {showArchived
                ? t("hideArchived")
                : t("showArchived", { count: archivedCount })}
            </button>
          ) : null}
          {canEdit ? (
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => setShowAdd((v) => !v)}
            >
              {showAdd ? t("hideAddForm") : t("addCta")}
            </button>
          ) : null}
        </div>
      </header>

      {canEdit && showAdd ? (
        <NewHomeworkForm
          classId={classId}
          locale={locale}
          sessions={sessions}
          t={t}
          onCreated={() => setShowAdd(false)}
        />
      ) : null}

      {dayFilter ? (
        <DayFilterBanner
          day={dayFilter}
          count={dayFiltered.length}
          onClear={onClearDay}
          locale={locale}
        />
      ) : null}

      {rows.length > 0 ? (
        <div className="hw-toolbar">
          <label className="hw-search">
            <span className="sr-only">{t("searchLabel")}</span>
            <input
              type="search"
              className="hw-search__input"
              placeholder={t("searchPlaceholder")}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              aria-label={t("searchLabel")}
            />
          </label>
        </div>
      ) : null}

      {rows.length === 0 ? (
        <p className="sched-empty">
          {showArchived ? t("emptyArchived") : t("empty")}
        </p>
      ) : filtered.length === 0 ? (
        <p className="sched-empty">{t("emptySearch")}</p>
      ) : dayFilter && dayFiltered.length === 0 ? (
        <p className="sched-empty">{t("emptyDay")}</p>
      ) : showArchived ? (
        <ul className="hw-list">
          {filtered.map((h) => (
            <HomeworkRow
              key={h.id}
              classId={classId}
              locale={locale}
              homework={h}
              sessions={sessions}
              canEdit={canEdit}
              t={t}
            />
          ))}
        </ul>
      ) : (
        <BucketedList
          groups={buckets!}
          tBuckets={tBuckets}
          renderRow={(h) => (
            <HomeworkRow
              key={h.id}
              classId={classId}
              locale={locale}
              homework={h}
              sessions={sessions}
              canEdit={canEdit}
              t={t}
            />
          )}
        />
      )}
    </article>
  );
}


// ------------------------------------------------------------------
// Board-blocks editor integration
// ------------------------------------------------------------------

/**
 * Pull the flat key-list the {@link BoardBlocksEditor} wants out of the
 * existing next-intl translator. Keeps the editor itself i18n-agnostic so
 * it's easy to drop into other forms later.
 */
function boardEditorLabels(t: TFn): BoardBlocksEditorLabels {
  return {
    heading: t("boards.heading"),
    hint: t("boards.hint"),
    addBoard: t("boards.addBoard"),
    removeBoard: t("boards.removeBoard"),
    moveUp: t("boards.moveUp"),
    moveDown: t("boards.moveDown"),
    blockTitleLabel: t("boards.blockTitleLabel"),
    blockTitlePlaceholder: t("boards.blockTitlePlaceholder"),
    startingFenLabel: t("boards.startingFenLabel"),
    resetToStart: t("boards.resetToStart"),
    useCurrentAsStart: t("boards.useCurrentAsStart"),
    orientationLabel: t("boards.orientationLabel"),
    orientationWhite: t("boards.orientationWhite"),
    orientationBlack: t("boards.orientationBlack"),
    movesHeading: t("boards.movesHeading"),
    movesCount: t("boards.movesCount"),
    movesEmpty: t("boards.movesEmpty"),
    pastePgnLabel: t("boards.pastePgnLabel"),
    pastePgnPlaceholder: t("boards.pastePgnPlaceholder"),
    pastePgnApply: t("boards.pastePgnApply"),
    pastePgnReplace: t("boards.pastePgnReplace"),
    pastePgnAppend: t("boards.pastePgnAppend"),
    undoLastMove: t("boards.undoLastMove"),
    clearAllMoves: t("boards.clearAllMoves"),
    solutionHeading: t("boards.solutionHeading"),
    solutionHint: t("boards.solutionHint"),
    enableSolution: t("boards.enableSolution"),
    disableSolution: t("boards.disableSolution"),
    solutionCount: t("boards.solutionCount"),
    solutionEmpty: t("boards.solutionEmpty"),
    solutionUndo: t("boards.solutionUndo"),
    solutionClear: t("boards.solutionClear"),
    sideToMove: t("boards.sideToMove"),
    sideWhite: t("boards.sideWhite"),
    sideBlack: t("boards.sideBlack"),
    invalidFen: t("boards.invalidFen"),
    invalidPgn: t("boards.invalidPgn"),
    noBlocks: t("boards.noBlocks"),
    modeSetup: t("boards.modeSetup"),
    modeMoves: t("boards.modeMoves"),
    setupHint: t("boards.setupHint"),
    setupFillStart: t("boards.setupFillStart"),
    setupEmpty: t("boards.setupEmpty"),
    setupPaletteHint: t("boards.setupPaletteHint"),
    setupRemoveHint: t("boards.setupRemoveHint"),
    setupCastling: t("boards.setupCastling"),
    setupCastleK: t("boards.setupCastleK"),
    setupCastleQ: t("boards.setupCastleQ"),
    setupCastlek: t("boards.setupCastlek"),
    setupCastleq: t("boards.setupCastleq"),
    setupSideToMove: t("boards.setupSideToMove"),
    setupAdvancedShow: t("boards.setupAdvancedShow"),
    setupAdvancedHide: t("boards.setupAdvancedHide"),
    setupIllegalPositionNotice: t("boards.setupIllegalPositionNotice"),
    switchToSetupWarning: t("boards.switchToSetupWarning"),
  };
}

/**
 * Viewer labels — shared by the admin preview (this file) and the student
 * shell. Kept here rather than baked into {@link BoardCardView} so the same
 * component can be mounted with different key trees without coupling to
 * next-intl's react surface.
 */
function boardViewerLabels(t: TFn): BoardCardLabels {
  return {
    untitled: t("viewer.untitled"),
    movesHeading: t("viewer.movesHeading"),
    // ICU plural + interpolation need variables at format time; next-intl
    // throws FORMATTING_ERROR if we resolve these eagerly. Keep parity
    // with the student shell's {@code buildViewerLabels}.
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
    showSolution: t("viewer.showSolution"),
    hideSolution: t("viewer.hideSolution"),
    solutionHeading: t("viewer.solutionHeading"),
    solutionEmpty: t("viewer.solutionEmpty"),
    invalidBlock: t("viewer.invalidBlock"),
  };
}

/**
 * Label bag for the per-block review surface. Mirrors the label-bag pattern
 * used by {@link boardViewerLabels} and {@code buildAttemptLabels} on the
 * student side: interpolation-dependent keys are exposed as functions so
 * next-intl can resolve ICU variables at format time (resolving them
 * eagerly throws {@code FORMATTING_ERROR}).
 */
function blockReviewLabels(t: TFn): BlockReviewLabels {
  return {
    sectionHeading: t("sheet.review.sectionHeading"),
    sectionEmpty: t("sheet.review.sectionEmpty"),
    blockHeading: (index: number, title: string) =>
      t("sheet.review.blockHeading", { index, title }),
    submittedMeta: (who: string, when: string) =>
      t("sheet.review.submittedMeta", { who, when }),
    reviewedMeta: (when: string) =>
      t("sheet.review.reviewedMeta", { when }),
    status: {
      SUBMITTED: t("sheet.review.status.SUBMITTED"),
      REVIEWED_OK: t("sheet.review.status.REVIEWED_OK"),
      REVIEWED_NEEDS_WORK: t("sheet.review.status.REVIEWED_NEEDS_WORK"),
      SOLUTION_REVEALED: t("sheet.review.status.SOLUTION_REVEALED"),
    },
    teacherNoteLabel: t("sheet.review.teacherNoteLabel"),
    teacherNotePlaceholder: t("sheet.review.teacherNotePlaceholder"),
    markOk: t("sheet.review.markOk"),
    markNeedsWork: t("sheet.review.markNeedsWork"),
    saving: t("sheet.review.saving"),
    staleNotice: t("sheet.review.staleNotice"),
    historyShow: (count: number) =>
      t("sheet.review.historyShow", { count }),
    historyHide: t("sheet.review.historyHide"),
    historyEmpty: t("sheet.review.historyEmpty"),
    historyLoading: t("sheet.review.historyLoading"),
    historyAttemptMeta: (when: string) =>
      t("sheet.review.historyAttemptMeta", { when }),
    historyReplacedBadge: t("sheet.review.historyReplacedBadge"),
    historyLiveBadge: t("sheet.review.historyLiveBadge"),
    historyMovesLabel: t("sheet.review.historyMovesLabel"),
    historyNoMoves: t("sheet.review.historyNoMoves"),
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

// ------------------------------------------------------------------
// New homework form
// ------------------------------------------------------------------

function NewHomeworkForm({
  classId,
  locale,
  sessions,
  t,
  onCreated,
}: {
  classId: string;
  locale: string;
  sessions: ScheduleSession[];
  t: TFn;
  onCreated: () => void;
}) {
  const [state, formAction, pending] = useActionState(
    createHomeworkAction,
    INITIAL,
  );
  const [blocks, setBlocks] = useState<BoardBlock[]>([]);

  // Close the form automatically when the last submit succeeded. useActionState
  // clears `error` on the next OK submit, so absence of error after a submit
  // attempt is our success signal.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  if (!pending && state && !state.error && formWasSubmitted.current) {
    formWasSubmitted.current = false;
    setBlocks([]);
    queueMicrotask(onCreated);
  }

  const labels = boardEditorLabels(t);
  // Only send `resources` when the author added at least one block. An empty
  // list would just mean "no content" and the server ignores it.
  const resourcesJson =
    blocks.length > 0 ? JSON.stringify(resourcesFromBlocks(blocks)) : "";

  return (
    <form
      className="sched-form hw-form"
      action={(fd) => {
        formWasSubmitted.current = true;
        formAction(fd);
      }}
    >
      <input type="hidden" name="classId" value={classId} />
      <input type="hidden" name="locale" value={locale} />
      {blocks.length > 0 ? (
        <input type="hidden" name="resources" value={resourcesJson} />
      ) : null}

      <label className="sched-field">
        <span className="sched-field__label">{t("form.titleLabel")}</span>
        <input
          type="text"
          name="title"
          maxLength={200}
          required
          placeholder={t("form.titlePlaceholder")}
        />
      </label>

      <label className="sched-field">
        <span className="sched-field__label">{t("form.instructionsLabel")}</span>
        <textarea
          name="instructions"
          rows={3}
          maxLength={4000}
          placeholder={t("form.instructionsPlaceholder")}
        />
      </label>

      <div className="sched-form__grid">
        <label className="sched-field">
          <span className="sched-field__label">{t("form.dueDateLabel")}</span>
          <input type="date" name="dueDate" />
        </label>
        <label className="sched-field">
          <span className="sched-field__label">{t("form.dueTimeLabel")}</span>
          <input type="time" name="dueTime" defaultValue="18:00" />
        </label>
        <label className="sched-field">
          <span className="sched-field__label">{t("form.sessionLabel")}</span>
          <SessionSelect sessions={sessions} locale={locale} t={t} name="sessionId" />
        </label>
      </div>

      <BoardBlocksEditor
        blocks={blocks}
        onChange={setBlocks}
        labels={labels}
      />

      <div className="sched-form__footer">
        <button className="btn btn--primary btn--sm" type="submit" disabled={pending}>
          {pending ? t("form.adding") : t("form.addCta")}
        </button>
        {state.error ? (
          <p className="sched-form__error" role="alert">
            {t(`error.${state.error}`)}
          </p>
        ) : null}
      </div>
    </form>
  );
}

// Module-level ref so the submit-closed-on-success effect doesn't require a
// client-only useRef — useActionState can resolve between render passes.
const formWasSubmitted = { current: false };

// ------------------------------------------------------------------
// Single row with collapse controls
// ------------------------------------------------------------------

function HomeworkRow({
  classId,
  locale,
  homework,
  sessions,
  canEdit,
  t,
}: {
  classId: string;
  locale: string;
  homework: HomeworkSummary;
  sessions: ScheduleSession[];
  canEdit: boolean;
  t: TFn;
}) {
  const [editing, setEditing] = useState(false);
  const [sheetOpen, setSheetOpen] = useState(false);

  // Backend strips null fields from JSON responses
  // (`spring.jackson.default-property-inclusion: non_null`), so a non-archived
  // homework arrives with `archivedAt` absent (undefined) rather than an
  // explicit null. Use loose inequality so both shapes read as "not archived".
  const isArchived = homework.archivedAt != null;
  const dueLabel = homework.dueAt
    ? formatInstant(homework.dueAt, locale)
    : null;
  const createdLabel = formatInstant(homework.createdAt, locale);
  // Session anchor as a compact pill. Prefer the denormalised
  // `sessionStartsAt` (present whenever the session still exists, including
  // past sessions that the upcoming-only `sessions` prop wouldn't carry). If
  // the summary didn't ship it (older clients / cache), fall back to the
  // sessions prop lookup.
  const sessionStartIso =
    homework.sessionStartsAt ??
    (homework.sessionId
      ? (sessions.find((s) => s.id === homework.sessionId)?.startsAt ?? null)
      : null);
  const sessionLabel = sessionStartIso
    ? formatInstant(sessionStartIso, locale)
    : null;
  // Instructions preview on the row — truncated to keep the row compact.
  // Full text stays available inside the expanded submission sheet.
  const instructionsPreview = truncatePreview(homework.instructions, 140);

  return (
    <li className="hw-row">
      <div className="hw-row__head">
        <div className="hw-row__main">
          <div className="hw-row__title">
            {homework.title}
            {isArchived ? (
              <span className="pill pill--expired hw-archived-pill">
                {t("row.archivedTag")}
              </span>
            ) : null}
          </div>
          <div className="hw-row__meta">
            {sessionLabel ? (
              <span className="hw-meta-item hw-meta-item--session">
                {t("row.sessionOn", { when: sessionLabel })}
              </span>
            ) : null}
            {dueLabel ? (
              <span className="hw-meta-item hw-meta-item--due">
                {t("row.dueOn", { when: dueLabel })}
              </span>
            ) : (
              <span className="hw-meta-item hw-meta-item--nodue">
                {t("row.noDue")}
              </span>
            )}
            <span className="hw-meta-item">
              {t("row.created", { when: createdLabel })}
            </span>
            <Tally homework={homework} t={t} />
          </div>
          {instructionsPreview ? (
            <p className="hw-row__instructions" title={homework.instructions ?? undefined}>
              {instructionsPreview}
            </p>
          ) : null}
        </div>
        <div className="hw-row__actions">
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            onClick={() => setSheetOpen((v) => !v)}
            aria-expanded={sheetOpen}
          >
            {sheetOpen ? t("row.collapseSheet") : t("row.expandSheet")}
          </button>
          {canEdit && !isArchived ? (
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => setEditing((v) => !v)}
              aria-pressed={editing}
            >
              {editing ? t("row.cancelEdit") : t("row.edit")}
            </button>
          ) : null}
          {canEdit && !isArchived ? (
            <ArchiveButton
              classId={classId}
              homeworkId={homework.id}
              locale={locale}
              t={t}
            />
          ) : null}
        </div>
      </div>

      {editing && canEdit && !isArchived ? (
        <EditHomeworkForm
          classId={classId}
          locale={locale}
          homework={homework}
          sessions={sessions}
          t={t}
          onDone={() => setEditing(false)}
        />
      ) : null}

      {sheetOpen ? (
        <SubmissionSheet
          classId={classId}
          homeworkId={homework.id}
          locale={locale}
          canEdit={canEdit && !isArchived}
          t={t}
        />
      ) : null}
    </li>
  );
}

function Tally({
  homework,
  t,
}: {
  homework: HomeworkSummary;
  t: TFn;
}) {
  const unmarked = Math.max(
    0,
    homework.rosterSize - homework.doneCount - homework.skippedCount,
  );
  return (
    <span className="hw-tally" aria-label={t("row.tallyAria")}>
      <span className="hw-tally__item hw-tally__item--done">
        {t("row.tallyDone", { count: homework.doneCount })}
      </span>
      <span className="hw-tally__item hw-tally__item--skipped">
        {t("row.tallySkipped", { count: homework.skippedCount })}
      </span>
      <span className="hw-tally__item hw-tally__item--unmarked">
        {t("row.tallyUnmarked", { count: unmarked })}
      </span>
    </span>
  );
}

// ------------------------------------------------------------------
// Edit form (inline, toggled)
// ------------------------------------------------------------------

function EditHomeworkForm({
  classId,
  locale,
  homework,
  sessions,
  t,
  onDone,
}: {
  classId: string;
  locale: string;
  homework: HomeworkSummary;
  sessions: ScheduleSession[];
  t: TFn;
  onDone: () => void;
}) {
  const [state, formAction, pending] = useActionState(
    updateHomeworkAction,
    INITIAL,
  );
  const [blocks, setBlocks] = useState<BoardBlock[]>([]);
  const [blocksTouched, setBlocksTouched] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailLoaded, setDetailLoaded] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  // Lazy-fetch the detail on first render so we can hydrate existing blocks.
  // Skipping this for fresh homework (no resources yet) would require a hint
  // on the summary — cheaper to just fetch. Wrapped in `useEffect` so the
  // setState calls don't run during render (React 19 strict-mode bans
  // updating state mid-render — the action's .then() chain would otherwise
  // schedule router updates while EditHomeworkForm is still rendering).
  //
  // CAREFUL: don't include `detailLoading` in the dep array.
  // `setDetailLoading(true)` would otherwise trigger a cleanup that flips
  // `cancelled = true` mid-fetch, and the .then bail leaves the form stuck
  // on the "Loading…" indicator. The gate keeps the flag so we don't
  // double-fetch on a fast dep change; we just don't react to it changing.
  useEffect(() => {
    if (detailLoaded || detailLoading || detailError !== null) return;
    let cancelled = false;
    setDetailLoading(true);
    void readHomeworkDetailAction(classId, homework.id).then((res) => {
      if (cancelled) return;
      setDetailLoading(false);
      if (!res.ok) {
        setDetailError(t(`error.${res.error}`));
        setDetailLoaded(true);
        return;
      }
      setBlocks(blocksFromResources(res.detail.resources ?? []));
      setDetailLoaded(true);
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classId, homework.id, detailLoaded, detailError, t]);

  const due = homework.dueAt ? splitInstant(homework.dueAt) : null;

  // eslint-disable-next-line react-hooks/exhaustive-deps
  if (!pending && state && !state.error && editWasSubmitted.current) {
    editWasSubmitted.current = false;
    queueMicrotask(onDone);
  }

  const labels = boardEditorLabels(t);
  // Only include `resources` in the POST body when the author actually
  // touched the editor. That keeps the replace-on-write backend semantics
  // intact: touching nothing means "leave blocks alone".
  const resourcesJson = blocksTouched
    ? JSON.stringify(resourcesFromBlocks(blocks))
    : null;

  return (
    <form
      className="sched-form hw-form hw-form--edit"
      action={(fd) => {
        editWasSubmitted.current = true;
        formAction(fd);
      }}
    >
      <input type="hidden" name="classId" value={classId} />
      <input type="hidden" name="homeworkId" value={homework.id} />
      <input type="hidden" name="locale" value={locale} />
      {resourcesJson !== null ? (
        <input type="hidden" name="resources" value={resourcesJson} />
      ) : null}

      <label className="sched-field">
        <span className="sched-field__label">{t("form.titleLabel")}</span>
        <input
          type="text"
          name="title"
          maxLength={200}
          required
          defaultValue={homework.title}
        />
      </label>

      <label className="sched-field">
        <span className="sched-field__label">{t("form.instructionsLabel")}</span>
        <textarea
          name="instructions"
          rows={3}
          maxLength={4000}
          defaultValue=""
          placeholder={t("form.instructionsKeepPlaceholder")}
        />
        <span className="hw-field-hint">{t("form.instructionsEditHint")}</span>
      </label>

      <div className="sched-form__grid">
        <label className="sched-field">
          <span className="sched-field__label">{t("form.dueDateLabel")}</span>
          <input type="date" name="dueDate" defaultValue={due?.date ?? ""} />
        </label>
        <label className="sched-field">
          <span className="sched-field__label">{t("form.dueTimeLabel")}</span>
          <input type="time" name="dueTime" defaultValue={due?.time ?? ""} />
        </label>
        <label className="sched-field hw-field--check">
          <input type="checkbox" name="clearDueAt" />
          <span>{t("form.clearDueAt")}</span>
        </label>
      </div>

      <div className="sched-form__grid">
        <label className="sched-field">
          <span className="sched-field__label">{t("form.sessionLabel")}</span>
          <SessionSelect
            sessions={sessions}
            locale={locale}
            t={t}
            name="sessionId"
            defaultValue={homework.sessionId ?? ""}
          />
        </label>
        <label className="sched-field hw-field--check">
          <input type="checkbox" name="clearSession" />
          <span>{t("form.clearSession")}</span>
        </label>
      </div>

      {detailLoading ? (
        <p className="hw-boards__loading">{t("boards.loading")}</p>
      ) : detailError ? (
        <p className="sched-form__error" role="alert">
          {detailError}
        </p>
      ) : (
        <BoardBlocksEditor
          blocks={blocks}
          onChange={(next) => {
            setBlocks(next);
            setBlocksTouched(true);
          }}
          labels={labels}
        />
      )}

      <div className="sched-form__footer">
        <button
          className="btn btn--primary btn--sm"
          type="submit"
          disabled={pending || detailLoading}
        >
          {pending ? t("form.saving") : t("form.saveCta")}
        </button>
        <button
          className="btn btn--ghost btn--sm"
          type="button"
          onClick={onDone}
          disabled={pending}
        >
          {t("form.cancel")}
        </button>
        {state.error ? (
          <p className="sched-form__error" role="alert">
            {t(`error.${state.error}`)}
          </p>
        ) : null}
      </div>
    </form>
  );
}

const editWasSubmitted = { current: false };

// ------------------------------------------------------------------
// Archive button (native confirm)
// ------------------------------------------------------------------

function ArchiveButton({
  classId,
  homeworkId,
  locale,
  t,
}: {
  classId: string;
  homeworkId: string;
  locale: string;
  t: TFn;
}) {
  const [state, formAction, pending] = useActionState(
    archiveHomeworkAction,
    INITIAL,
  );
  return (
    <form
      action={formAction}
      className="sched-inline-form"
      onSubmit={(e) => {
        if (!window.confirm(t("row.archiveConfirm"))) {
          e.preventDefault();
        }
      }}
    >
      <input type="hidden" name="classId" value={classId} />
      <input type="hidden" name="homeworkId" value={homeworkId} />
      <input type="hidden" name="locale" value={locale} />
      <button type="submit" className="btn btn--ghost btn--sm" disabled={pending}>
        {pending ? t("row.archiving") : t("row.archive")}
      </button>
      {state.error ? (
        <span className="sched-field-error" role="alert">
          {t(`error.${state.error}`)}
        </span>
      ) : null}
    </form>
  );
}

// ------------------------------------------------------------------
// Submission sheet
// ------------------------------------------------------------------

function SubmissionSheet({
  classId,
  homeworkId,
  locale,
  canEdit,
  t,
}: {
  classId: string;
  homeworkId: string;
  locale: string;
  canEdit: boolean;
  t: TFn;
}) {
  const router = useRouter();
  const [detail, setDetail] = useState<HomeworkDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [savingRowId, setSavingRowId] = useState<string | null>(null);
  const [rowError, setRowError] = useState<string | null>(null);
  // Optimistic mirror of the server's live attempts list. We splice fresh
  // rows in on review so the status pill / note updates paint instantly;
  // the full detail revalidation still runs in the background via
  // {@code reviewBlockAttemptAction}'s {@code revalidatePath}.
  const [liveAttempts, setLiveAttempts] = useState<BlockAttemptView[]>([]);
  const [, startTransition] = useTransition();

  // First open → fetch. Re-opens within the same session reuse the cache.
  // Wrapped in `useEffect` so the setState calls don't run during render
  // (React 19 strict-mode bans updating state mid-render — the action's
  // .then() chain would otherwise schedule router updates while
  // SubmissionSheet is still rendering).
  //
  // CAREFUL: don't include `loading` in the dep array. `setLoading(true)`
  // would otherwise trigger a cleanup mid-fetch that flips
  // `cancelled = true` before the response lands, and the panel stays
  // stuck on the loading indicator forever. Gate keeps the flag to avoid
  // double-fetches; we just don't react to it changing.
  useEffect(() => {
    if (loading || detail !== null || loadError !== null) return;
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    void readHomeworkDetailAction(classId, homeworkId).then((res) => {
      if (cancelled) return;
      setLoading(false);
      if (!res.ok) {
        setLoadError(t(`error.${res.error}`));
        return;
      }
      setDetail(res.detail);
      setLiveAttempts(res.detail.attempts ?? []);
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classId, homeworkId, detail, loadError, t]);

  // Replace-in-place (keyed by attempt id) so the re-review path works too.
  function handleAttemptReviewed(fresh: BlockAttemptView) {
    setLiveAttempts((prev) => {
      const idx = prev.findIndex((a) => a.id === fresh.id);
      if (idx === -1) return [...prev, fresh];
      const next = prev.slice();
      next[idx] = fresh;
      return next;
    });
    // Kick a soft refresh so the class page's tally / detail revalidation
    // lands. The review endpoint doesn't change the DONE / SKIPPED counts
    // but a subsequent open of the sheet should reflect whatever the
    // server says — keeps us honest in the face of concurrent edits.
    startTransition(() => router.refresh());
  }

  async function markStudent(
    studentUserId: string,
    status: HomeworkSubmissionStatus | null,
    note: string | null,
  ) {
    if (!canEdit) return;
    setSavingRowId(studentUserId);
    setRowError(null);
    const res = await updateHomeworkSubmissionAction(
      classId,
      homeworkId,
      studentUserId,
      locale,
      status,
      note,
    );
    setSavingRowId(null);
    if (!res.ok) {
      setRowError(t(`error.${res.error}`));
      return;
    }
    setDetail(res.detail);
    // Revalidate the parent page so the tally pills update — use a transition
    // so the refresh doesn't block the inline state update above.
    startTransition(() => router.refresh());
  }

  if (loading) {
    return <div className="hw-sheet hw-sheet--loading">{t("sheet.loading")}</div>;
  }
  if (loadError) {
    return (
      <div className="hw-sheet hw-sheet--error" role="alert">
        {loadError}
      </div>
    );
  }
  if (!detail) {
    return null;
  }
  if (detail.submissions.length === 0) {
    return <div className="hw-sheet hw-sheet--empty">{t("sheet.empty")}</div>;
  }

  // Parse resources into viewable board blocks. Admins can unlock solutions
  // — this is the author's own surface, so we pass `canRevealSolution=true`.
  const viewBlocks = blocksFromResourcesForView(detail.resources ?? []);
  const viewerLabels = boardViewerLabels(t);
  const reviewLabels = blockReviewLabels(t);

  // Build the display-name lookup for the review cards.
  const studentNames = new Map<string, string>();
  for (const s of detail.submissions) {
    studentNames.set(s.studentUserId, s.displayName ?? s.studentUserId);
  }
  // The review surface matches attempts to blocks by {@code blockIndex ===
  // resource.position}. {@code blocksFromResourcesForView} filter-shifts
  // when a resource doesn't parse, which would mis-align attempts. Build
  // a dense array indexed by position instead: slots for unparseable
  // blocks are {@code null} and the review section skips them. In
  // practice the backend validates BOARD payloads on write so every
  // authored row parses — this is a defensive belt-and-braces layer.
  const sortedResources = (detail.resources ?? [])
    .slice()
    .sort((a, b) => a.position - b.position);
  const maxPosition =
    sortedResources.length > 0
      ? Math.max(...sortedResources.map((r) => r.position))
      : -1;
  const reviewBlocks: (BoardCardBlock | null)[] = new Array(
    Math.max(0, maxPosition + 1),
  ).fill(null);
  for (const r of sortedResources) {
    reviewBlocks[r.position] = parseBoardBlock(r);
  }

  return (
    <div className="hw-sheet">
      {detail.instructions ? (
        <p className="hw-sheet__instructions">{detail.instructions}</p>
      ) : null}

      {viewBlocks.length > 0 ? (
        <section className="hw-sheet__boards">
          <h4 className="hw-sheet__boards-heading">{t("sheet.boardsHeading")}</h4>
          <div className="hw-sheet__boards-list">
            {viewBlocks.map((b) => (
              <BoardCardView
                key={b.id}
                block={b}
                labels={viewerLabels}
                canRevealSolution={true}
                idPrefix="admin-sheet"
              />
            ))}
          </div>
        </section>
      ) : null}

      {canEdit && reviewBlocks.length > 0 ? (
        <BlockReviewSection
          classId={classId}
          homeworkId={homeworkId}
          locale={locale}
          blocks={reviewBlocks}
          attempts={liveAttempts}
          studentNames={studentNames}
          labels={reviewLabels}
          viewerLabels={viewerLabels}
          formatInstant={(iso) => formatInstant(iso, locale)}
          onAttemptReviewed={handleAttemptReviewed}
          canEdit={canEdit}
        />
      ) : null}

      <ul className="hw-sheet__list">
        {detail.submissions.map((s) => {
          const saving = savingRowId === s.studentUserId;
          return (
            <li key={s.studentUserId} className="hw-sheet__row">
              <div className="hw-sheet__name">
                {s.displayName ?? s.studentUserId}
              </div>
              <div className="hw-sheet__chips">
                {SUBMISSION_STATUSES.map((status) => {
                  const active = s.status === status;
                  return (
                    <button
                      key={status}
                      type="button"
                      className={
                        "hw-chip " +
                        (active
                          ? `hw-chip--active hw-chip--${status.toLowerCase()}`
                          : "")
                      }
                      aria-pressed={active}
                      disabled={!canEdit || saving}
                      onClick={() =>
                        markStudent(
                          s.studentUserId,
                          active ? null : status,
                          s.studentNote,
                        )
                      }
                    >
                      {t(`sheet.status.${status}`)}
                    </button>
                  );
                })}
                {s.status != null && canEdit ? (
                  <button
                    type="button"
                    className="hw-chip hw-chip--clear"
                    disabled={saving}
                    onClick={() => markStudent(s.studentUserId, null, null)}
                  >
                    {t("sheet.clear")}
                  </button>
                ) : null}
              </div>
              {s.submittedAt ? (
                <span className="hw-sheet__when">
                  {t("sheet.submittedAt", {
                    when: formatInstant(s.submittedAt, locale),
                  })}
                </span>
              ) : null}
            </li>
          );
        })}
      </ul>

      {rowError ? (
        <p className="hw-sheet__error" role="alert">
          {rowError}
        </p>
      ) : null}
    </div>
  );
}

// ------------------------------------------------------------------
// Session dropdown
// ------------------------------------------------------------------

function SessionSelect({
  sessions,
  locale,
  t,
  name,
  defaultValue,
}: {
  sessions: ScheduleSession[];
  locale: string;
  t: TFn;
  name: string;
  defaultValue?: string;
}) {
  return (
    <select name={name} defaultValue={defaultValue ?? ""}>
      <option value="">{t("form.sessionNone")}</option>
      {sessions.map((s) => (
        <option key={s.id} value={s.id}>
          {formatInstant(s.startsAt, locale)}
        </option>
      ))}
    </select>
  );
}

// ------------------------------------------------------------------
// Time formatting helpers
// ------------------------------------------------------------------

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
 * Flatten whitespace and truncate to at most {@code max} characters, suffixing
 * with an ellipsis when anything was dropped. Returns {@code null} when the
 * input is null / empty — callers use that to decide whether to render the
 * preview line at all.
 */
function truncatePreview(text: string | null | undefined, max: number): string | null {
  if (text == null) return null;
  // Collapse any internal newlines / runs of spaces so the preview stays on
  // a single line regardless of how the author wrote the instructions.
  const flat = text.replace(/\s+/g, " ").trim();
  if (!flat) return null;
  if (flat.length <= max) return flat;
  return flat.slice(0, max - 1).trimEnd() + "…";
}

/** Split a UTC ISO into a local YYYY-MM-DD + HH:mm pair in {@link DISPLAY_TZ}. */
function splitInstant(iso: string): { date: string; time: string } {
  const d = new Date(iso);
  const parts = new Intl.DateTimeFormat("sv-SE", {
    timeZone: DISPLAY_TZ,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(d);
  const y = parts.find((p) => p.type === "year")?.value ?? "1970";
  const m = parts.find((p) => p.type === "month")?.value ?? "01";
  const dd = parts.find((p) => p.type === "day")?.value ?? "01";
  const hh = parts.find((p) => p.type === "hour")?.value ?? "00";
  const mm = parts.find((p) => p.type === "minute")?.value ?? "00";
  return { date: `${y}-${m}-${dd}`, time: `${hh}:${mm}` };
}
