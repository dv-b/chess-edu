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
  fetchMyBlockThreadsAction,
  openMyThreadAction,
  replyMyThreadAction,
  resolveMyThreadAction,
  type OpenThreadActionState,
  type ReplyMyThreadActionState,
  type StudentQaError,
  type StudentQaParentKind,
  type StudentQaThread,
} from "./qa-actions";

/**
 * Per-block "Ask teacher" affordance for the student's homework /
 * lesson detail pages. Mounts at the bottom of each block; collapsed
 * by default to keep the page clean. On expand, lazy-fetches the
 * student's existing threads on this block (open + resolved, oldest
 * first) and renders:
 *
 * <ul>
 *   <li>Each existing thread — student message + ordered replies
 *       (role-coloured the same way as the admin inbox), plus a
 *       reply form + "Mark resolved" button when the thread is
 *       still open.</li>
 *   <li>An "Ask another question" form below the threads — always
 *       visible so the student can open as many threads as they
 *       want. Multi-thread per block is supported by the schema +
 *       backend; v1 UX doesn't gate it.</li>
 * </ul>
 *
 * <p>Optimistic local state splices fresh threads from the action
 * results so the UI repaints without a refetch; the actions also
 * `revalidatePath` the parent detail page so any stale neighbouring
 * surface picks up the change on next nav.
 */

type Props = {
  parentKind: StudentQaParentKind;
  parentId: string;
  blockIndex: number;
  locale: string;
};

export function StudentBlockQa({ parentKind, parentId, blockIndex, locale }: Props) {
  const t = useTranslations("student.qa");
  const router = useRouter();
  const [, startRefresh] = useTransition();

  const [expanded, setExpanded] = useState(false);
  const [threads, setThreads] = useState<StudentQaThread[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<StudentQaError | null>(null);

  // Lazy-fetch threads on first expand.
  //
  // CAREFUL: do NOT include `loading` in the dep array. If you do,
  // calling `setLoading(true)` synchronously inside the effect kicks
  // a re-render whose cleanup flips the local `cancelled` flag to
  // true — the in-flight `.then` then bails instead of writing the
  // result, and the panel stays stuck on the "…" indicator forever.
  // The loading-flag check in the gate prevents duplicate fetches
  // when `expanded` toggles rapidly; we just shouldn't react to the
  // flag changing.
  useEffect(() => {
    if (!expanded || threads !== null || loading || loadError !== null) return;
    let cancelled = false;
    setLoading(true);
    void fetchMyBlockThreadsAction(parentKind, parentId, blockIndex).then(
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
  }, [expanded, threads, loadError, parentKind, parentId, blockIndex]);

  // New-thread form. On success, append the new thread to the list +
  // refresh so any other surface picks up the count.
  const [openState, openAction, openPending] = useActionState<
    OpenThreadActionState,
    FormData
  >(openMyThreadAction, null);

  useEffect(() => {
    if (openState && "ok" in openState && openState.ok) {
      setThreads((prev) => (prev ?? []).concat(openState.thread));
      startRefresh(() => router.refresh());
    }
  }, [openState, router]);

  const openError = openState && "error" in openState ? openState.error : null;

  const replaceThread = useCallback((fresh: StudentQaThread) => {
    setThreads((prev) =>
      prev
        ? prev.map((t) => (t.question.id === fresh.question.id ? fresh : t))
        : [fresh],
    );
    startRefresh(() => router.refresh());
  }, [router]);

  function errorCopy(code: StudentQaError): string {
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
    <section className="student-qa">
      <button
        type="button"
        className="student-qa__toggle"
        aria-expanded={expanded}
        onClick={() => setExpanded((v) => !v)}
      >
        {expanded ? t("hide") : t("ask")}
      </button>

      {expanded ? (
        <div className="student-qa__panel">
          {loading ? (
            <p className="student-qa__loading">…</p>
          ) : loadError ? (
            <p className="student-qa__error" role="alert">
              {errorCopy(loadError)}
            </p>
          ) : (
            <>
              {threads && threads.length > 0 ? (
                <ul className="student-qa__threads">
                  {threads.map((thread) => (
                    <StudentQaThreadCard
                      key={thread.question.id}
                      thread={thread}
                      parentKind={parentKind}
                      parentId={parentId}
                      locale={locale}
                      onReplied={replaceThread}
                      errorCopy={errorCopy}
                    />
                  ))}
                </ul>
              ) : null}

              <form className="student-qa__new" action={openAction}>
                <input type="hidden" name="parentKind" value={parentKind} />
                <input type="hidden" name="parentId" value={parentId} />
                <input
                  type="hidden"
                  name="blockIndex"
                  value={String(blockIndex)}
                />
                <input type="hidden" name="locale" value={locale} />

                <label className="student-qa__field">
                  <span className="student-qa__field-label">
                    {threads && threads.length > 0
                      ? t("anotherLabel")
                      : t("firstLabel")}
                  </span>
                  <textarea
                    className="student-qa__input"
                    name="body"
                    rows={3}
                    placeholder={t("placeholder")}
                    maxLength={2000}
                    required
                    disabled={openPending}
                  />
                </label>

                {openError ? (
                  <p className="student-qa__error" role="alert">
                    {errorCopy(openError)}
                  </p>
                ) : null}

                <div className="student-qa__actions">
                  <button
                    type="submit"
                    className="btn btn--primary"
                    disabled={openPending}
                  >
                    {openPending ? t("openSubmitting") : t("openSubmit")}
                  </button>
                </div>
              </form>
            </>
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
  thread: StudentQaThread;
  parentKind: StudentQaParentKind;
  parentId: string;
  locale: string;
  onReplied: (fresh: StudentQaThread) => void;
  errorCopy: (code: StudentQaError) => string;
};

function StudentQaThreadCard({
  thread,
  parentKind,
  parentId,
  locale,
  onReplied,
  errorCopy,
}: ThreadCardProps) {
  const t = useTranslations("student.qa");
  const router = useRouter();
  const [, startRefresh] = useTransition();

  const [replyState, replyAction, replyPending] = useActionState<
    ReplyMyThreadActionState,
    FormData
  >(replyMyThreadAction, null);

  // CAREFUL: do NOT include `onReplied` in the dep array. The parent
  // recreates the callback on every render — including it would chain
  // setState → parent re-render → new callback ref → effect re-runs →
  // setState again → "Maximum update depth exceeded". See the same
  // fix in qa-inbox.tsx.
  useEffect(() => {
    if (replyState && "ok" in replyState && replyState.ok) {
      onReplied(replyState.thread);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [replyState]);

  const replyError =
    replyState && "error" in replyState ? replyState.error : null;

  const [resolving, startResolve] = useTransition();
  const [resolveError, setResolveError] = useState<StudentQaError | null>(null);

  function onResolveClick() {
    setResolveError(null);
    startResolve(async () => {
      const res = await resolveMyThreadAction(
        thread.question.id,
        parentKind,
        parentId,
        locale,
      );
      if (!res.ok) {
        setResolveError(res.error);
        return;
      }
      onReplied(res.thread);
      startRefresh(() => router.refresh());
    });
  }

  const isResolved = thread.question.resolvedAt !== null;

  return (
    <li className={`student-qa__thread${isResolved ? " student-qa__thread--resolved" : ""}`}>
      <header className="student-qa__thread-head">
        <span className="student-qa__msg-when">
          {formatRelative(thread.question.createdAt, locale)}
        </span>
        {isResolved ? (
          <span className="student-qa__resolved-badge">
            {t("resolvedBadge")}
          </span>
        ) : null}
      </header>

      <article className="student-qa__msg">
        <header className="student-qa__msg-meta">
          <span className="student-qa__msg-role student-qa__msg-role--student">
            {t("roleStudent")}
          </span>
        </header>
        <p className="student-qa__msg-body">{thread.question.body}</p>
      </article>

      {thread.replies.map((reply) => (
        <article
          key={reply.id}
          className={`student-qa__msg student-qa__msg--reply student-qa__msg--${reply.authorRole.toLowerCase()}`}
        >
          <header className="student-qa__msg-meta">
            <span
              className={`student-qa__msg-role student-qa__msg-role--${reply.authorRole.toLowerCase()}`}
            >
              {reply.authorRole === "STUDENT"
                ? t("roleStudent")
                : t("roleTeacher")}
            </span>
            <span className="student-qa__msg-author">
              {reply.authorDisplayName ?? "—"}
            </span>
            <time className="student-qa__msg-when">
              {formatRelative(reply.createdAt, locale)}
            </time>
          </header>
          <p className="student-qa__msg-body">{reply.body}</p>
        </article>
      ))}

      {!isResolved ? (
        <form className="student-qa__reply-form" action={replyAction}>
          <input type="hidden" name="questionId" value={thread.question.id} />
          <input type="hidden" name="parentKind" value={parentKind} />
          <input type="hidden" name="parentId" value={parentId} />
          <input type="hidden" name="locale" value={locale} />

          <label className="student-qa__field">
            <span className="student-qa__field-label">{t("replyLabel")}</span>
            <textarea
              className="student-qa__input"
              name="body"
              rows={2}
              placeholder={t("placeholder")}
              maxLength={2000}
              required
              disabled={replyPending}
            />
          </label>

          {replyError ? (
            <p className="student-qa__error" role="alert">
              {errorCopy(replyError)}
            </p>
          ) : null}

          <div className="student-qa__actions">
            <button
              type="button"
              className="btn btn--ghost"
              onClick={onResolveClick}
              disabled={resolving || replyPending}
            >
              {resolving ? t("resolveButtonResolving") : t("resolveButton")}
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
            <p className="student-qa__error" role="alert">
              {errorCopy(resolveError)}
            </p>
          ) : null}
        </form>
      ) : (
        <p className="student-qa__resolved-meta">
          {t("resolvedNotice")}
        </p>
      )}
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
