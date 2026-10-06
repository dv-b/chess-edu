"use client";

import { useActionState, useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams, usePathname } from "next/navigation";
import type { Route } from "next";
import { useTranslations } from "next-intl";
import {
  archiveLessonAction,
  createLessonAction,
  readLessonDetailAction,
  updateLessonAction,
  type LessonActionState,
  type LessonDetail,
  type LessonSummary,
} from "./lesson-actions";
import type { ScheduleSession } from "./schedule-panels";
import { bucketByTimestamp, isOnDay } from "@/lib/class-detail-buckets";
import { BucketedList } from "./bucketed-list";
import { DayFilterBanner } from "./day-filter-banner";
import {
  LessonBlocksEditor,
  lessonBlocksFromResources,
  lessonResourcesFromBlocks,
  type LessonBlock,
  type LessonBlocksEditorLabels,
} from "./lesson-blocks-editor";
import type { WalkthroughLabels } from "@/components/lesson-walkthrough-view";

type TFn = {
  (key: string): string;
  (key: string, values: Record<string, string | number | Date>): string;
};

const INITIAL: LessonActionState = {};

const DISPLAY_TZ = "Asia/Tashkent";

/**
 * Strict YYYY-MM-DD match. Mirrors the guard used by the Phase 2b calendar
 * strip so malformed `?day` URLs fall back silently instead of blowing up
 * the filter pipeline.
 */
function isValidYmd(s: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(s);
}

/**
 * Pull the flat key-list the {@link LessonBlocksEditor} wants out of the
 * existing next-intl translator. Mirrors
 * {@code homework-panels.tsx#boardEditorLabels} — keeps the editor itself
 * i18n-agnostic so it's easy to drop into future forms (e.g. a standalone
 * lesson authoring surface) without tangling with next-intl's react
 * bindings.
 */
/**
 * Build the editor labels bag. Takes both the editor-namespace `t`
 * (from `admin.classes.lessons`) and a viewer-namespace `tViewer`
 * (from `student.lessonViewer.walkthrough`) so the walkthrough
 * Preview tab can reuse the exact translation keys the student page
 * already ships — no duplicate i18n surface.
 */
function lessonBlocksEditorLabels(
  t: TFn,
  tViewer: TFn,
): LessonBlocksEditorLabels {
  const walkthroughViewLabels: WalkthroughLabels = {
    untitled: tViewer("untitled"),
    invalidBlock: tViewer("invalidBlock"),
    sideToMove: tViewer("sideToMove"),
    sideWhite: tViewer("sideWhite"),
    sideBlack: tViewer("sideBlack"),
    start: tViewer("start"),
    prev: tViewer("prev"),
    next: tViewer("next"),
    end: tViewer("end"),
    plyLabel: ({ ply, total }) => tViewer("plyLabel", { ply, total }),
    movesHeading: tViewer("movesHeading"),
    movesEmpty: tViewer("movesEmpty"),
    annotationsHeading: tViewer("annotationsHeading"),
    annotationAtStart: tViewer("annotationAtStart"),
    annotationAtPly: ({ ply }) => tViewer("annotationAtPly", { ply }),
  };
  return {
    walkthroughViewLabels,
    heading: t("blocks.heading"),
    hint: t("blocks.hint"),
    noBlocks: t("blocks.noBlocks"),
    addProse: t("blocks.addProse"),
    addWalkthrough: t("blocks.addWalkthrough"),
    addMcq: t("blocks.addMcq"),
    addVideo: t("blocks.addVideo"),
    removeBlock: t("blocks.removeBlock"),
    moveUp: t("blocks.moveUp"),
    moveDown: t("blocks.moveDown"),
    blockKindProse: t("blocks.blockKindProse"),
    blockKindWalkthrough: t("blocks.blockKindWalkthrough"),
    blockKindMcq: t("blocks.blockKindMcq"),
    blockKindVideo: t("blocks.blockKindVideo"),
    proseLabel: t("blocks.proseLabel"),
    prosePlaceholder: t("blocks.prosePlaceholder"),
    proseCharCount: t("blocks.proseCharCount"),
    proseTooLong: t("blocks.proseTooLong"),
    tabEdit: t("blocks.tabEdit"),
    tabPreview: t("blocks.tabPreview"),
    prosePreviewEmpty: t("blocks.prosePreviewEmpty"),
    videoPreviewInvalid: t("blocks.videoPreviewInvalid"),
    mcqPreviewCorrect: t("blocks.mcqPreviewCorrect"),
    mcqPreviewEmpty: t("blocks.mcqPreviewEmpty"),
    blockTitleLabel: t("blocks.blockTitleLabel"),
    blockTitlePlaceholder: t("blocks.blockTitlePlaceholder"),
    startingFenLabel: t("blocks.startingFenLabel"),
    resetToStart: t("blocks.resetToStart"),
    useCurrentAsStart: t("blocks.useCurrentAsStart"),
    orientationLabel: t("blocks.orientationLabel"),
    orientationWhite: t("blocks.orientationWhite"),
    orientationBlack: t("blocks.orientationBlack"),
    movesHeading: t("blocks.movesHeading"),
    movesCount: t("blocks.movesCount"),
    movesEmpty: t("blocks.movesEmpty"),
    pastePgnLabel: t("blocks.pastePgnLabel"),
    pastePgnPlaceholder: t("blocks.pastePgnPlaceholder"),
    pastePgnReplace: t("blocks.pastePgnReplace"),
    pastePgnAppend: t("blocks.pastePgnAppend"),
    undoLastMove: t("blocks.undoLastMove"),
    clearAllMoves: t("blocks.clearAllMoves"),
    sideToMove: t("blocks.sideToMove"),
    sideWhite: t("blocks.sideWhite"),
    sideBlack: t("blocks.sideBlack"),
    invalidFen: t("blocks.invalidFen"),
    invalidPgn: t("blocks.invalidPgn"),
    modeSetup: t("blocks.modeSetup"),
    modeMoves: t("blocks.modeMoves"),
    modePreview: t("blocks.modePreview"),
    setupFillStart: t("blocks.setupFillStart"),
    setupEmpty: t("blocks.setupEmpty"),
    setupPaletteHint: t("blocks.setupPaletteHint"),
    setupRemoveHint: t("blocks.setupRemoveHint"),
    setupCastling: t("blocks.setupCastling"),
    setupCastleK: t("blocks.setupCastleK"),
    setupCastleQ: t("blocks.setupCastleQ"),
    setupCastlek: t("blocks.setupCastlek"),
    setupCastleq: t("blocks.setupCastleq"),
    setupSideToMove: t("blocks.setupSideToMove"),
    setupAdvancedShow: t("blocks.setupAdvancedShow"),
    setupAdvancedHide: t("blocks.setupAdvancedHide"),
    setupIllegalPositionNotice: t("blocks.setupIllegalPositionNotice"),
    annotationsHeading: t("blocks.annotationsHeading"),
    annotationsHint: t("blocks.annotationsHint"),
    annotationsEmpty: t("blocks.annotationsEmpty"),
    annotationsCount: t("blocks.annotationsCount"),
    annotationsAdd: t("blocks.annotationsAdd"),
    annotationsAddAtStart: t("blocks.annotationsAddAtStart"),
    annotationsDelete: t("blocks.annotationsDelete"),
    annotationsPlyLabel: t("blocks.annotationsPlyLabel"),
    annotationsPlyHint: t("blocks.annotationsPlyHint"),
    annotationsTextLabel: t("blocks.annotationsTextLabel"),
    annotationsTextPlaceholder: t("blocks.annotationsTextPlaceholder"),
    annotationsMaxReached: t("blocks.annotationsMaxReached"),
    mcqQuestionLabel: t("blocks.mcqQuestionLabel"),
    mcqQuestionPlaceholder: t("blocks.mcqQuestionPlaceholder"),
    mcqQuestionCharCount: t("blocks.mcqQuestionCharCount"),
    mcqQuestionTooLong: t("blocks.mcqQuestionTooLong"),
    mcqOptionsLabel: t("blocks.mcqOptionsLabel"),
    mcqOptionsHint: t("blocks.mcqOptionsHint"),
    mcqOptionLabel: t("blocks.mcqOptionLabel"),
    mcqOptionPlaceholder: t("blocks.mcqOptionPlaceholder"),
    mcqOptionCorrect: t("blocks.mcqOptionCorrect"),
    mcqOptionCorrectAria: t("blocks.mcqOptionCorrectAria"),
    mcqOptionRemove: t("blocks.mcqOptionRemove"),
    mcqOptionTooLong: t("blocks.mcqOptionTooLong"),
    mcqAddOption: t("blocks.mcqAddOption"),
    mcqRemoveOption: t("blocks.mcqRemoveOption"),
    mcqMinOptions: t("blocks.mcqMinOptions"),
    mcqMaxOptions: t("blocks.mcqMaxOptions"),
    mcqDuplicateOption: t("blocks.mcqDuplicateOption"),
    mcqExplanationLabel: t("blocks.mcqExplanationLabel"),
    mcqExplanationPlaceholder: t("blocks.mcqExplanationPlaceholder"),
    mcqExplanationCharCount: t("blocks.mcqExplanationCharCount"),
    mcqExplanationTooLong: t("blocks.mcqExplanationTooLong"),
    videoUrlLabel: t("blocks.videoUrlLabel"),
    videoUrlPlaceholder: t("blocks.videoUrlPlaceholder"),
    videoUrlHint: t("blocks.videoUrlHint"),
    videoInvalidUrl: t("blocks.videoInvalidUrl"),
    videoNotHttps: t("blocks.videoNotHttps"),
    videoHostNotAllowed: t("blocks.videoHostNotAllowed"),
    videoProviderYoutube: t("blocks.videoProviderYoutube"),
    videoProviderVimeo: t("blocks.videoProviderVimeo"),
    videoProviderLabel: t("blocks.videoProviderLabel"),
    videoAllowedHosts: t("blocks.videoAllowedHosts"),
  };
}

/**
 * Admin / teacher-facing Lessons panel. Sits below the Homework section on
 * the class detail page and handles the metadata surface:
 *
 * <ul>
 *   <li>Active / archived tabs (the "archived" view is opt-in).</li>
 *   <li>Inline "New lesson" form with optional session anchor.</li>
 *   <li>Per-row "Edit" dialog (toggled inline form) that covers PATCH
 *       including {@code clearSession}.</li>
 *   <li>"Archive" with a native confirm prompt.</li>
 *   <li>Expandable completion sheet per row — read-only for v1 since the
 *       backend doesn't expose an admin-side per-student completion write
 *       endpoint. The sheet still shows who has read each lesson + when,
 *       which is the only thing the author / teacher needs to eyeball
 *       engagement.</li>
 * </ul>
 *
 * <p>The block editor is out of scope for Task #53; it lands in #54 (PROSE
 * + BOARD_WALKTHROUGH) and #55 (MCQ + VIDEO). Until then the form sends no
 * {@code resources} field on create and omits it on PATCH, which leaves the
 * backend's authored blocks untouched.
 *
 * <p>When {@code canEdit} is false (unassigned teacher reading the page)
 * the whole panel still renders read-only — the backend enforces the real
 * authorisation so the flags here are purely cosmetic.
 */
export function LessonPanels({
  classId,
  locale,
  active,
  archived,
  sessions,
  canEdit,
}: {
  classId: string;
  locale: string;
  /** Active (non-archived) lessons, already sorted newest-first by API. */
  active: LessonSummary[];
  /** Archived lessons, same sort. Empty unless the backend returned any. */
  archived: LessonSummary[];
  /** Upcoming sessions available as optional anchors on the new form. */
  sessions: ScheduleSession[];
  canEdit: boolean;
}) {
  const t = useTranslations("admin.classes.lessons");
  const tBuckets = useTranslations("admin.classes.buckets");
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [showArchived, setShowArchived] = useState(false);
  const [showAdd, setShowAdd] = useState(false);
  const [query, setQuery] = useState("");

  // Read the Phase 2b calendar strip's day-pin param. Ignored on the
  // archived tab (review-only surface) and when the param doesn't match the
  // strict YYYY-MM-DD shape — see {@link isValidYmd}.
  const rawDay = searchParams.get("day");
  const dayFilter =
    !showArchived && rawDay != null && isValidYmd(rawDay) ? rawDay : null;

  const archivedCount = archived.length;
  const rows = showArchived ? archived : active;

  // Case-insensitive substring search on title + instructions. Dense panels,
  // typos should still reveal matches so fall back to raw includes().
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((l) => {
      const title = l.title.toLowerCase();
      const inst = (l.instructions ?? "").toLowerCase();
      return title.includes(q) || inst.includes(q);
    });
  }, [rows, query]);

  // Layered day-filter: untimed lessons (no session anchor) never land on a
  // specific day, so they drop out when {@code ?day} is active. Matches the
  // same accessor the bucketing helper uses below so the semantics stay
  // coherent.
  const dayFiltered = useMemo(() => {
    if (!dayFilter) return filtered;
    return filtered.filter((l) => isOnDay(l.sessionStartsAt ?? null, dayFilter));
  }, [filtered, dayFilter]);

  // Lesson timestamp = the anchored session's start when present, else the
  // denormalised `sessionStartsAt`, else null (untimed lesson). Archive view
  // stays flat — it's review-only.
  const buckets = useMemo(
    () =>
      showArchived
        ? null
        : bucketByTimestamp(dayFiltered, (l) => l.sessionStartsAt ?? null),
    [dayFiltered, showArchived],
  );

  // Strip {@code ?day} from the URL while preserving any other params. See
  // the homework panel's twin for rationale behind the {@link Route} cast.
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
        <NewLessonForm
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
          {filtered.map((l) => (
            <LessonRow
              key={l.id}
              classId={classId}
              locale={locale}
              lesson={l}
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
          renderRow={(l) => (
            <LessonRow
              key={l.id}
              classId={classId}
              locale={locale}
              lesson={l}
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
// New lesson form
// ------------------------------------------------------------------

function NewLessonForm({
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
  const tWalkthrough = useTranslations("student.lessonViewer.walkthrough");
  const [state, formAction, pending] = useActionState(
    createLessonAction,
    INITIAL,
  );
  const [blocks, setBlocks] = useState<LessonBlock[]>([]);

  // Close the form automatically when the last submit succeeded. Same trick
  // the homework form uses: useActionState clears `error` on the next OK
  // submit, so absence of error after a submit attempt is the success signal.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  if (!pending && state && !state.error && createWasSubmitted.current) {
    createWasSubmitted.current = false;
    setBlocks([]);
    queueMicrotask(onCreated);
  }

  const labels = lessonBlocksEditorLabels(t, tWalkthrough);
  // Only send `resources` when the author added at least one block. An empty
  // list would just mean "no content" and the backend accepts the create
  // anyway — skipping the field keeps the POST body honest.
  const resourcesJson =
    blocks.length > 0
      ? JSON.stringify(lessonResourcesFromBlocks(blocks))
      : "";

  return (
    <form
      className="sched-form hw-form"
      action={(fd) => {
        createWasSubmitted.current = true;
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
          maxLength={8000}
          placeholder={t("form.instructionsPlaceholder")}
        />
      </label>

      <div className="sched-form__grid">
        <label className="sched-field">
          <span className="sched-field__label">{t("form.sessionLabel")}</span>
          <SessionSelect sessions={sessions} locale={locale} t={t} name="sessionId" />
        </label>
      </div>

      <LessonBlocksEditor
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

// Module-level ref so the submit-closed-on-success effect doesn't require
// a client-only useRef — useActionState can resolve between render passes.
// Separate ref per form-site (create vs. edit) so a parallel create + edit
// on the same page don't race each other's close handlers.
const createWasSubmitted = { current: false };
const editWasSubmitted = { current: false };

// ------------------------------------------------------------------
// Single row with collapse controls
// ------------------------------------------------------------------

function LessonRow({
  classId,
  locale,
  lesson,
  sessions,
  canEdit,
  t,
}: {
  classId: string;
  locale: string;
  lesson: LessonSummary;
  sessions: ScheduleSession[];
  canEdit: boolean;
  t: TFn;
}) {
  const [editing, setEditing] = useState(false);
  const [sheetOpen, setSheetOpen] = useState(false);

  // Same Jackson non_null serialisation gotcha as homework — a non-archived
  // row arrives with `archivedAt` absent (undefined) not null. Use loose
  // inequality so both shapes read as "not archived".
  const isArchived = lesson.archivedAt != null;
  const createdLabel = formatInstant(lesson.createdAt, locale);
  const sessionStartIso =
    lesson.sessionStartsAt ??
    (lesson.sessionId
      ? (sessions.find((s) => s.id === lesson.sessionId)?.startsAt ?? null)
      : null);
  const sessionLabel = sessionStartIso
    ? formatInstant(sessionStartIso, locale)
    : null;
  const instructionsPreview = truncatePreview(lesson.instructions, 140);

  return (
    <li className="hw-row">
      <div className="hw-row__head">
        <div className="hw-row__main">
          <div className="hw-row__title">
            {lesson.title}
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
            <span className="hw-meta-item">
              {t("row.created", { when: createdLabel })}
            </span>
            <span className="hw-meta-item">
              {t("row.blocks", { count: lesson.blockCount })}
            </span>
            <Tally lesson={lesson} t={t} />
          </div>
          {instructionsPreview ? (
            <p className="hw-row__instructions" title={lesson.instructions ?? undefined}>
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
              lessonId={lesson.id}
              locale={locale}
              t={t}
            />
          ) : null}
        </div>
      </div>

      {editing && canEdit && !isArchived ? (
        <EditLessonForm
          classId={classId}
          locale={locale}
          lesson={lesson}
          sessions={sessions}
          t={t}
          onDone={() => setEditing(false)}
        />
      ) : null}

      {sheetOpen ? (
        <CompletionSheet
          classId={classId}
          lessonId={lesson.id}
          locale={locale}
          t={t}
        />
      ) : null}
    </li>
  );
}

/**
 * Read / unread tally. Unread = roster minus read; both explicit
 * NOT_STARTED rows and null (no-row) students count as unread, matching
 * the backend's "explicit NOT_STARTED is not a self-declared read" contract.
 */
function Tally({ lesson, t }: { lesson: LessonSummary; t: TFn }) {
  const unread = Math.max(0, lesson.rosterSize - lesson.readCount);
  return (
    <span className="hw-tally" aria-label={t("row.tallyAria")}>
      <span className="hw-tally__item hw-tally__item--done">
        {t("row.tallyRead", { count: lesson.readCount })}
      </span>
      <span className="hw-tally__item hw-tally__item--unmarked">
        {t("row.tallyUnread", { count: unread })}
      </span>
    </span>
  );
}

// ------------------------------------------------------------------
// Edit form (inline, toggled)
// ------------------------------------------------------------------

function EditLessonForm({
  classId,
  locale,
  lesson,
  sessions,
  t,
  onDone,
}: {
  classId: string;
  locale: string;
  lesson: LessonSummary;
  sessions: ScheduleSession[];
  t: TFn;
  onDone: () => void;
}) {
  const tWalkthrough = useTranslations("student.lessonViewer.walkthrough");
  const [state, formAction, pending] = useActionState(
    updateLessonAction,
    INITIAL,
  );

  // eslint-disable-next-line react-hooks/exhaustive-deps
  if (!pending && state && !state.error && editWasSubmitted.current) {
    editWasSubmitted.current = false;
    queueMicrotask(onDone);
  }

  // Three-state PATCH on `resources`: untouched editor → omit the hidden
  // input entirely (leaves existing blocks alone on the backend);
  // emptied editor → send "[]" (clears all blocks); populated → send the
  // full serialised list. Same gate the homework twin uses.
  const [blocks, setBlocks] = useState<LessonBlock[]>([]);
  const [blocksTouched, setBlocksTouched] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailLoaded, setDetailLoaded] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  // Lazy fetch on first render — matches the SubmissionSheet / HomeworkEdit
  // pattern. The summary row doesn't carry `resources`, so the editor has to
  // hit the detail endpoint before it can hydrate. Wrapped in `useEffect` so
  // the setState calls don't run during render (React 19 strict-mode bans
  // updating a different component's state mid-render — the action's .then()
  // chain calls would otherwise schedule router updates while EditLessonForm
  // is still rendering).
  //
  // CAREFUL: don't include `detailLoading` in the dep array.
  // `setDetailLoading(true)` would otherwise trigger a cleanup that flips
  // `cancelled = true` mid-fetch, and the .then bail leaves the form stuck
  // on the "Loading…" indicator. The gate keeps `detailLoading` so a fast
  // re-trigger from another dep change doesn't double-fetch; we just don't
  // react to it changing.
  useEffect(() => {
    if (detailLoaded || detailLoading || detailError !== null) return;
    let cancelled = false;
    setDetailLoading(true);
    void readLessonDetailAction(classId, lesson.id).then((res) => {
      if (cancelled) return;
      setDetailLoading(false);
      if (!res.ok) {
        setDetailError(t(`error.${res.error}`));
        setDetailLoaded(true);
        return;
      }
      setBlocks(lessonBlocksFromResources(res.detail.resources ?? []));
      setDetailLoaded(true);
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classId, lesson.id, detailLoaded, detailError, t]);

  const labels = lessonBlocksEditorLabels(t, tWalkthrough);
  const resourcesJson = blocksTouched
    ? JSON.stringify(lessonResourcesFromBlocks(blocks))
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
      <input type="hidden" name="lessonId" value={lesson.id} />
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
          defaultValue={lesson.title}
        />
      </label>

      <label className="sched-field">
        <span className="sched-field__label">{t("form.instructionsLabel")}</span>
        <textarea
          name="instructions"
          rows={3}
          maxLength={8000}
          defaultValue=""
          placeholder={t("form.instructionsKeepPlaceholder")}
        />
        <span className="hw-field-hint">{t("form.instructionsEditHint")}</span>
      </label>

      <div className="sched-form__grid">
        <label className="sched-field">
          <span className="sched-field__label">{t("form.sessionLabel")}</span>
          <SessionSelect
            sessions={sessions}
            locale={locale}
            t={t}
            name="sessionId"
            defaultValue={lesson.sessionId ?? ""}
          />
        </label>
        <label className="sched-field hw-field--check">
          <input type="checkbox" name="clearSession" />
          <span>{t("form.clearSession")}</span>
        </label>
      </div>

      {detailLoading ? (
        <p className="hw-boards__loading">{t("blocks.loading")}</p>
      ) : detailError ? (
        <p className="sched-form__error" role="alert">
          {detailError}
        </p>
      ) : (
        <LessonBlocksEditor
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

// ------------------------------------------------------------------
// Archive button (native confirm)
// ------------------------------------------------------------------

function ArchiveButton({
  classId,
  lessonId,
  locale,
  t,
}: {
  classId: string;
  lessonId: string;
  locale: string;
  t: TFn;
}) {
  const [state, formAction, pending] = useActionState(
    archiveLessonAction,
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
      <input type="hidden" name="lessonId" value={lessonId} />
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
// Completion sheet (read-only in v1)
// ------------------------------------------------------------------

/**
 * Read-only per-student completion sheet. Lessons have no admin write
 * endpoint for per-student completion — the student is the only actor who
 * can change their own row. This component simply fetches the detail and
 * renders a flat list of (displayName, status, markedAt) tuples grouped by
 * status for quick eyeballing of engagement.
 */
function CompletionSheet({
  classId,
  lessonId,
  locale,
  t,
}: {
  classId: string;
  lessonId: string;
  locale: string;
  t: TFn;
}) {
  const [detail, setDetail] = useState<LessonDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  // First open → fetch. Re-opens within the same session reuse the cache.
  if (!loading && detail === null && loadError === null) {
    setLoading(true);
    setLoadError(null);
    void readLessonDetailAction(classId, lessonId).then((res) => {
      setLoading(false);
      if (!res.ok) {
        setLoadError(t(`error.${res.error}`));
        return;
      }
      setDetail(res.detail);
    });
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
  if (detail.completions.length === 0) {
    return <div className="hw-sheet hw-sheet--empty">{t("sheet.empty")}</div>;
  }

  return (
    <div className="hw-sheet">
      {detail.instructions ? (
        <p className="hw-sheet__instructions">{detail.instructions}</p>
      ) : null}

      <ul className="hw-sheet__list">
        {detail.completions.map((c) => (
          <li key={c.studentUserId} className="hw-sheet__row">
            <div className="hw-sheet__name">
              {c.displayName ?? c.studentUserId}
            </div>
            <div className="hw-sheet__chips">
              <span
                className={
                  "hw-chip " +
                  (c.status === "READ"
                    ? "hw-chip--active hw-chip--done"
                    : c.status === "NOT_STARTED"
                    ? "hw-chip--active hw-chip--skipped"
                    : "hw-chip--readonly")
                }
                aria-pressed={c.status != null}
              >
                {c.status === "READ"
                  ? t("sheet.status.READ")
                  : c.status === "NOT_STARTED"
                  ? t("sheet.status.NOT_STARTED")
                  : t("sheet.status.UNMARKED")}
              </span>
            </div>
            {c.markedAt ? (
              <span className="hw-sheet__when">
                {t("sheet.markedAt", {
                  when: formatInstant(c.markedAt, locale),
                })}
              </span>
            ) : null}
          </li>
        ))}
      </ul>
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
 * Flatten whitespace and truncate to at most {@code max} characters,
 * suffixing with an ellipsis when anything was dropped. Returns
 * {@code null} when the input is null / empty — callers use that to decide
 * whether to render the preview line at all.
 */
function truncatePreview(text: string | null | undefined, max: number): string | null {
  if (text == null) return null;
  const flat = text.replace(/\s+/g, " ").trim();
  if (!flat) return null;
  if (flat.length <= max) return flat;
  return flat.slice(0, max - 1).trimEnd() + "…";
}
