"use client";

import {
  useActionState,
  useEffect,
  useState,
  useTransition,
} from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";

import {
  fetchBlockOverlayAction,
  replyToThreadAction,
  resolveThreadAction,
  type BlockQuestionParentKind,
  type BlockQuestionThreadView,
  type QaErrorCode,
  type ReplyActionState,
} from "./qa-actions";

/**
 * Per-block QA overlay for the homework Submissions sheet.
 *
 * <p>The Q&A inbox tab on the class detail page (Task #68) shows every
 * unresolved thread across the class. This overlay is the close-up:
 * when a teacher is reviewing a specific block's attempts, it surfaces
 * any thread (open + resolved) the student opened on that block,
 * inline with the attempts list. Saves a tab-switch when the teacher
 * wants to answer a question while reviewing the work that prompted
 * it.
 *
 * <p>Lazy-fetches on first expand. Reuses the admin reply / resolve
 * actions from {@link ./qa-actions}; their `revalidatePath` already
 * refreshes the inbox tab badge so the count stays accurate after a
 * mutation lands here.
 */

type Props = {
  classId: string;
  parentKind: BlockQuestionParentKind;
  parentId: string;
  blockIndex: number;
  locale: string;
  /** Read-only mode for unassigned teachers. */
  canEdit: boolean;
};

export function BlockQaOverlay({
  classId,
  parentKind,
  parentId,
  blockIndex,
  locale,
  canEdit,
}: Props) {
  const t = useTranslations("admin.classes.qa");
  const router = useRouter();
  const [, startRefresh] = useTransition();

  const [expanded, setExpanded] = useState(false);
  const [threads, setThreads] = useState<BlockQuestionThreadView[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<QaErrorCode | null>(null);

  // Lazy-fetch on first expand. CAREFUL: keep `loading` out of the
  // dep array — see qa-inbox.tsx for the cleanup-mid-fetch race
  // explanation.
  useEffect(() => {
    if (!expanded || threads !== null || loading || loadError !== null) return;
    let cancelled = false;
    setLoading(true);
    void fetchBlockOverlayAction(classId, parentKind, parentId, blockIndex).then(
      (res) => {
        if (cancelled) return;
        setLoading(false);
        if (!res.ok) {
          setLoadError(res.error);
          return;
        }
        setThreads(res.threads);
      },
    );
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expanded, threads, loadError, classId, parentKind, parentId, blockIndex]);

  function errorCopy(code: QaErrorCode): string {
    switch (code) {
      case "invalid":
        return t("errorInvalid");
      case "unauthorised":
        return t("errorUnauthorised");
      case "forbidden":
        return t("errorForbidden");
      case "notfound":
        return t("errorNotfound");
      case "conflict":
        return t("errorConflict");
      case "network":
        return t("errorNetwork");
      default:
        return t("errorUnknown");
    }
  }

  function replaceThread(fresh: BlockQuestionThreadView) {
    setThreads((prev) =>
      prev
        ? prev.map((thr) =>
            thr.question.id === fresh.question.id ? fresh : thr,
          )
        : [fresh],
    );
    startRefresh(() => router.refresh());
  }

  // Toggle copy: "Show questions (N)" when there are any, plain
  // "Show questions" otherwise. Once expanded, also surfaces the
  // open / resolved breakdown inline.
  const totalCount = threads?.length ?? null;
  const openCount = threads?.filter((t) => !t.question.resolvedAt).length ?? null;

  return (
    <section className="hw-review-block__qa">
      <button
        type="button"
        className="hw-review-block__qa-toggle"
        aria-expanded={expanded}
        onClick={() => setExpanded((v) => !v)}
      >
        {expanded ? t("overlayHide") : t("overlayShow")}
        {totalCount !== null ? (
          <span className="hw-review-block__qa-count">
            {totalCount}
            {openCount !== null && openCount > 0 && openCount !== totalCount
              ? ` · ${t("overlayOpenSuffix", { count: openCount })}`
              : ""}
          </span>
        ) : null}
      </button>

      {expanded ? (
        <div className="hw-review-block__qa-panel">
          {loading ? (
            <p className="qa-inbox__loading">…</p>
          ) : loadError ? (
            <p className="qa-inbox__error" role="alert">
              {errorCopy(loadError)}
            </p>
          ) : threads === null || threads.length === 0 ? (
            <p className="hw-review-block__qa-empty">{t("overlayEmpty")}</p>
          ) : (
            <ul className="hw-review-block__qa-list">
              {threads.map((thread) => (
                <BlockQaThreadCard
                  key={thread.question.id}
                  classId={classId}
                  thread={thread}
                  locale={locale}
                  canEdit={canEdit}
                  onUpdated={replaceThread}
                  errorCopy={errorCopy}
                />
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </section>
  );
}

// --------------------------------------------------------------------
// Thread card — head + replies + reply form + resolve button
// --------------------------------------------------------------------

type ThreadCardProps = {
  classId: string;
  thread: BlockQuestionThreadView;
  locale: string;
  canEdit: boolean;
  onUpdated: (fresh: BlockQuestionThreadView) => void;
  errorCopy: (code: QaErrorCode) => string;
};

function BlockQaThreadCard({
  classId,
  thread,
  locale,
  canEdit,
  onUpdated,
  errorCopy,
}: ThreadCardProps) {
  const t = useTranslations("admin.classes.qa");

  const [replyState, replyAction, replyPending] = useActionState<
    ReplyActionState,
    FormData
  >(replyToThreadAction, null);

  // CAREFUL: do NOT include `onUpdated` in the dep array. The parent
  // recreates the callback on every render — including it would chain
  // setState → parent re-render → new callback ref → effect re-runs →
  // setState again → "Maximum update depth exceeded". See the same
  // fix in qa-inbox.tsx.
  useEffect(() => {
    if (replyState && "ok" in replyState && replyState.ok) {
      onUpdated(replyState.thread);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [replyState]);

  const replyError =
    replyState && "error" in replyState ? replyState.error : null;

  const [resolving, startResolve] = useTransition();
  const [resolveError, setResolveError] = useState<QaErrorCode | null>(null);

  function onResolveClick() {
    setResolveError(null);
    startResolve(async () => {
      const res = await resolveThreadAction(
        classId,
        thread.question.id,
        locale,
      );
      if (!res.ok) {
        setResolveError(res.error);
        return;
      }
      onUpdated(res.thread);
    });
  }

  const isResolved = thread.question.resolvedAt !== null;

  return (
    <li
      className={`qa-inbox__row qa-inbox__row--open${isResolved ? " hw-review-block__qa-thread--resolved" : ""}`}
    >
      <div className="qa-inbox__thread">
        <article className="qa-inbox__head-msg">
          <header className="qa-inbox__msg-head">
            <span className="qa-inbox__msg-author">
              {thread.question.studentDisplayName ?? "—"}
            </span>
            <span className="qa-inbox__msg-role qa-inbox__msg-role--student">
              {t("roleStudent")}
            </span>
            <time className="qa-inbox__msg-when">
              {formatRelative(thread.question.createdAt, locale)}
            </time>
            {isResolved ? (
              <span className="qa-inbox__resolved-badge">
                {t("resolvedBadge")}
              </span>
            ) : null}
          </header>
          <p className="qa-inbox__msg-body">{thread.question.body}</p>
        </article>

        {thread.replies.map((reply) => (
          <article
            key={reply.id}
            className={`qa-inbox__reply qa-inbox__reply--${reply.authorRole.toLowerCase()}`}
          >
            <header className="qa-inbox__msg-head">
              <span className="qa-inbox__msg-author">
                {reply.authorDisplayName ?? "—"}
              </span>
              <span
                className={`qa-inbox__msg-role qa-inbox__msg-role--${reply.authorRole.toLowerCase()}`}
              >
                {reply.authorRole === "STUDENT"
                  ? t("roleStudent")
                  : t("roleTeacher")}
              </span>
              <time className="qa-inbox__msg-when">
                {formatRelative(reply.createdAt, locale)}
              </time>
            </header>
            <p className="qa-inbox__msg-body">{reply.body}</p>
          </article>
        ))}

        {canEdit && !isResolved ? (
          <form className="qa-inbox__reply-form" action={replyAction}>
            <input type="hidden" name="classId" value={classId} />
            <input
              type="hidden"
              name="questionId"
              value={thread.question.id}
            />
            <input type="hidden" name="locale" value={locale} />

            <label className="qa-inbox__reply-label">
              <span className="qa-inbox__reply-label-text">
                {t("replyLabel")}
              </span>
              <textarea
                className="qa-inbox__reply-input"
                name="body"
                rows={2}
                placeholder={t("replyPlaceholder")}
                maxLength={2000}
                required
                disabled={replyPending}
              />
            </label>

            {replyError ? (
              <p className="qa-inbox__error" role="alert">
                {errorCopy(replyError)}
              </p>
            ) : null}

            <div className="qa-inbox__actions">
              <button
                type="button"
                className="btn btn--ghost"
                onClick={onResolveClick}
                disabled={resolving || replyPending}
              >
                {resolving
                  ? t("resolveButtonResolving")
                  : t("resolveButton")}
              </button>
              <button
                type="submit"
                className="btn btn--primary"
                disabled={replyPending}
              >
                {replyPending ? t("replySubmitting") : t("replySubmit")}
              </button>
            </div>
            {resolveError ? (
              <p className="qa-inbox__error" role="alert">
                {errorCopy(resolveError)}
              </p>
            ) : null}
          </form>
        ) : null}

        {isResolved ? (
          <p className="qa-inbox__resolved-meta">
            {thread.question.resolvedByDisplayName
              ? t("resolvedMetaBy", {
                  when: formatRelative(thread.question.resolvedAt!, locale),
                  by: thread.question.resolvedByDisplayName,
                })
              : t("resolvedMeta", {
                  when: formatRelative(thread.question.resolvedAt!, locale),
                })}
          </p>
        ) : null}
      </div>
    </li>
  );
}

// --------------------------------------------------------------------
// Helpers
// --------------------------------------------------------------------

function formatRelative(iso: string, locale: string): string {
  const ts = Date.parse(iso);
  if (Number.isNaN(ts)) return iso;
  const now = Date.now();
  const diffSec = Math.max(0, Math.round((now - ts) / 1000));

  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  if (diffSec < 60) return rtf.format(-diffSec, "second");
  const diffMin = Math.round(diffSec / 60);
  if (diffMin < 60) return rtf.format(-diffMin, "minute");
  const diffHr = Math.round(diffMin / 60);
  if (diffHr < 24) return rtf.format(-diffHr, "hour");
  const diffDay = Math.round(diffHr / 24);
  if (diffDay < 7) return rtf.format(-diffDay, "day");

  return new Intl.DateTimeFormat(locale, {
    timeZone: "Asia/Tashkent",
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(ts));
}
