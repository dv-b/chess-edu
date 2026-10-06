import type { ReactNode } from "react";
import { notFound, redirect } from "next/navigation";
import { setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import { routing, type AppLocale } from "@/i18n/routing";
import { StudentSidebar } from "./sidebar";

type MeResponse = {
  userId: string;
  email: string;
  displayName: string;
  preferredLocale: string;
  role: string | null;
  platformRole: string | null;
};

type StudentHomeworkRow = {
  id: string;
  status: "NOT_STARTED" | "DONE" | "SKIPPED" | null;
};

type StudentLessonRow = {
  id: string;
  /**
   * {@code null} means the student hasn't interacted with the lesson yet.
   * Treated as "unread" for the badge count — matches the backend's
   * {@code null}/{@code NOT_STARTED}/{@code READ} semantics.
   */
  status: "NOT_STARTED" | "READ" | null;
};

/**
 * Slim shape from the cross-class questions rollup
 * ({@code GET /api/v1/me/questions}). The endpoint actually returns
 * full thread views, but we only need the question's resolution
 * status to compute the badge — pulling the rest into our type would
 * be noise.
 */
type StudentQaThreadRow = {
  question: { resolvedAt: string | null };
};

/**
 * Auth-gated shell for every `/student/*` route. Renders the Kingside
 * sidebar once for the whole section, so the detail page and any future
 * /student surface share the persistent nav. The `<main>` column receives
 * {children} — each page decides its own header / content.
 *
 * The role gate still lives on the individual pages (they call `/auth/me`
 * and bounce non-STUDENT roles to `/admin`); the layout only enforces
 * "is this request authenticated?". Keeping the role check page-side
 * means a future parent-of-student dashboard can live alongside without
 * a second auth round-trip here.
 */
export default async function StudentLayout({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  if (!routing.locales.includes(locale as AppLocale)) notFound();
  setRequestLocale(locale);

  const access = await getAccessToken();
  if (!access) {
    redirect(`/${locale}/auth/login`);
  }

  const meRes = await apiFetch<MeResponse>("/api/v1/auth/me", {
    accessToken: access,
  });
  if (!meRes.ok) {
    redirect(`/api/session/logout?next=/${locale}/auth/login`);
  }
  const me = meRes.data;

  // Sidebar badge counts run in parallel — the three fetches are
  // independent and every student-facing page needs all of them. All
  // three are silent on failure: the count badge simply hides if we
  // can't reach the relevant endpoint.
  //
  //  - homeworkCount  = everything the student hasn't self-marked DONE yet.
  //    A null status counts as unfinished (matches the row rollup on the
  //    list page, which treats nullish as "not started").
  //  - lessonCount    = lessons the student hasn't marked READ. An explicit
  //    NOT_STARTED from the opened-but-didn't-finish trail still counts as
  //    unread because the badge is about "stuff waiting on you", not
  //    "stuff you've opened". See StudentLessonView javadoc for the shape.
  //  - questionsCount = unresolved Q&A threads opened by the student
  //    across every class. Resolved threads (whether closed by the
  //    teacher or self-resolved) drop off the badge — same shape as
  //    the admin inbox tab badge on the class detail page. Doesn't
  //    track "unread teacher replies" — that needs a per-thread
  //    last_read_at column we don't have yet (deliberately scoped out
  //    in #73).
  const [homeworkListRes, lessonListRes, qaListRes] = await Promise.all([
    apiFetch<StudentHomeworkRow[]>("/api/v1/me/homework", {
      accessToken: access,
    }),
    apiFetch<StudentLessonRow[]>("/api/v1/me/lessons", {
      accessToken: access,
    }),
    apiFetch<StudentQaThreadRow[]>("/api/v1/me/questions", {
      accessToken: access,
    }),
  ]);
  const homeworkCount = homeworkListRes.ok
    ? homeworkListRes.data.filter(
        (h) => (h.status ?? "NOT_STARTED") !== "DONE",
      ).length
    : 0;
  const lessonCount = lessonListRes.ok
    ? lessonListRes.data.filter((l) => l.status !== "READ").length
    : 0;
  const questionsCount = qaListRes.ok
    ? qaListRes.data.filter((t) => t.question.resolvedAt === null).length
    : 0;

  return (
    <div className="student-shell">
      <StudentSidebar
        me={me}
        locale={locale}
        homeworkCount={homeworkCount}
        lessonCount={lessonCount}
        questionsCount={questionsCount}
      />
      <main className="student-main">{children}</main>
    </div>
  );
}
