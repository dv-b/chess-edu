import { redirect } from "next/navigation";
import { Link } from "@/i18n/routing";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import { finishLessonAction } from "../actions";

type MeResponse = {
  userId: string;
  email: string;
  displayName: string;
  preferredLocale: string;
  role: string | null;
  platformRole: string | null;
};

/**
 * Read shape for one row on the student's own lesson list. Mirrors the
 * backend {@code StudentLessonView} record verbatim.
 *
 * <p>Notable shape notes:
 * <ul>
 *   <li>{@code status === null} means no completion row exists yet — the
 *       student has never touched the lesson. Treated as "new" for the
 *       chip. An explicit {@code NOT_STARTED} means the opened-but-
 *       didn't-finish trail, shown as "in progress".</li>
 *   <li>{@code sessionStartsAt} is denormalised from the anchored
 *       session (if any), so the row can show a "Session · 20 Apr 18:00"
 *       pill without a second fetch.</li>
 *   <li>{@code blockCount} is the total number of authored content
 *       blocks — used on the meta strip as "N blocks".</li>
 * </ul>
 */
type StudentLessonView = {
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
  blockCount: number;
};

type PageProps = {
  params: Promise<{ locale: string }>;
  searchParams: Promise<{ view?: string }>;
};

const DISPLAY_TZ = "Asia/Tashkent";

/**
 * Turn the lesson title into a single-letter badge for the card's icon
 * tile. Mirrors the homework list's {@code iconForHomework} helper —
 * same rationale (Newsreader serif renders the Latin glyph at the
 * configured size, whereas chess Unicode pieces fall back to the
 * system colour-emoji font on many platforms and ignore {@code
 * font-size}).
 */
function iconForLesson(title: string): string {
  for (const ch of title) {
    if (/[\p{L}\p{N}]/u.test(ch)) return ch.toUpperCase();
  }
  return "?";
}

type TFn = {
  (key: string): string;
  (key: string, values: Record<string, string | number | Date>): string;
};

type LessonChip = {
  label: string;
  variant: "neutral" | "progress" | "ok";
};

/**
 * Three visual states the card can land in, derived from the lesson's
 * completion status:
 * <ul>
 *   <li>{@code null}        → "chipNew"        (never opened, neutral)</li>
 *   <li>{@code NOT_STARTED} → "chipInProgress" (opened, not finished — amber)</li>
 *   <li>{@code READ}        → "chipRead"       (student self-marked — green)</li>
 * </ul>
 *
 * <p>The three-way split comes from the backend's deliberate acceptance
 * of explicit {@code NOT_STARTED} writes on the student endpoint
 * (contrast with homework, which 400s on that value). Preserves the
 * "opened but didn't finish" trail and lets the row scan honestly to
 * both the student and the admin completion sheet.
 */
function chipFor(lesson: StudentLessonView, t: TFn): LessonChip {
  if (lesson.status === "READ") {
    return { label: t("lessonsList.chipRead"), variant: "ok" };
  }
  if (lesson.status === "NOT_STARTED") {
    return { label: t("lessonsList.chipInProgress"), variant: "progress" };
  }
  return { label: t("lessonsList.chipNew"), variant: "neutral" };
}

type ViewFilter = "unread" | "read";

function normalizeView(raw: string | undefined): ViewFilter {
  return raw === "read" ? "read" : "unread";
}

function filterFor(view: ViewFilter, lesson: StudentLessonView): boolean {
  const isRead = lesson.status === "READ";
  return view === "read" ? isRead : !isRead;
}

/**
 * Format an ISO timestamp into the meta-strip "Session · 20 Apr 18:00"
 * pill. Locale-aware weekday + day + short month + HH:mm in
 * Asia/Tashkent so the label reads the way a student in Tashkent would
 * expect regardless of the browser's local tz.
 */
function formatSessionLabel(iso: string, locale: string, t: TFn): string {
  const when = new Intl.DateTimeFormat(locale, {
    timeZone: DISPLAY_TZ,
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(new Date(iso));
  return t("lessonsList.sessionOn", { when });
}

/**
 * Student-side lessons list. Mirrors the shape of the homework list
 * page — same auth gate, same header layout, same card primitives
 * (reused {@code .hw-cards} / {@code .hw-card*} chrome) — but
 * simplified for the lesson flow's binary completion state: no due
 * dates, no block-level progress bar, no three-way chip rollup. The
 * URL-driven {@code ?view=read} toggle flips the list between unread
 * (default) and the review-only read archive.
 */
export default async function StudentLessonsPage({
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

  const listRes = await apiFetch<StudentLessonView[]>("/api/v1/me/lessons", {
    accessToken: access,
  });
  if (
    !listRes.ok &&
    (listRes.error.status === 401 || listRes.error.status === 403)
  ) {
    redirect(`/api/session/logout?next=/${locale}/auth/login`);
  }
  const allLessons = listRes.ok ? listRes.data : [];
  const lessons = allLessons.filter((l) => filterFor(view, l));
  const unreadCount = allLessons.filter((l) => filterFor("unread", l)).length;
  const readCount = allLessons.length - unreadCount;

  const t = await getTranslations("student");

  // Subtitle strip for the page header — mirrors the homework list's
  // "assigned · awaiting review" rhythm but swaps awaiting-review for
  // "unread" since lessons have no teacher-review axis. If every
  // assigned lesson has been read, the unread clause collapses to the
  // empty-state phrasing from ICU's =0 branch.
  const subtitleParts = [
    t("lessonsList.subAssigned", { count: allLessons.length }),
    t("lessonsList.subUnread", { count: unreadCount }),
  ];

  return (
    <>
      <header className="student-main__head">
        <div className="student-main__head-copy">
          <h1 className="student-main__title">{t("lessonsList.title")}</h1>
          <p className="student-main__sub">{subtitleParts.join(" · ")}</p>
        </div>
        <nav className="student-tabs" aria-label={t("lessonsList.tabsLabel")}>
          <Link
            href="/student/lessons"
            className={
              "student-tabs__tab" +
              (view === "unread" ? " student-tabs__tab--on" : "")
            }
            aria-current={view === "unread" ? "page" : undefined}
          >
            {t("lessonsList.tabUnread")}
            {unreadCount > 0 ? (
              <span className="student-tabs__count">{unreadCount}</span>
            ) : null}
          </Link>
          <Link
            href={{ pathname: "/student/lessons", query: { view: "read" } }}
            className={
              "student-tabs__tab" +
              (view === "read" ? " student-tabs__tab--on" : "")
            }
            aria-current={view === "read" ? "page" : undefined}
          >
            {t("lessonsList.tabRead")}
            {readCount > 0 ? (
              <span className="student-tabs__count">{readCount}</span>
            ) : null}
          </Link>
        </nav>
      </header>

      {!listRes.ok ? (
        <div className="form-error" role="alert">
          {t("lessonsList.loadError")}
        </div>
      ) : null}

      {lessons.length === 0 ? (
        <div className="hw-empty">
          <p className="hw-empty__copy">
            {view === "read"
              ? t("lessonsList.emptyRead")
              : t("lessonsList.empty")}
          </p>
        </div>
      ) : (
        <ul className="hw-cards" role="list">
          {lessons.map((l) => {
            const chip = chipFor(l, t);
            const icon = iconForLesson(l.title);
            const sessionLabel = l.sessionStartsAt
              ? formatSessionLabel(l.sessionStartsAt, locale, t)
              : null;
            const blocksLabel =
              l.blockCount > 0
                ? t("lessonsList.blocksCount", { count: l.blockCount })
                : t("lessonsList.noBlocks");
            const showMarkRead = l.status !== "READ";
            return (
              <li key={l.id} className="hw-card">
                <Link
                  href={`/student/lessons/${l.id}`}
                  className="hw-card__link"
                >
                  <span className="hw-card__icon" aria-hidden="true">
                    {icon}
                  </span>
                  <div className="hw-card__body">
                    <h3 className="hw-card__title">{l.title}</h3>
                    <div className="hw-card__meta">
                      <span>{blocksLabel}</span>
                      {sessionLabel ? (
                        <>
                          <span className="hw-card__sep" aria-hidden="true">
                            ·
                          </span>
                          <span className="hw-card__deadline hw-card__deadline--default">
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
                            {sessionLabel}
                          </span>
                        </>
                      ) : null}
                      {l.className ? (
                        <>
                          <span className="hw-card__sep" aria-hidden="true">
                            ·
                          </span>
                          <span>{l.className}</span>
                        </>
                      ) : null}
                    </div>
                  </div>
                  <div className="hw-card__status">
                    <span className={"hw-chip hw-chip--" + chip.variant}>
                      <span className="hw-chip__dot" aria-hidden="true" />
                      {chip.label}
                    </span>
                  </div>
                </Link>
                {showMarkRead ? (
                  <MarkReadRow lessonId={l.id} locale={locale} t={t} />
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
 * Row-level micro-action that flips a lesson's completion to READ in
 * one click. Only rendered when the lesson isn't already READ — once
 * it is, the card falls off the default Unread tab and reappears
 * under Read with no affordance to un-mark (a cleanup path lives on
 * the forthcoming detail page, where the chip trio mirrors the
 * homework detail's mark-done/skipped/clear grouping).
 *
 * <p>Uses the same plain {@code <form>} + hidden-input pattern as the
 * homework list's {@code FinishRow}, which lets this page stay a
 * server component while still binding to a server action. Errors
 * are swallowed on purpose — see {@link finishLessonAction} for the
 * rationale.
 */
function MarkReadRow({
  lessonId,
  locale,
  t,
}: {
  lessonId: string;
  locale: string;
  t: TFn;
}) {
  return (
    <form className="hw-card__finish" action={finishLessonAction}>
      <input type="hidden" name="lessonId" value={lessonId} />
      <input type="hidden" name="locale" value={locale} />
      <input type="hidden" name="status" value="READ" />
      <button type="submit" className="hw-card__finish-btn">
        {t("lessonsList.markRead")}
      </button>
    </form>
  );
}
