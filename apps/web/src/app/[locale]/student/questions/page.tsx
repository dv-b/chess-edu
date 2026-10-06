import { redirect } from "next/navigation";
import { Link } from "@/i18n/routing";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import type { StudentQaThread } from "../qa-actions";

/**
 * Student cross-class "Questions" rollup (Lane B / B4).
 *
 * <p>Lists every thread the student has opened, newest first, with a
 * compact preview (parent kind + title, block index, question
 * snippet, reply count, last-reply meta, status pill). Each row links
 * to the parent's detail page where the inline per-block QA panel
 * (Task #69) handles reply / resolve. The rollup is read-only — no
 * write actions on this page; it's purely an aggregator.
 */

type MeResponse = {
  userId: string;
  email: string;
  displayName: string;
  preferredLocale: string;
  role: string | null;
  platformRole: string | null;
};

type PageProps = {
  params: Promise<{ locale: string }>;
};

export default async function StudentQuestionsPage({ params }: PageProps) {
  const { locale } = await params;
  setRequestLocale(locale);

  const access = await getAccessToken();
  if (!access) redirect(`/${locale}/auth/login`);

  const meRes = await apiFetch<MeResponse>("/api/v1/auth/me", {
    accessToken: access,
  });
  if (!meRes.ok) {
    redirect(`/api/session/logout?next=/${locale}/auth/login`);
  }
  const me = meRes.data;
  if (me.role !== "STUDENT") {
    redirect(`/${locale}/admin`);
  }

  const listRes = await apiFetch<StudentQaThread[]>("/api/v1/me/questions", {
    accessToken: access,
  });
  if (
    !listRes.ok &&
    (listRes.error.status === 401 || listRes.error.status === 403)
  ) {
    redirect(`/api/session/logout?next=/${locale}/auth/login`);
  }
  const threads = listRes.ok ? listRes.data : [];
  const loadFailed = !listRes.ok;

  const t = await getTranslations("student");
  const openCount = threads.filter((th) => th.question.resolvedAt === null).length;

  return (
    <>
      <header className="student-main__head">
        <div className="student-main__head-copy">
          <h1 className="student-main__title">{t("questionsList.title")}</h1>
          <p className="student-main__sub">
            {t("questionsList.subAll", { count: threads.length })}
            {threads.length > 0 ? ` · ${t("questionsList.subOpen", { count: openCount })}` : ""}
          </p>
        </div>
      </header>

      {loadFailed ? (
        <div className="form-error" role="alert">
          {t("questionsList.loadError")}
        </div>
      ) : null}

      {threads.length === 0 ? (
        <div className="hw-empty">
          <p className="hw-empty__copy">{t("questionsList.empty")}</p>
        </div>
      ) : (
        <ul className="student-questions-list">
          {threads.map((thread) => {
            const q = thread.question;
            const replyCount = thread.replies.length;
            const lastReply =
              replyCount > 0 ? thread.replies[replyCount - 1] : null;
            const isResolved = q.resolvedAt !== null;
            const parentHref =
              q.parentKind === "HOMEWORK"
                ? `/student/homework/${q.parentId}`
                : `/student/lessons/${q.parentId}`;
            const parentLabel =
              q.parentKind === "HOMEWORK"
                ? t("qa.parentHomework")
                : t("qa.parentLesson");
            const parentTitle = thread.parentTitle ?? "—";

            return (
              <li
                key={q.id}
                className={
                  "student-questions-row" +
                  (isResolved ? " student-questions-row--resolved" : "")
                }
              >
                <Link href={parentHref} className="student-questions-row__link">
                  <div className="student-questions-row__head">
                    <span className="student-questions-row__parent">
                      {parentLabel} · {parentTitle} ·{" "}
                      {t("qa.blockLabel", { index: q.blockIndex + 1 })}
                    </span>
                    <span
                      className={
                        "student-questions-row__pill student-questions-row__pill--" +
                        (isResolved ? "resolved" : "open")
                      }
                    >
                      {isResolved
                        ? t("qa.resolvedBadge")
                        : t("questionsList.openBadge")}
                    </span>
                  </div>
                  <p className="student-questions-row__body">
                    {truncate(q.body, 160)}
                  </p>
                  <div className="student-questions-row__meta">
                    <span className="student-questions-row__meta-item">
                      {t("questionsList.askedAt", {
                        when: formatRelative(q.createdAt, locale),
                      })}
                    </span>
                    <span className="student-questions-row__meta-item">
                      {t("qa.replyCount", { count: replyCount })}
                    </span>
                    {lastReply ? (
                      <span className="student-questions-row__meta-item">
                        {t("questionsList.lastReply", {
                          when: formatRelative(lastReply.createdAt, locale),
                          author: lastReply.authorDisplayName ?? "—",
                        })}
                      </span>
                    ) : null}
                  </div>
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </>
  );
}

// --------------------------------------------------------------------
// Helpers
// --------------------------------------------------------------------

function truncate(text: string, max: number): string {
  if (text.length <= max) return text;
  return text.slice(0, max - 1) + "…";
}

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
