"use client";

import {
  Fragment,
  useActionState,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { useTranslations } from "next-intl";
import {
  finishLessonAction,
  updateMyLessonCompletionAction,
  type StudentActionState,
} from "../../actions";
import { StudentBlockQa } from "../../qa-block-panel";
import { renderProseMarkdown } from "@/components/prose-render";
import {
  LessonWalkthroughView,
  parseWalkthroughBlockFromWire,
  type WalkthroughLabels,
} from "@/components/lesson-walkthrough-view";

/**
 * Resource read-shape on the wire. Mirrors the backend's
 * {@code LessonResourceView} record — the {@code payload} arrives as a
 * plain JSON object whose shape depends on {@code kind}, and the four v1
 * kinds ({@code PROSE} / {@code BOARD_WALKTHROUGH} / {@code MCQ} /
 * {@code VIDEO}) are documented on the Java entity. Unlike homework,
 * nothing is stripped server-side — MCQ {@code correctIndex} and the
 * BOARD_WALKTHROUGH full move list both ship verbatim (see the DTO
 * javadoc for the rationale).
 */
type LessonResource = {
  id: string;
  position: number;
  kind: string;
  title: string | null;
  payload: Record<string, unknown>;
};

/**
 * Read shape returned by {@code GET /api/v1/me/lessons/{id}}. Mirrors the
 * backend {@code StudentLessonDetail} record one-for-one. Two shape notes
 * worth flagging when you're staring at this next to the homework twin:
 *
 * <ul>
 *   <li>No {@code dueAt} / {@code submittedAt} / {@code studentNote} /
 *       {@code attempts} — lessons are revisitable study material with a
 *       binary READ / NOT_STARTED grain. No teacher-review axis.</li>
 *   <li>{@code status} is a three-way union with {@code null} meaning
 *       "no completion row yet" (distinct from an explicit NOT_STARTED
 *       write which preserves the "opened but didn't finish" trail).</li>
 * </ul>
 */
export type StudentLessonDetail = {
  id: string;
  classId: string;
  className: string | null;
  sessionId: string | null;
  sessionStartsAt: string | null;
  title: string;
  instructions: string | null;
  createdAt: string;
  status: "NOT_STARTED" | "READ" | null;
  markedAt: string | null;
  resources: LessonResource[];
};

type TFn = {
  (key: string): string;
  (key: string, values: Record<string, string | number | Date>): string;
};

const INITIAL: StudentActionState = {};

const DISPLAY_TZ = "Asia/Tashkent";

/**
 * Wall-clock "20 Apr 18:00" label anchored to Asia/Tashkent, matching the
 * rhythm of {@code page.tsx}'s header meta. Shared with the annotations
 * rail in {@link LessonBoardWalkthroughView}.
 */
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

// ---------------------------------------------------------------------------
// PROSE — inline safe markdown renderer
// ---------------------------------------------------------------------------
//
// Pulled out into `@/components/prose-render` so the lesson editor's
// Preview tab (Lane C / Task #74) can render the same exact markdown
// output authors will see on the student detail page. See that module
// for the full implementation + safety notes; this file just consumes
// `renderProseMarkdown(src, keyPrefix)`.


/**
 * Friendly fallback for a block whose payload didn't parse cleanly — e.g.
 * an MCQ whose {@code options} disappeared between authoring and read, or
 * a VIDEO with a {@code url} that no longer matches the host allowlist. The
 * walkthrough renderer has its own kind-specific fallback inline (because
 * it needs to swallow FEN / move-list errors), so this component is shared
 * by MCQ + VIDEO + any future kind whose parser might return {@code null}.
 * Keeps the visual rhythm of the block list intact: same paper-elevated
 * card chrome + same title slot + a dashed-border orange notice so the
 * viewer knows something's off without crashing the page.
 */
function LessonInvalidBlock({
  resource,
  t,
}: {
  resource: LessonResource;
  t: TFn;
}) {
  return (
    <article className="lesson-viewer__block lesson-viewer__block--invalid">
      {resource.title ? (
        <h3 className="lesson-viewer__block-title">{resource.title}</h3>
      ) : null}
      <p className="lesson-viewer__invalid">
        {t("lessonViewer.invalidBlock")}
      </p>
    </article>
  );
}

function LessonProseBlock({
  resource,
  t,
}: {
  resource: LessonResource;
  t: TFn;
}) {
  const raw =
    typeof resource.payload?.markdown === "string"
      ? (resource.payload.markdown as string)
      : "";
  const hasContent = raw.trim().length > 0;
  return (
    <article className="lesson-viewer__block lesson-viewer__block--prose">
      {resource.title ? (
        <h3 className="lesson-viewer__block-title">{resource.title}</h3>
      ) : null}
      <div className="lesson-prose">
        {hasContent ? (
          renderProseMarkdown(raw, `prose-${resource.id}`)
        ) : (
          <p className="lesson-prose__empty">{t("lessonViewer.prose.empty")}</p>
        )}
      </div>
    </article>
  );
}


// ---------------------------------------------------------------------------
// MCQ — interactive quiz question with lock-on-submit + retry
// ---------------------------------------------------------------------------

/**
 * Wire payload for an MCQ block. Mirrors the backend validator's canonical
 * shape — {@code question} + 2–4 {@code options} + {@code correctIndex} +
 * optional {@code explanation}. Unlike homework, nothing is scrubbed
 * server-side; {@code correctIndex} ships verbatim and the client mediates
 * the reveal UX (lock on submit, not a security boundary).
 */
type McqPayload = {
  question: string;
  options: string[];
  correctIndex: number;
  explanation: string | null;
};

/**
 * Narrow the untyped {@code JsonNode} payload into {@link McqPayload}. Any
 * shape violation (fewer than 2 options, a non-integer {@code correctIndex},
 * an out-of-range index, a non-string question) returns {@code null} so the
 * caller can render the friendly {@link LessonInvalidBlock} fallback instead
 * of crashing. The backend validator already enforces these, but defending
 * here keeps the viewer honest against legacy rows / crafted payloads.
 */
function parseMcqPayload(raw: Record<string, unknown>): McqPayload | null {
  const question = raw["question"];
  const options = raw["options"];
  const correctIndex = raw["correctIndex"];
  const explanation = raw["explanation"];
  if (typeof question !== "string" || question.trim().length === 0) {
    return null;
  }
  if (!Array.isArray(options) || options.length < 2 || options.length > 4) {
    return null;
  }
  const stringOptions: string[] = [];
  for (const opt of options) {
    if (typeof opt !== "string") return null;
    stringOptions.push(opt);
  }
  if (typeof correctIndex !== "number" || !Number.isInteger(correctIndex)) {
    return null;
  }
  if (correctIndex < 0 || correctIndex >= stringOptions.length) {
    return null;
  }
  const explanationStr =
    typeof explanation === "string" && explanation.trim().length > 0
      ? explanation
      : null;
  return {
    question,
    options: stringOptions,
    correctIndex,
    explanation: explanationStr,
  };
}

/**
 * Student-facing MCQ renderer. Owns three pieces of local state:
 *
 * <ul>
 *   <li>{@code selected} — the radio currently highlighted, cleared on
 *       retry.</li>
 *   <li>{@code submitted} — has the student locked in their guess yet?
 *       Once true, the radio group is visually locked (inputs go
 *       {@code disabled}, the correct answer picks up the green tint, the
 *       chosen-wrong answer the red tint) until {@code Try again} resets
 *       both flags.</li>
 *   <li>{@code attempts} — count of submissions this mount. Exposed only
 *       via the {@code aria-label} on the retry button so a screen reader
 *       can say "Try again (attempt 2)" if the student keeps missing.</li>
 * </ul>
 *
 * Deliberate non-goals: no server persistence of the guess (same reason as
 * walkthrough playhead — completion is the only server-persisted per-
 * student signal); no "X of Y tries remaining" — a lesson is revisitable.
 */
function LessonMcqView({
  resource,
  t,
}: {
  resource: LessonResource;
  t: TFn;
}) {
  const payload = useMemo(
    () => parseMcqPayload(resource.payload),
    [resource.payload],
  );
  const [selected, setSelected] = useState<number | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const [attempts, setAttempts] = useState(0);

  if (payload == null) {
    return <LessonInvalidBlock resource={resource} t={t} />;
  }

  const title = resource.title?.trim()
    ? resource.title
    : t("lessonViewer.mcq.defaultTitle");
  const isCorrect = submitted && selected === payload.correctIndex;
  const isWrong =
    submitted && selected !== null && selected !== payload.correctIndex;

  const onSubmit = () => {
    if (selected == null || submitted) return;
    setSubmitted(true);
    setAttempts((n) => n + 1);
  };

  const onRetry = () => {
    setSelected(null);
    setSubmitted(false);
  };

  return (
    <article className="lesson-viewer__block lesson-viewer__block--mcq lesson-mcq-view">
      <h3 className="lesson-viewer__block-title">{title}</h3>
      <p className="lesson-mcq-view__prompt">{payload.question}</p>

      <fieldset
        className="lesson-mcq-view__choices"
        // Disabling the fieldset applies to every nested radio so the student
        // can't mutate their answer after submit without going through
        // "Try again". Native + accessible in one attribute.
        disabled={submitted}
      >
        <legend className="lesson-mcq-view__choices-legend">
          {t("lessonViewer.mcq.choicesLegend")}
        </legend>
        <ul className="lesson-mcq-view__choices-list">
          {payload.options.map((opt, idx) => {
            const id = `mcq-${resource.id}-${idx}`;
            const isCorrectOpt = idx === payload.correctIndex;
            const isSelectedOpt = selected === idx;
            // Visual state: pre-submit, the only active cue is the radio
            // dot. Post-submit, paint green on the correct option always,
            // and red on the selected one if it was wrong.
            let mod = "";
            if (submitted) {
              if (isCorrectOpt) mod = " lesson-mcq-view__choice--correct";
              else if (isSelectedOpt) mod = " lesson-mcq-view__choice--wrong";
            }
            return (
              <li
                key={idx}
                className={"lesson-mcq-view__choice" + mod}
              >
                <input
                  type="radio"
                  id={id}
                  name={`mcq-${resource.id}`}
                  className="lesson-mcq-view__radio"
                  checked={isSelectedOpt}
                  onChange={() => setSelected(idx)}
                />
                <label htmlFor={id} className="lesson-mcq-view__label">
                  <span className="lesson-mcq-view__option-index">
                    {t("lessonViewer.mcq.optionLabel", { index: idx + 1 })}
                  </span>
                  <span className="lesson-mcq-view__option-text">{opt}</span>
                  {submitted && isCorrectOpt ? (
                    <span
                      className="lesson-mcq-view__option-tag lesson-mcq-view__option-tag--correct"
                      aria-label={t("lessonViewer.mcq.correctAnswerAria")}
                    >
                      {t("lessonViewer.mcq.correctAnswerTag")}
                    </span>
                  ) : null}
                  {submitted && isSelectedOpt && !isCorrectOpt ? (
                    <span
                      className="lesson-mcq-view__option-tag lesson-mcq-view__option-tag--wrong"
                      aria-label={t("lessonViewer.mcq.yourAnswerAria")}
                    >
                      {t("lessonViewer.mcq.yourAnswerTag")}
                    </span>
                  ) : null}
                </label>
              </li>
            );
          })}
        </ul>
      </fieldset>

      {submitted ? (
        <div
          className={
            "lesson-mcq-view__feedback" +
            (isCorrect
              ? " lesson-mcq-view__feedback--correct"
              : " lesson-mcq-view__feedback--wrong")
          }
          role="status"
          aria-live="polite"
        >
          <p className="lesson-mcq-view__feedback-head">
            {isCorrect
              ? t("lessonViewer.mcq.correctBanner")
              : t("lessonViewer.mcq.wrongBanner")}
          </p>
          {payload.explanation ? (
            <>
              <p className="lesson-mcq-view__feedback-eyebrow">
                {t("lessonViewer.mcq.explanationHeading")}
              </p>
              <p className="lesson-mcq-view__feedback-body">
                {payload.explanation}
              </p>
            </>
          ) : isWrong ? (
            <p className="lesson-mcq-view__feedback-body lesson-mcq-view__feedback-body--hint">
              {t("lessonViewer.mcq.noExplanation")}
            </p>
          ) : null}
        </div>
      ) : null}

      <div className="lesson-mcq-view__actions">
        {!submitted ? (
          <button
            type="button"
            className="btn btn--primary btn--sm"
            disabled={selected == null}
            onClick={onSubmit}
          >
            {t("lessonViewer.mcq.submit")}
          </button>
        ) : (
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            onClick={onRetry}
            aria-label={t("lessonViewer.mcq.tryAgainAria", {
              attempts: attempts,
            })}
          >
            {t("lessonViewer.mcq.tryAgain")}
          </button>
        )}
      </div>
    </article>
  );
}

// ---------------------------------------------------------------------------
// VIDEO — responsive 16:9 iframe embed (YouTube / Vimeo)
// ---------------------------------------------------------------------------

type VideoProvider = "youtube" | "vimeo" | "unknown";

type VideoEmbed = {
  provider: VideoProvider;
  /** The embeddable src URL for the iframe. Null when we can't build one. */
  embedUrl: string | null;
  /** The canonical watch URL — always reflects what the author typed. */
  watchUrl: string;
};

/**
 * Build the embeddable {@code src} URL from a validated lesson video URL.
 * Matches the backend's host allowlist from
 * {@code LessonResourceValidator#VIDEO_HOST_ALLOWLIST}:
 *
 * <ul>
 *   <li>{@code youtube.com/watch?v=X} → {@code youtube-nocookie.com/embed/X}
 *       (cookie-free variant respects student privacy; no tracking until
 *       playback starts)</li>
 *   <li>{@code youtu.be/X} → same embed shape</li>
 *   <li>{@code vimeo.com/12345} → {@code player.vimeo.com/video/12345}</li>
 *   <li>{@code player.vimeo.com/video/X} → passthrough</li>
 * </ul>
 *
 * Returns {@code null} {@code embedUrl} for unknown hosts or malformed URLs
 * so the caller falls through to the "Watch on {host}" link treatment.
 */
function buildVideoEmbed(rawUrl: string): VideoEmbed {
  let parsed: URL;
  try {
    parsed = new URL(rawUrl);
  } catch {
    return { provider: "unknown", embedUrl: null, watchUrl: rawUrl };
  }
  const host = parsed.host.toLowerCase();
  const watchUrl = parsed.toString();

  // --- YouTube -----------------------------------------------------------
  if (host === "youtube.com" || host === "www.youtube.com") {
    const id = parsed.searchParams.get("v");
    if (id && /^[A-Za-z0-9_-]{5,20}$/.test(id)) {
      const embed = new URL(`https://www.youtube-nocookie.com/embed/${id}`);
      embed.searchParams.set("rel", "0");
      embed.searchParams.set("modestbranding", "1");
      return {
        provider: "youtube",
        embedUrl: embed.toString(),
        watchUrl,
      };
    }
    return { provider: "youtube", embedUrl: null, watchUrl };
  }
  if (host === "youtu.be") {
    const id = parsed.pathname.replace(/^\//, "").split("/")[0] ?? "";
    if (/^[A-Za-z0-9_-]{5,20}$/.test(id)) {
      const embed = new URL(`https://www.youtube-nocookie.com/embed/${id}`);
      embed.searchParams.set("rel", "0");
      embed.searchParams.set("modestbranding", "1");
      return {
        provider: "youtube",
        embedUrl: embed.toString(),
        watchUrl,
      };
    }
    return { provider: "youtube", embedUrl: null, watchUrl };
  }

  // --- Vimeo -------------------------------------------------------------
  if (host === "vimeo.com" || host === "www.vimeo.com") {
    const id = parsed.pathname.replace(/^\//, "").split("/")[0] ?? "";
    if (/^\d{5,15}$/.test(id)) {
      return {
        provider: "vimeo",
        embedUrl: `https://player.vimeo.com/video/${id}`,
        watchUrl,
      };
    }
    return { provider: "vimeo", embedUrl: null, watchUrl };
  }
  if (host === "player.vimeo.com") {
    return { provider: "vimeo", embedUrl: watchUrl, watchUrl };
  }

  // --- Unknown host ------------------------------------------------------
  return { provider: "unknown", embedUrl: null, watchUrl };
}

/**
 * Student-facing VIDEO renderer. Parses the authored URL, derives the
 * provider from the host, and mounts an iframe for the embed-friendly
 * variant. Sandbox attrs keep the embed contained: {@code allow-scripts}
 * + {@code allow-same-origin} for the player, {@code allow-presentation}
 * for fullscreen, {@code allow-popups} so "Watch on YouTube" type links
 * inside the player still work. {@code referrerpolicy} stops the embed
 * from leaking the student's session URL to the third-party origin.
 * {@code loading="lazy"} defers the request until the block scrolls into
 * view — useful on lessons with multiple videos.
 *
 * Fallbacks:
 * <ul>
 *   <li>Unknown host / unparseable URL → invalid-url notice plus a
 *       "Watch externally" button so the content is still reachable.</li>
 *   <li>Known host but unrecognised path shape (e.g. a YouTube playlist
 *       URL without a {@code v=} param) → same treatment.</li>
 * </ul>
 */
function LessonVideoView({
  resource,
  t,
}: {
  resource: LessonResource;
  t: TFn;
}) {
  const payload = resource.payload;
  const rawUrl = typeof payload["url"] === "string" ? payload["url"] : "";
  const embed = useMemo(() => buildVideoEmbed(rawUrl), [rawUrl]);

  const title = resource.title?.trim()
    ? resource.title
    : t("lessonViewer.video.defaultTitle");

  const providerLabel =
    embed.provider === "youtube"
      ? t("lessonViewer.video.providerYoutube")
      : embed.provider === "vimeo"
      ? t("lessonViewer.video.providerVimeo")
      : t("lessonViewer.video.providerUnknown");

  return (
    <article className="lesson-viewer__block lesson-viewer__block--video lesson-video-view">
      <h3 className="lesson-viewer__block-title">{title}</h3>

      {embed.embedUrl ? (
        <div className="lesson-video-view__frame-wrap">
          <iframe
            className="lesson-video-view__frame"
            src={embed.embedUrl}
            title={title}
            loading="lazy"
            referrerPolicy="strict-origin-when-cross-origin"
            allow="accelerometer; clipboard-write; encrypted-media; gyroscope; picture-in-picture; fullscreen"
            allowFullScreen
            sandbox="allow-scripts allow-same-origin allow-presentation allow-popups"
          />
        </div>
      ) : rawUrl.trim().length === 0 ? (
        <p className="lesson-video-view__invalid">
          {t("lessonViewer.video.invalidUrl")}
        </p>
      ) : (
        <p className="lesson-video-view__invalid">
          {t("lessonViewer.video.invalidUrl")}
        </p>
      )}

      <div className="lesson-video-view__meta">
        <span className="lesson-video-view__provider">
          <span className="lesson-video-view__provider-eyebrow">
            {t("lessonViewer.video.providerLabel")}
          </span>{" "}
          <span className="lesson-video-view__provider-name">
            {providerLabel}
          </span>
        </span>
        {rawUrl ? (
          <a
            href={embed.watchUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="lesson-video-view__external"
          >
            {t("lessonViewer.video.watchOn", { provider: providerLabel })}
          </a>
        ) : null}
      </div>
    </article>
  );
}

// ---------------------------------------------------------------------------
// Top-level panels — status chips + resources list
// ---------------------------------------------------------------------------

/**
 * Client-only detail panels for a single lesson as the student sees it.
 * Receives the pre-fetched detail from the server page and renders the
 * mark-as-read / mark-as-not-started / clear status chips plus the ordered
 * list of authored blocks. Revalidation happens server-side inside the
 * shared {@link updateMyLessonCompletionAction}.
 */
export function StudentLessonDetailPanels({
  detail,
  locale,
}: {
  detail: StudentLessonDetail;
  locale: string;
}) {
  const t = useTranslations("student");

  const [state, formAction, pending] = useActionState(
    updateMyLessonCompletionAction,
    INITIAL,
  );

  // Three-way: explicit status or the "no row yet" null case.
  const status = detail.status;

  // Auto-mark NOT_STARTED on first open. Lessons are revisitable study
  // material; the explicit NOT_STARTED state distinguishes "opened but
  // didn't finish" from "never touched" on the admin completion sheet.
  // The PUT is fire-and-forget — `finishLessonAction` swallows errors,
  // and a subsequent revalidatePath round-trip refreshes the chip from
  // "Unmarked" to "In progress".
  //
  // CAREFUL: don't include the lesson id or locale in deps with the
  // intention of "re-fire if they change" — they don't change for a
  // mounted detail page (the URL params are stable for this render).
  // Once `status` flips to NOT_STARTED via revalidate, the effect
  // re-runs and the gate skips. A future status change to READ via
  // the explicit chips also re-runs but skips the same way.
  useEffect(() => {
    if (status !== null && status !== undefined) return;
    const fd = new FormData();
    fd.set("lessonId", detail.id);
    fd.set("locale", locale);
    fd.set("status", "NOT_STARTED");
    void finishLessonAction(fd);
  }, [status, detail.id, locale]);

  const sortedResources = useMemo(
    () => [...detail.resources].sort((a, b) => a.position - b.position),
    [detail.resources],
  );

  // Locale-agnostic label bag for the shared walkthrough viewer. Built
  // inside the render so the next-intl `t` closure stays current.
  const walkthroughLabels: WalkthroughLabels = {
    untitled: t("lessonViewer.walkthrough.untitled"),
    invalidBlock: t("lessonViewer.walkthrough.invalidBlock"),
    sideToMove: t("lessonViewer.walkthrough.sideToMove"),
    sideWhite: t("lessonViewer.walkthrough.sideWhite"),
    sideBlack: t("lessonViewer.walkthrough.sideBlack"),
    start: t("lessonViewer.walkthrough.start"),
    prev: t("lessonViewer.walkthrough.prev"),
    next: t("lessonViewer.walkthrough.next"),
    end: t("lessonViewer.walkthrough.end"),
    plyLabel: ({ ply, total }) =>
      t("lessonViewer.walkthrough.plyLabel", { ply, total }),
    movesHeading: t("lessonViewer.walkthrough.movesHeading"),
    movesEmpty: t("lessonViewer.walkthrough.movesEmpty"),
    annotationsHeading: t("lessonViewer.walkthrough.annotationsHeading"),
    annotationAtStart: t("lessonViewer.walkthrough.annotationAtStart"),
    annotationAtPly: ({ ply }) =>
      t("lessonViewer.walkthrough.annotationAtPly", { ply }),
  };

  return (
    <section className="student-hw-detail student-lesson-detail">
      <div className="student-hw-detail__status">
        <div className="student-hw-detail__status-line">
          <span className="student-hw-detail__status-label">
            {t("lessonDetail.yourStatus")}:
          </span>
          <span
            className={
              "student-hw-detail__status-pill student-hw-detail__status-pill--" +
              // Jackson `non_null` strips the field when it's null, so `status`
              // arrives as `undefined` on the wire — use loose equality so
              // both shapes route to the "unmarked" branch. (Task #25 fix.)
              (status == null ? "unmarked" : status.toLowerCase())
            }
          >
            {t(`lessonDetail.statusPill.${status ?? "UNMARKED"}`)}
          </span>
          {detail.markedAt ? (
            <span className="student-hw-detail__submitted">
              {t("lessonDetail.markedAtLine", {
                when: formatInstant(detail.markedAt, locale),
              })}
            </span>
          ) : null}
        </div>

        <form action={formAction} className="student-hw-detail__actions">
          <input type="hidden" name="lessonId" value={detail.id} />
          <input type="hidden" name="locale" value={locale} />
          <button
            type="submit"
            name="status"
            value="READ"
            className={
              "btn btn--sm " +
              (status === "READ" ? "btn--primary" : "btn--ghost")
            }
            disabled={pending}
            aria-pressed={status === "READ"}
          >
            {t("lessonDetail.markRead")}
          </button>
          <button
            type="submit"
            name="status"
            value="NOT_STARTED"
            className={
              "btn btn--sm " +
              (status === "NOT_STARTED" ? "btn--primary" : "btn--ghost")
            }
            disabled={pending}
            aria-pressed={status === "NOT_STARTED"}
          >
            {t("lessonDetail.markNotStarted")}
          </button>
          {status != null ? (
            <button
              type="submit"
              name="status"
              value=""
              className="btn btn--ghost btn--sm"
              disabled={pending}
            >
              {t("lessonDetail.clear")}
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
            {t("lessonDetail.instructions")}
          </h3>
          <p>{detail.instructions}</p>
        </div>
      ) : null}

      <div className="lesson-viewer">
        <h3 className="student-hw-detail__heading">
          {t("lessonDetail.contentHeading")}
        </h3>
        {sortedResources.length === 0 ? (
          <p className="lesson-viewer__empty">
            {t("lessonViewer.emptyLesson")}
          </p>
        ) : (
          <div className="lesson-viewer__list">
            {sortedResources.map((r) => {
              const kind = r.kind.toUpperCase();
              let body: ReactNode;
              if (kind === "PROSE") {
                body = <LessonProseBlock resource={r} t={t} />;
              } else if (kind === "BOARD_WALKTHROUGH") {
                const wt = parseWalkthroughBlockFromWire({
                  id: r.id,
                  title: r.title,
                  payload: r.payload,
                });
                body = (
                  <LessonWalkthroughView
                    block={wt}
                    labels={walkthroughLabels}
                    idPrefix="lesson-wt"
                  />
                );
              } else if (kind === "MCQ") {
                body = <LessonMcqView resource={r} t={t} />;
              } else if (kind === "VIDEO") {
                body = <LessonVideoView resource={r} t={t} />;
              } else {
                body = (
                  <article className="lesson-viewer__block lesson-viewer__block--unknown">
                    <h3 className="lesson-viewer__block-title">
                      {r.title ?? t("lessonViewer.unknownKind")}
                    </h3>
                  </article>
                );
              }
              return (
                <Fragment key={r.id}>
                  {body}
                  <StudentBlockQa
                    parentKind="LESSON"
                    parentId={detail.id}
                    blockIndex={r.position}
                    locale={locale}
                  />
                </Fragment>
              );
            })}
          </div>
        )}
      </div>
    </section>
  );
}
