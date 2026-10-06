import { redirect } from "next/navigation";
import { Link } from "@/i18n/routing";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import { finishHomeworkAction } from "./actions";

type MeResponse = {
  userId: string;
  email: string;
  displayName: string;
  preferredLocale: string;
  role: string | null;
  platformRole: string | null;
};

type StudentHomeworkView = {
  id: string;
  classId: string;
  className: string | null;
  sessionId: string | null;
  title: string;
  instructions: string | null;
  dueAt: string | null;
  createdAt: string;
  status: "NOT_STARTED" | "DONE" | "SKIPPED" | null;
  studentNote: string | null;
  submittedAt: string | null;
  markedBy: string | null;
  markedAt: string | null;
  blockCount: number;
  blocksAwaitingReview: number;
  blocksReviewedOk: number;
  blocksNeedsWork: number;
  blocksSolutionRevealed: number;
};

type PageProps = {
  params: Promise<{ locale: string }>;
  searchParams: Promise<{ view?: string }>;
};

const DISPLAY_TZ = "Asia/Tashkent";

/**
 * Turn the homework title into a single-letter badge for the card's
 * icon tile. Uses the first alphanumeric character of the title (or
 * "?" as a fallback for titles that are pure punctuation / empty).
 * We deliberately avoid the Unicode chess pieces the mock uses — they
 * fall back to the system colour-emoji font on many platforms, which
 * ignores `font-size` / `line-height` and renders at an unbounded size.
 * A plain Latin letter in Newsreader serif is a safe, consistent
 * substitute until we have a proper authored icon per homework.
 */
function iconForHomework(title: string): string {
  for (const ch of title) {
    if (/[\p{L}\p{N}]/u.test(ch)) return ch.toUpperCase();
  }
  return "?";
}

type TFn = {
  (key: string): string;
  (key: string, values: Record<string, string | number | Date>): string;
};

type DeadlineInfo = {
  label: string;
  tone: "default" | "tight" | "overdue";
};

/**
 * Turn a due-at ISO string into the relative-time copy the mock uses on
 * the card. Mirrors the three colour states: default (more than a day
 * out), tight (less than 24 h away), and overdue (already past). The
 * "tomorrow / today / weekday" split keeps the surface scannable —
 * students shouldn't have to decode a timestamp to know when something
 * is due.
 */
function deadlineInfoFor(
  iso: string,
  locale: string,
  t: TFn,
): DeadlineInfo {
  const due = new Date(iso);
  const now = new Date();
  const diffMs = due.getTime() - now.getTime();
  const diffHrs = diffMs / (1000 * 60 * 60);
  const diffDays = diffMs / (1000 * 60 * 60 * 24);

  if (diffMs < 0) {
    const overdueHrs = -diffHrs;
    if (overdueHrs < 24) {
      return { label: t("list.dueToday"), tone: "overdue" };
    }
    if (overdueHrs < 48) {
      return { label: t("list.overdueYesterday"), tone: "overdue" };
    }
    const days = Math.max(2, Math.floor(-diffDays));
    return {
      label: t("list.overdueDays", { count: days }),
      tone: "overdue",
    };
  }

  if (diffHrs < 1) {
    const mins = Math.max(1, Math.round(diffHrs * 60));
    return { label: t("list.dueInMinutes", { count: mins }), tone: "tight" };
  }
  if (diffHrs < 24) {
    const hrs = Math.max(1, Math.round(diffHrs));
    return { label: t("list.dueInHours", { count: hrs }), tone: "tight" };
  }
  if (diffDays < 7) {
    const days = Math.max(1, Math.round(diffDays));
    const weekday = new Intl.DateTimeFormat(locale, {
      timeZone: DISPLAY_TZ,
      weekday: "short",
    }).format(due);
    return {
      label: t("list.dueOnWeekday", { weekday, count: days }),
      tone: "default",
    };
  }
  const when = new Intl.DateTimeFormat(locale, {
    timeZone: DISPLAY_TZ,
    day: "numeric",
    month: "short",
  }).format(due);
  return { label: t("list.dueOn", { when }), tone: "default" };
}

type RolledStatus =
  | "NOT_STARTED"
  | "IN_PROGRESS"
  | "SUBMITTED"
  | "NEEDS_WORK"
  | "REVEALED"
  | "REVIEWED_OK"
  | "EMPTY";

type StatusChip = {
  label: string;
  variant: "neutral" | "progress" | "submitted" | "ok" | "needs" | "ghost";
};

/**
 * Squash the five block-level counters into the "dominant" chip the card
 * shows on the right. Matches the mock's priority ordering: any
 * outstanding NEEDS_WORK wins (red), then in-flight SUBMITTED (blue),
 * then fully reviewed OK (green), then "in progress" (amber) if any
 * block has been attempted, else neutral.
 */
function rollUp(hw: StudentHomeworkView): RolledStatus {
  const total = hw.blockCount;
  if (total === 0) return "EMPTY";
  const attempted =
    hw.blocksAwaitingReview +
    hw.blocksReviewedOk +
    hw.blocksNeedsWork +
    hw.blocksSolutionRevealed;
  if (hw.blocksNeedsWork > 0) return "NEEDS_WORK";
  if (hw.blocksReviewedOk === total) return "REVIEWED_OK";
  if (hw.blocksSolutionRevealed > 0 && attempted === total) return "REVEALED";
  if (hw.blocksAwaitingReview > 0 && attempted === total) return "SUBMITTED";
  if (attempted > 0) return "IN_PROGRESS";
  return "NOT_STARTED";
}

function chipFor(
  hw: StudentHomeworkView,
  t: TFn,
): StatusChip {
  const total = hw.blockCount;
  const attempted =
    hw.blocksAwaitingReview +
    hw.blocksReviewedOk +
    hw.blocksNeedsWork +
    hw.blocksSolutionRevealed;
  const rolled = rollUp(hw);
  switch (rolled) {
    case "EMPTY":
      return { label: t("list.chipEmpty"), variant: "ghost" };
    case "NEEDS_WORK":
      return {
        label: t("list.chipNeedsWork", { count: hw.blocksNeedsWork }),
        variant: "needs",
      };
    case "REVIEWED_OK":
      return {
        label: t("list.chipReviewedOk", { done: total, total }),
        variant: "ok",
      };
    case "REVEALED":
      return {
        label: t("list.chipRevealed", {
          count: hw.blocksSolutionRevealed,
        }),
        variant: "ghost",
      };
    case "SUBMITTED":
      return {
        label: t("list.chipSubmitted", { done: attempted, total }),
        variant: "submitted",
      };
    case "IN_PROGRESS":
      return {
        label: t("list.chipInProgress", { done: attempted, total }),
        variant: "progress",
      };
    default:
      return { label: t("list.chipNotStarted"), variant: "neutral" };
  }
}

function progressPercentFor(hw: StudentHomeworkView): number {
  if (hw.blockCount === 0) return 0;
  const attempted =
    hw.blocksAwaitingReview +
    hw.blocksReviewedOk +
    hw.blocksNeedsWork +
    hw.blocksSolutionRevealed;
  return Math.round((attempted / hw.blockCount) * 100);
}

/**
 * True when every block has a terminal state (reviewed OK, needs-work,
 * or solution-revealed). That's the condition the mock gives for
 * surfacing the self-mark "Mark as finished" micro-action. SUBMITTED
 * counts as in-flight so it does NOT qualify.
 */
function allBlocksTerminal(hw: StudentHomeworkView): boolean {
  if (hw.blockCount === 0) return false;
  const terminal =
    hw.blocksReviewedOk + hw.blocksNeedsWork + hw.blocksSolutionRevealed;
  return terminal === hw.blockCount;
}

function computeNextDueHint(
  homework: StudentHomeworkView[],
  locale: string,
  t: TFn,
): string | null {
  const upcoming = homework
    .filter((h) => h.dueAt != null && (h.status ?? "NOT_STARTED") !== "DONE")
    .map((h) => ({ h, when: new Date(h.dueAt!).getTime() }))
    .sort((a, b) => a.when - b.when);
  const now = Date.now();
  const future = upcoming.find((entry) => entry.when >= now);
  if (future) {
    const hrs = (future.when - now) / (1000 * 60 * 60);
    if (hrs < 24) return t("list.nextDueSoon");
    if (hrs < 48) return t("list.nextDueTomorrow");
    const d = new Date(future.h.dueAt!);
    const when = new Intl.DateTimeFormat(locale, {
      timeZone: DISPLAY_TZ,
      weekday: "short",
      day: "numeric",
      month: "short",
    }).format(d);
    return t("list.nextDueOn", { when });
  }
  if (upcoming.length > 0) return t("list.overdue");
  return null;
}

type ViewFilter = "active" | "finished";

function normalizeView(raw: string | undefined): ViewFilter {
  return raw === "finished" ? "finished" : "active";
}

function filterFor(view: ViewFilter, hw: StudentHomeworkView): boolean {
  const status = hw.status ?? "NOT_STARTED";
  return view === "finished" ? status === "DONE" : status !== "DONE";
}

/**
 * Main student surface: the list of active (or finished) homework
 * presented as individual cards. The chrome (sidebar, etc.) lives one
 * level up in {@code layout.tsx}; this page is responsible for just the
 * main column.
 */
export default async function StudentHomePage({
  params,
  searchParams,
}: PageProps) {
  const [{ locale }, spRaw] = await Promise.all([params, searchParams]);
  const view = normalizeView(spRaw.view);
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

  const listRes = await apiFetch<StudentHomeworkView[]>(
    "/api/v1/me/homework",
    { accessToken: access },
  );
  if (!listRes.ok && (listRes.error.status === 401 || listRes.error.status === 403)) {
    redirect(`/api/session/logout?next=/${locale}/auth/login`);
  }
  const allHomework = listRes.ok ? listRes.data : [];
  const homework = allHomework.filter((h) => filterFor(view, h));
  const activeCount = allHomework.filter((h) => filterFor("active", h)).length;
  const finishedCount = allHomework.length - activeCount;

  const t = await getTranslations("student");

  // Summary line for the page header — ties the assigned / awaiting /
  // next-due counts into one scannable metric strip.
  const assigned = activeCount;
  const awaiting = allHomework.reduce(
    (acc, h) => acc + h.blocksAwaitingReview,
    0,
  );
  const nextDue = computeNextDueHint(allHomework, locale, t);
  const subtitleParts = [
    t("list.subAssigned", { count: assigned }),
    t("list.subAwaiting", { count: awaiting }),
    ...(nextDue ? [nextDue] : []),
  ];

  return (
    <>
      <header className="student-main__head">
        <div className="student-main__head-copy">
          <h1 className="student-main__title">{t("list.title")}</h1>
          <p className="student-main__sub">{subtitleParts.join(" · ")}</p>
        </div>
        <nav className="student-tabs" aria-label={t("list.tabsLabel")}>
          <Link
            href="/student"
            className={
              "student-tabs__tab" +
              (view === "active" ? " student-tabs__tab--on" : "")
            }
            aria-current={view === "active" ? "page" : undefined}
          >
            {t("list.tabActive")}
            {activeCount > 0 ? (
              <span className="student-tabs__count">{activeCount}</span>
            ) : null}
          </Link>
          <Link
            href={{ pathname: "/student", query: { view: "finished" } }}
            className={
              "student-tabs__tab" +
              (view === "finished" ? " student-tabs__tab--on" : "")
            }
            aria-current={view === "finished" ? "page" : undefined}
          >
            {t("list.tabFinished")}
            {finishedCount > 0 ? (
              <span className="student-tabs__count">{finishedCount}</span>
            ) : null}
          </Link>
        </nav>
      </header>

      {!listRes.ok ? (
        <div className="form-error" role="alert">
          {t("dashboard.loadError")}
        </div>
      ) : null}

      {homework.length === 0 ? (
        <div className="hw-empty">
          <p className="hw-empty__copy">
            {view === "finished"
              ? t("list.emptyFinished")
              : t("homework.empty")}
          </p>
        </div>
      ) : (
        <ul className="hw-cards" role="list">
          {homework.map((h) => {
            const chip = chipFor(h, t);
            const deadline = h.dueAt
              ? deadlineInfoFor(h.dueAt, locale, t)
              : null;
            const icon = iconForHomework(h.title);
            const percent = progressPercentFor(h);
            const showFinish =
              (h.status ?? "NOT_STARTED") !== "DONE" && allBlocksTerminal(h);
            const blocksLabel =
              h.blockCount > 0
                ? t("list.blocksCount", { count: h.blockCount })
                : t("list.noBlocks");
            return (
              <li key={h.id} className="hw-card">
                <Link
                  href={`/student/homework/${h.id}`}
                  className="hw-card__link"
                >
                  <span className="hw-card__icon" aria-hidden="true">
                    {icon}
                  </span>
                  <div className="hw-card__body">
                    <h3 className="hw-card__title">{h.title}</h3>
                    <div className="hw-card__meta">
                      <span>{blocksLabel}</span>
                      {deadline ? (
                        <>
                          <span className="hw-card__sep" aria-hidden="true">
                            ·
                          </span>
                          <span
                            className={
                              "hw-card__deadline hw-card__deadline--" +
                              deadline.tone
                            }
                          >
                            <svg
                              viewBox="0 0 12 12"
                              width="12"
                              height="12"
                              fill="none"
                              stroke="currentColor"
                              strokeWidth="1.2"
                              aria-hidden="true"
                            >
                              <circle cx="6" cy="6" r="4.5" />
                              <path d="M6 3.5v2.8l1.8 1.1" />
                            </svg>
                            {deadline.label}
                          </span>
                        </>
                      ) : null}
                      {h.className ? (
                        <>
                          <span className="hw-card__sep" aria-hidden="true">
                            ·
                          </span>
                          <span>{h.className}</span>
                        </>
                      ) : null}
                    </div>
                  </div>
                  <div className="hw-card__status">
                    <span
                      className={"hw-chip hw-chip--" + chip.variant}
                    >
                      <span className="hw-chip__dot" aria-hidden="true" />
                      {chip.label}
                    </span>
                    {h.blockCount > 0 ? (
                      <div
                        className={
                          "hw-progress hw-progress--" + chip.variant
                        }
                        aria-label={t("list.progressAriaLabel", {
                          percent,
                        })}
                      >
                        <span
                          className="hw-progress__fill"
                          style={{ width: `${percent}%` }}
                        />
                      </div>
                    ) : null}
                  </div>
                </Link>
                {showFinish ? (
                  <FinishRow homeworkId={h.id} locale={locale} t={t} />
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
    </>
  );
}

/**
 * Row-level action that only renders when every block has a terminal
 * state. Uses the existing `updateMyHomeworkAction` through a plain
 * `<form>` so the page stays a server component — same technique the
 * detail page already uses for its mark-done/skipped chips.
 */
function FinishRow({
  homeworkId,
  locale,
  t,
}: {
  homeworkId: string;
  locale: string;
  t: TFn;
}) {
  return (
    <form className="hw-card__finish" action={finishHomeworkAction}>
      <input type="hidden" name="homeworkId" value={homeworkId} />
      <input type="hidden" name="locale" value={locale} />
      <input type="hidden" name="status" value="DONE" />
      <button type="submit" className="hw-card__finish-btn">
        {t("list.markFinished")}
      </button>
    </form>
  );
}
