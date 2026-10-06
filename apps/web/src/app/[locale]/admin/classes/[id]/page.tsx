import { redirect } from "next/navigation";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import { Link } from "@/i18n/routing";
import { RosterPanels } from "./roster-panels";
import { ClassStatusForm } from "./status-form";
import {
  SchedulePanels,
  type SchedulePattern,
  type ScheduleSession,
} from "./schedule-panels";
import { HomeworkPanels } from "./homework-panels";
import type { HomeworkSummary } from "./homework-actions";
import { LessonPanels } from "./lesson-panels";
import type { LessonSummary } from "./lesson-actions";
import { ClassDetailSubNav } from "./sub-nav";
import { normaliseClassDetailTab, type ClassDetailTab } from "./tabs";
import { ClassOverview } from "./class-overview";
import { CalendarStrip } from "./calendar-strip";
import { QaInboxPanel } from "./qa-inbox";
import type { BlockQuestionInboxRow, QaErrorCode } from "./qa-actions";
import { countThisWeek } from "@/lib/class-detail-buckets";

type RosterMember = {
  userId: string;
  email: string;
  displayName: string;
};

type ClassDetail = {
  id: string;
  schoolId: string;
  name: string;
  description: string | null;
  level: "BEGINNER" | "INTERMEDIATE" | "ADVANCED" | null;
  status: "ACTIVE" | "ARCHIVED";
  teachers: RosterMember[];
  students: RosterMember[];
  createdAt: string;
  updatedAt: string;
};

type MemberView = {
  userId: string;
  email: string;
  displayName: string;
  role: string;
  membershipStatus: string;
};

type MeResponse = {
  userId: string;
  role: string | null;
  platformRole: string | null;
};

type PageProps = {
  params: Promise<{ locale: string; id: string }>;
  searchParams: Promise<{ tab?: string | string[] }>;
};

/**
 * Detail view for a single class. The server component gathers everything
 * the page needs in a single pass: the class itself, the active teachers /
 * students in the school (for the add dropdowns), recurring schedule
 * patterns, upcoming sessions, and the caller's role (to decide whether
 * to render edit controls).
 *
 * <p>We call {@code /auth/me} to discover the role rather than passing it
 * down from the dashboard, because a teacher might arrive here via a direct
 * URL / bookmark.
 */
export default async function ClassDetailPage({ params, searchParams }: PageProps) {
  const { locale, id } = await params;
  const sp = await searchParams;
  const activeTab: ClassDetailTab = normaliseClassDetailTab(sp.tab);
  setRequestLocale(locale);

  const access = await getAccessToken();
  if (!access) redirect(`/${locale}/auth/login`);

  const [
    detailRes,
    teacherRes,
    studentRes,
    patternsRes,
    sessionsRes,
    homeworkRes,
    lessonsRes,
    meRes,
    qaRes,
  ] = await Promise.all([
    apiFetch<ClassDetail>(`/api/v1/admin/classes/${id}`, { accessToken: access }),
    apiFetch<MemberView[]>("/api/v1/admin/members?role=TEACHER", {
      accessToken: access,
    }),
    apiFetch<MemberView[]>("/api/v1/admin/members?role=STUDENT", {
      accessToken: access,
    }),
    apiFetch<SchedulePattern[]>(`/api/v1/admin/classes/${id}/patterns`, {
      accessToken: access,
    }),
    apiFetch<ScheduleSession[]>(
      `/api/v1/admin/classes/${id}/sessions?upcomingOnly=true`,
      { accessToken: access },
    ),
    // Pull archived rows too — the panel splits them client-side so admins
    // can flip the "show archived" toggle without a round-trip.
    apiFetch<HomeworkSummary[]>(
      `/api/v1/admin/classes/${id}/homework?includeArchived=true`,
      { accessToken: access },
    ),
    // Same pattern for lessons: one fetch including archived, split locally.
    apiFetch<LessonSummary[]>(
      `/api/v1/admin/classes/${id}/lessons?includeArchived=true`,
      { accessToken: access },
    ),
    apiFetch<MeResponse>("/api/v1/auth/me", { accessToken: access }),
    // Q&A inbox — unresolved threads on non-archived parents, oldest
    // first. Fetched even when the active tab isn't `?tab=qa` so the
    // tab badge count is accurate without a round-trip on tab switch.
    apiFetch<BlockQuestionInboxRow[]>(
      `/api/v1/admin/classes/${id}/questions`,
      { accessToken: access },
    ),
  ]);

  if (!detailRes.ok && (detailRes.error.status === 401 || detailRes.error.status === 403)) {
    redirect(`/api/session/logout?next=/${locale}/auth/login`);
  }

  if (!detailRes.ok) {
    const t = await getTranslations("admin.classes");
    return (
      <main className="dashboard-shell">
        <div className="form-error" role="alert">{t("detail.loadError")}</div>
        <p className="admin-back">
          <Link href="/admin/classes">{t("detail.backToList")}</Link>
        </p>
      </main>
    );
  }

  const detail = detailRes.data;
  const allTeachers = teacherRes.ok ? teacherRes.data : [];
  const allStudents = studentRes.ok ? studentRes.data : [];
  const patterns = patternsRes.ok ? patternsRes.data : [];
  const sessions = sessionsRes.ok ? sessionsRes.data : [];
  const homework = homeworkRes.ok ? homeworkRes.data : [];
  // The backend runs with `spring.jackson.default-property-inclusion: non_null`,
  // so a homework with no archive timestamp arrives with `archivedAt` missing
  // entirely (undefined) rather than an explicit `null`. Use nullish equality
  // here so both shapes route the row correctly — otherwise every fresh
  // homework lands in the Archived bucket.
  const activeHomework = homework.filter((h) => h.archivedAt == null);
  const archivedHomework = homework.filter((h) => h.archivedAt != null);

  const lessons = lessonsRes.ok ? lessonsRes.data : [];
  // Same Jackson non_null serialization gotcha applies here: a fresh lesson's
  // archivedAt arrives absent rather than an explicit null. Loose nullish
  // equality is the load-bearing part so new lessons don't get routed into
  // the archived bucket — see the #25 fix entry in CLAUDE.md for history.
  const activeLessons = lessons.filter((l) => l.archivedAt == null);
  const archivedLessons = lessons.filter((l) => l.archivedAt != null);

  // Only school-scoped or platform admins can mutate. Anyone else (teachers)
  // gets a read-only Schedule panel; the API enforces the real authorisation
  // so a crafted request can't bypass this.
  const role = meRes.ok ? meRes.data.role : null;
  const platformRole = meRes.ok ? meRes.data.platformRole : null;
  const meId = meRes.ok ? meRes.data.userId : null;
  const canEditSchedule =
    role === "SCHOOL_ADMIN" || platformRole === "PLATFORM_ADMIN";

  // Attendance is writable by admins (always) and by teachers assigned to this
  // class. We gate on assignment here to avoid showing an affordance that
  // would 403 on save; the backend re-checks on both read and write.
  const isAssignedTeacher =
    role === "TEACHER" &&
    meId !== null &&
    detail.teachers.some((m) => m.userId === meId);
  const canMarkAttendance =
    canEditSchedule || isAssignedTeacher;

  const inClassTeacherIds = new Set(detail.teachers.map((m) => m.userId));
  const inClassStudentIds = new Set(detail.students.map((m) => m.userId));

  // Only offer users who aren't already in the class AND are active.
  const availableTeachers = allTeachers
    .filter((m) => m.membershipStatus === "ACTIVE" && !inClassTeacherIds.has(m.userId))
    .map((m) => ({ userId: m.userId, displayName: m.displayName, email: m.email }));
  const availableStudents = allStudents
    .filter((m) => m.membershipStatus === "ACTIVE" && !inClassStudentIds.has(m.userId))
    .map((m) => ({ userId: m.userId, displayName: m.displayName, email: m.email }));

  const t = await getTranslations("admin.classes");

  // Sub-nav counts. Computed here from the same data the panels render so
  // there's no second fetch and the badges match exactly what the user is
  // about to scroll into. "Due this week" / "this week" rollups use the
  // shared bucketing helper so they agree with the in-panel section
  // headings without a parallel implementation.
  const rosterCount = detail.teachers.length + detail.students.length;
  const homeworkDueThisWeek = countThisWeek(activeHomework, (h) => h.dueAt);
  const sessionsThisWeek = countThisWeek(sessions, (s) => s.startsAt);

  // Q&A inbox — failures here shouldn't gate the rest of the page,
  // so we degrade to an empty list + an in-panel error banner that
  // only renders when the QA tab is active.
  const qaRows = qaRes.ok ? qaRes.data : [];
  const qaLoadError: QaErrorCode | null = qaRes.ok
    ? null
    : qaRes.error.status === 0
      ? "network"
      : "unknown";
  // Total + unresolved are the same number for now (the backend
  // returns unresolved-only). Splitting the two slots keeps room
  // for a future "include resolved" toggle without a sub-nav rev.
  const qaUnresolved = qaRows.length;

  // Overview widget takes the first upcoming session that isn't cancelled.
  // The /sessions endpoint we called above already passes `upcomingOnly=true`
  // (→ backend filters `startsAt >= now()` sorted asc), so the first non-
  // cancelled row is the next scheduled meeting by definition. A currently-
  // in-progress session has `startsAt < now()` and won't appear here; the
  // hero shows the next-next session in that case. Acceptable trade-off
  // for Phase 2a — a proper "in progress" banner can land with the
  // calendar strip in Phase 2b (#62).
  const nextSession = sessions.find((s) => s.status !== "CANCELLED") ?? null;

  return (
    <main className="dashboard-shell class-detail">
      <header className="dashboard-header">
        <div>
          <h1 className="dashboard-title">{detail.name}</h1>
          <p className="dashboard-subtitle">
            {detail.level ? translateLevel(t, detail.level) : t("detail.noLevel")}
            {" · "}
            <span className={`pill pill--${detail.status === "ACTIVE" ? "accepted" : "expired"}`}>
              {translateStatus(t, detail.status)}
            </span>
          </p>
        </div>
        <div className="dashboard-header__actions">
          <ClassStatusForm classId={detail.id} status={detail.status} locale={locale} />
        </div>
      </header>

      <ClassOverview
        nextSession={nextSession}
        homeworkDueThisWeek={homeworkDueThisWeek}
        locale={locale}
      />

      <CalendarStrip
        sessions={sessions}
        homework={activeHomework}
        lessons={activeLessons}
        locale={locale}
      />

      <ClassDetailSubNav
        activeTab={activeTab}
        counts={{
          roster: rosterCount,
          homework: {
            total: activeHomework.length,
            dueThisWeek: homeworkDueThisWeek,
          },
          lessons: { total: activeLessons.length },
          schedule: {
            total: sessions.length,
            thisWeek: sessionsThisWeek,
          },
          qa: {
            total: qaRows.length,
            unresolved: qaUnresolved,
          },
        }}
      />

      {detail.description ? (
        <section className="dashboard-note">
          <p>{detail.description}</p>
        </section>
      ) : null}

      {/*
        Each panel sits inside an anchored wrapper so the sticky sub-nav can
        jump to it via `href="#xxx"`. `scroll-margin-top` on the wrapper
        (set in globals.css under `.class-detail__anchor`) keeps the panel
        header clear of the sticky bar after the jump.
      */}
      {activeTab === "roster" && (
        <div id="roster" className="class-detail__anchor">
          <RosterPanels
            classId={detail.id}
            locale={locale}
            teachers={detail.teachers}
            students={detail.students}
            availableTeachers={availableTeachers}
            availableStudents={availableStudents}
          />
        </div>
      )}

      {/*
        Panel order mirrors the data hierarchy with the most actionable /
        time-sensitive surface at the top:
          Homework  ← what's due (most urgent)
          Lessons   ← study material (rides on top of sessions)
          Schedule  ← when we meet (foundation / reference timetable)
        Reading top-to-bottom: homework → lessons → schedule. The data
        hierarchy (schedule ⊃ lesson ⊃ homework) reads bottom-up from the
        foundation. Keeping roster above all three so membership changes are
        still the first thing a teacher sees on a class.
      */}
      {activeTab === "homework" && (
        <div id="homework" className="class-detail__anchor">
          <HomeworkPanels
            classId={detail.id}
            locale={locale}
            active={activeHomework}
            archived={archivedHomework}
            sessions={sessions}
            canEdit={canMarkAttendance}
          />
        </div>
      )}

      {activeTab === "lessons" && (
        <div id="lessons" className="class-detail__anchor">
          <LessonPanels
            classId={detail.id}
            locale={locale}
            active={activeLessons}
            archived={archivedLessons}
            sessions={sessions}
            canEdit={canMarkAttendance}
          />
        </div>
      )}

      {activeTab === "schedule" && (
        <div id="schedule" className="class-detail__anchor">
          <SchedulePanels
            classId={detail.id}
            locale={locale}
            patterns={patterns}
            sessions={sessions}
            canEdit={canEditSchedule}
            canMarkAttendance={canMarkAttendance}
          />
        </div>
      )}

      {activeTab === "qa" && (
        <div id="qa" className="class-detail__anchor">
          <QaInboxPanel
            classId={detail.id}
            locale={locale}
            rows={qaRows}
            loadError={qaLoadError}
            canEdit={canMarkAttendance}
          />
        </div>
      )}

      <p className="admin-back">
        <Link href="/admin/classes">{t("detail.backToList")}</Link>
      </p>
    </main>
  );
}

function translateStatus(t: (k: string) => string, status: string): string {
  try {
    return t(`status.${status}`);
  } catch {
    return status;
  }
}

function translateLevel(t: (k: string) => string, level: string): string {
  try {
    return t(`level.${level}`);
  } catch {
    return level;
  }
}
