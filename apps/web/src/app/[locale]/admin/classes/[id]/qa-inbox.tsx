"use client";

import {
  useActionState,
  useCallback,
  useEffect,
  useState,
  useTransition,
} from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";

import {
  fetchThreadAction,
  replyToThreadAction,
  resolveThreadAction,
  type BlockQuestionInboxRow,
  type BlockQuestionThreadView,
  type QaErrorCode,
  type ReplyActionState,
} from "./qa-actions";

/**
 * Q&A inbox panel mounted under the {@code ?tab=qa} slug on the class
 * detail page.
 *
 * <p>Server hands us the inbox preview (one batch fetch in
 * {@code page.tsx}); the panel renders that as a list of collapsed
 * thread rows. Expanding a row triggers a fetch for the full reply
 * chain, which lives in client state. Reply / resolve mutations
 * splice the fresh thread into the same client state for instant
 * repaint, and {@code router.refresh()} runs in a transition so the
 * server's preview list (and the tab badge count) revalidate in
 * the background.
 *
 * <p>Inbox preview returns unresolved-only — that's by design on the
 * backend. v1 scope: no toggle for resolved threads; that lands when
 * a separate "resolved archive" view is filed.
 */

type FormatLocale = string;

type Props = {
  classId: string;
  locale: FormatLocale;
  rows: BlockQuestionInboxRow[];
  /** Server-side load failure — non-null shows an error banner. */
  loadError?: QaErrorCode | null;
  /**
   * Whether the caller can mutate (reply / resolve). Mirrors the
   * other class-detail panels' read-only mode for unassigned
   * teachers; the backend re-checks on write.
   */
  canEdit: boolean;
};

export function QaInboxPanel({
  classId,
  locale,
  rows,
  loadError = null,
  canEdit,
}: Props) {
  const t = useTranslations("admin.classes.qa");
  const router = useRouter();
  const [, startRefresh] = useTransition();

  // Optimistic mirror of inbox rows. We splice on resolve so the row
  // disappears immediately; reply mutations refresh the row's reply
  // count and last-reply meta in place.
  const [liveRows, setLiveRows] = useState<BlockQuestionInboxRow[]>(rows);
  // Re-sync if the parent's prop changes (e.g. revalidatePath landed).
  useEffect(() => {
    setLiveRows(rows);
  }, [rows]);

  // Per-row expanded state + cached thread.
  const [openId, setOpenId] = useState<string | null>(null);

  const refresh = useCallback(() => {
    startRefresh(() => router.refresh());
  }, [router]);

  return (
    <section className="qa-inbox">
      <header className="qa-inbox__head">
        <h2 className="qa-inbox__title">{t("title")}</h2>
        <p className="qa-inbox__subtitle">{t("subtitle")}</p>
      </header>

      {loadError ? (
        <div className="qa-inbox__error" role="alert">
          {t("loadError")}
        </div>
      ) : null}

      {liveRows.length === 0 ? (
        <div className="qa-inbox__empty">{t("empty")}</div>
      ) : (
        <ul className="qa-inbox__list">
          {liveRows.map((row) => (
            <QaInboxRow
              key={row.question.id}
              classId={classId}
              locale={locale}
              row={row}
              expanded={openId === row.question.id}
              onToggle={() =>
                setOpenId((cur) => (cur === row.question.id ? null : row.question.id))
              }
              canEdit={canEdit}
              onResolved={() => {
                // Drop the resolved row from the inbox preview.
                setLiveRows((prev) =>
                  prev.filter((r) => r.question.id !== row.question.id),
                );
                setOpenId(null);
                refresh();
              }}
              onRepliesUpdated={(thread) => {
                // Update the row's reply count + last-reply meta in
                // place so the collapsed preview stays accurate.
                setLiveRows((prev) =>
                  prev.map((r) =>
                    r.question.id === row.question.id
                      ? {
                          ...r,
                          replyCount: thread.replies.length,
                          lastReplyAt:
                            thread.replies.length === 0
                              ? null
                              : (thread.replies[thread.replies.length - 1]?.createdAt ?? null),
                          lastReplyAuthorDisplayName:
                            thread.replies.length === 0
                              ? null
                              : (thread.replies[thread.replies.length - 1]
                                  ?.authorDisplayName ?? null),
                        }
                      : r,
                  ),
                );
                refresh();
              }}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

// --------------------------------------------------------------------
// Per-row component
// --------------------------------------------------------------------

type RowProps = {
  classId: string;
  locale: FormatLocale;
  row: BlockQuestionInboxRow;
  expanded: boolean;
  onToggle: () => void;
  canEdit: boolean;
  onResolved: () => void;
  onRepliesUpdated: (thread: BlockQuestionThreadView) => void;
};

function QaInboxRow({
  classId,
  locale,
  row,
  expanded,
  onToggle,
  canEdit,
  onResolved,
  onRepliesUpdated,
}: RowProps) {
  const t = useTranslations("admin.classes.qa");

  // Lazy-fetched full thread (head + replies). null until the user
  // expands the row at least once.
  const [thread, setThread] = useState<BlockQuestionThreadView | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadErr, setLoadErr] = useState<QaErrorCode | null>(null);
  const [, startRefresh] = useTransition();

  // Lazy-fetch the full thread on expand.
  //
  // CAREFUL: do NOT include `loading` in the dep array. `setLoading(true)`
  // inside the effect would otherwise trigger a cleanup mid-fetch that
  // flips `cancelled = true` before the response lands, leaving the
  // panel stuck on the "…" indicator forever. Same gotcha as the
  // student QA panel; keep the gate but drop the dep.
  useEffect(() => {
    if (!expanded || thread !== null || loading || loadErr !== null) return;
    let cancelled = false;
    setLoading(true);
    void fetchThreadAction(classId, row.question.id).then((res) => {
      if (cancelled) return;
      setLoading(false);
      if (!res.ok) {
        setLoadErr(res.error);
        return;
      }
      setThread(res.thread);
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expanded, thread, loadErr, classId, row.question.id]);

  // Reply form. useActionState yields the latest result; we splice
  // any returned thread into local state so the UI repaints.
  const [replyState, replyAction, replyPending] = useActionState<
    ReplyActionState,
    FormData
  >(replyToThreadAction, null);

  // CAREFUL: do NOT include `onRepliesUpdated` in the dep array. The
  // parent recreates that callback on every render (it's an inline
  // arrow). If we react to it, the chain becomes:
  //   reply OK → setThread + onRepliesUpdated → parent re-renders →
  //   new callback reference → effect re-runs → setThread again →
  //   infinite loop ("Maximum update depth exceeded").
  // We only want to react to `replyState` flipping to ok=true — the
  // callback identity is irrelevant here.
  useEffect(() => {
    if (replyState && "ok" in replyState && replyState.ok) {
      setThread(replyState.thread);
      onRepliesUpdated(replyState.thread);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [replyState]);

  const replyError =
    replyState && "error" in replyState ? replyState.error : null;

  // Resolve handler — separate transition because resolve isn't a form.
  const [resolving, startResolve] = useTransition();
  const [resolveError, setResolveError] = useState<QaErrorCode | null>(null);

  function onResolveClick() {
    setResolveError(null);
    startResolve(async () => {
      const res = await resolveThreadAction(classId, row.question.id, locale);
      if (!res.ok) {
        setResolveError(res.error);
        return;
      }
      // Push the resolved thread back upstream so the inbox can
      // collapse the row + run router.refresh() on the parent.
      onResolved();
    });
  }

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

  return (
    <li
      className={`qa-inbox__row${expanded ? " qa-inbox__row--open" : ""}`}
    >
      <button
        type="button"
        className="qa-inbox__row-head"
        aria-expanded={expanded}
        aria-controls={`qa-thread-${row.question.id}`}
        onClick={onToggle}
      >
        <div className="qa-inbox__row-meta">
          <span className="qa-inbox__row-parent">
            {row.question.parentKind === "HOMEWORK"
              ? t("parentHomework")
              : t("parentLesson")}
            {row.parentTitle ? ` · ${row.parentTitle}` : ""}
            {" · "}
            {t("blockLabel", { index: row.question.blockIndex + 1 })}
          </span>
          <span className="qa-inbox__row-asker">
            {t("askedBy", { name: row.question.studentDisplayName ?? "—" })}
          </span>
        </div>
        <div className="qa-inbox__row-snippet">
          {truncate(row.question.body, 200)}
        </div>
        <div className="qa-inbox__row-counts">
          <span className="qa-inbox__row-count">
            {t("replyCount", { count: row.replyCount })}
          </span>
          {row.lastReplyAt ? (
            <span className="qa-inbox__row-last">
              {t("lastReplyBy", {
                when: formatRelative(row.lastReplyAt, locale),
                author: row.lastReplyAuthorDisplayName ?? "—",
              })}
            </span>
          ) : null}
        </div>
        <span className="qa-inbox__row-toggle" aria-hidden="true">
          {expanded ? t("collapse") : t("expand")}
        </span>
      </button>

      {expanded ? (
        <div
          id={`qa-thread-${row.question.id}`}
          className="qa-inbox__thread"
          role="region"
        >
          {loading ? (
            <p className="qa-inbox__loading">…</p>
          ) : loadErr ? (
            <p className="qa-inbox__error" role="alert">
              {errorCopy(loadErr)}
            </p>
          ) : thread ? (
            <>
              <article className="qa-inbox__head-msg">
                <header className="qa-inbox__msg-head">
                  <span className="qa-inbox__msg-author">
                    {row.question.studentDisplayName ?? "—"}
                  </span>
                  <span className="qa-inbox__msg-role qa-inbox__msg-role--student">
                    {t("roleStudent")}
                  </span>
                  <time className="qa-inbox__msg-when">
                    {formatRelative(row.question.createdAt, locale)}
                  </time>
                </header>
                <p className="qa-inbox__msg-body">{row.question.body}</p>
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

              {canEdit && !thread.question.resolvedAt ? (
                <form
                  className="qa-inbox__reply-form"
                  action={replyAction}
                >
                  <input type="hidden" name="classId" value={classId} />
                  <input
                    type="hidden"
                    name="questionId"
                    value={row.question.id}
                  />
                  <input type="hidden" name="locale" value={locale} />

                  <label className="qa-inbox__reply-label">
                    <span className="qa-inbox__reply-label-text">
                      {t("replyLabel")}
                    </span>
                    <textarea
                      className="qa-inbox__reply-input"
                      name="body"
                      rows={3}
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

              {thread.question.resolvedAt ? (
                <p className="qa-inbox__resolved-meta">
                  {thread.question.resolvedByDisplayName
                    ? t("resolvedMetaBy", {
                        when: formatRelative(thread.question.resolvedAt, locale),
                        by: thread.question.resolvedByDisplayName,
                      })
                    : t("resolvedMeta", {
                        when: formatRelative(thread.question.resolvedAt, locale),
                      })}
                </p>
              ) : null}
            </>
          ) : null}
        </div>
      ) : null}
    </li>
  );
}

// --------------------------------------------------------------------
// Helpers
// --------------------------------------------------------------------

function truncate(text: string, max: number): string {
  if (text.length <= max) return text;
  return text.slice(0, max - 1) + "…";
}

/**
 * Render an instant as a short relative phrase (e.g. "2 h ago", "3 d ago").
 * Falls back to a short Intl-formatted date if the row is older than a week.
 */
function formatRelative(iso: string, locale: FormatLocale): string {
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
