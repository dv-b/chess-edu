import { getTranslations } from "next-intl/server";
import { CLASS_DETAIL_TZ } from "@/lib/class-detail-buckets";
import type { ScheduleSession } from "./schedule-panels";

/**
 * Hero card that sits above the sticky sub-nav on the class detail page.
 * Answers the two "what's on my plate right now?" questions a teacher or
 * admin asks when they land on a class:
 *
 * <ol>
 *   <li>When is the next session?</li>
 *   <li>How much homework is due this week?</li>
 * </ol>
 *
 * <p>All data is computed client-side (i.e. in page.tsx) from the rows
 * the panels are about to render anyway — no new backend endpoint. This
 * is the Phase 2a "cheap win" of the Option 4 UX overhaul; the backend
 * rollup that was originally scoped into Task #61 is deliberately
 * deferred because everything we need is already on the wire.
 *
 * <p>The relative-time label ("In 4 h", "Tomorrow", "In 3 days") is
 * computed at server-render time — it won't tick on an open tab.
 * That's consistent with the sub-nav counts and the temporal bucket
 * headings; a future polish could add a client-side refresh hook, but
 * for Phase 2a a fresh render per navigation is the right trade-off.
 *
 * <p>Panel links go to the `#homework` / `#schedule` anchors the Phase
 * 1 sub-nav already established, so hitting the CTA scrolls the user
 * into the relevant panel instead of opening a modal or a new page.
 */

type ClassOverviewProps = {
  nextSession: ScheduleSession | null;
  homeworkDueThisWeek: number;
  locale: string;
};

export async function ClassOverview({
  nextSession,
  homeworkDueThisWeek,
  locale,
}: ClassOverviewProps) {
  const t = await getTranslations("admin.classes.overview");
  const now = new Date();

  return (
    <section className="class-overview" aria-label={t("ariaLabel")}>
      <div className="class-overview__cell class-overview__cell--session">
        <span className="class-overview__eyebrow">
          {t("nextSessionLabel")}
        </span>
        {nextSession ? (
          <>
            <p className="class-overview__headline">
              {relativeLabel(nextSession.startsAt, now, locale, t)}
            </p>
            <p className="class-overview__sub">
              <a className="class-overview__link" href="#schedule">
                {fullTimestamp(nextSession.startsAt, locale)}
              </a>
            </p>
          </>
        ) : (
          <p className="class-overview__headline class-overview__headline--empty">
            {t("nextSessionEmpty")}
          </p>
        )}
      </div>

      <div className="class-overview__cell class-overview__cell--homework">
        <span className="class-overview__eyebrow">
          {t("homeworkDueLabel")}
        </span>
        {homeworkDueThisWeek > 0 ? (
          <>
            <p className="class-overview__headline">
              {t("homeworkDueCount", { count: homeworkDueThisWeek })}
            </p>
            <p className="class-overview__sub">
              <a className="class-overview__link" href="#homework">
                {t("viewHomeworkCta")}
              </a>
            </p>
          </>
        ) : (
          <p className="class-overview__headline class-overview__headline--empty">
            {t("homeworkDueEmpty")}
          </p>
        )}
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Formatting helpers (Asia/Tashkent wall-clock — no DST, fixed +05:00)
// ---------------------------------------------------------------------------

/**
 * Human-scale distance from `now` to the session's `startsAt`. Five bins:
 *
 *   • <2 min out → "Starting now"
 *   • <60 min   → "In N min"
 *   • <24 h     → "In N h"
 *   • Tomorrow  → "Tomorrow"
 *   • 2–6 cal days → "In N days"
 *   • ≥7 days   → fall through to the full date string
 *
 * "Calendar day" is measured in Asia/Tashkent, not wall-clock hours — a
 * session at 00:30 tomorrow from a call made at 23:45 today reads as
 * "Tomorrow", not "In 45 min".
 */
function relativeLabel(
  iso: string,
  now: Date,
  locale: string,
  t: (key: string, values?: Record<string, string | number>) => string,
): string {
  const target = new Date(iso);
  const diffMs = target.getTime() - now.getTime();

  // Edge case: the backend filters `startsAt >= now()` at query time, but
  // the request + render takes a few ms, so a session scheduled for right
  // now can momentarily tip into the past. Render "Starting now" rather
  // than a negative count.
  if (diffMs <= 0) return t("relativeNow");

  const diffMins = Math.round(diffMs / 60_000);
  if (diffMins < 2) return t("relativeNow");
  if (diffMins < 60) return t("relativeMinutes", { count: diffMins });

  const diffHours = Math.round(diffMs / 3_600_000);
  if (diffHours < 24) return t("relativeHours", { count: diffHours });

  const targetYmd = localIsoDate(target);
  const todayYmd = localIsoDate(now);
  const dayDiff = calendarDaysBetween(todayYmd, targetYmd);

  if (dayDiff === 1) return t("relativeTomorrow");
  if (dayDiff <= 6) return t("relativeDays", { count: dayDiff });
  return fullTimestamp(iso, locale);
}

/**
 * Dense header-style timestamp: "THU · 21 APR · 18:00" in Asia/Tashkent.
 * Uppercased weekday + month matches the rhythm of the student list
 * date ribbons elsewhere on the site. Time is 24-hour because the
 * Uzbek product runs on 24h notation; chess classes tend to be
 * evening-only, but a 9 a.m. Saturday club would read correctly too.
 */
function fullTimestamp(iso: string, locale: string): string {
  const target = new Date(iso);
  const tag = locale;
  const weekdayFmt = new Intl.DateTimeFormat(tag, {
    timeZone: CLASS_DETAIL_TZ,
    weekday: "short",
  });
  const dayFmt = new Intl.DateTimeFormat(tag, {
    timeZone: CLASS_DETAIL_TZ,
    day: "numeric",
  });
  const monthFmt = new Intl.DateTimeFormat(tag, {
    timeZone: CLASS_DETAIL_TZ,
    month: "short",
  });
  const timeFmt = new Intl.DateTimeFormat(tag, {
    timeZone: CLASS_DETAIL_TZ,
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
  const weekday = weekdayFmt.format(target).replace(/\.$/, "").toUpperCase();
  const day = dayFmt.format(target);
  const month = monthFmt.format(target).replace(/\.$/, "").toUpperCase();
  const time = timeFmt.format(target);
  return `${weekday} · ${day} ${month} · ${time}`;
}

/**
 * Format a `Date` as YYYY-MM-DD in the display timezone. sv-SE's short
 * form already matches ISO-8601, so the output is directly comparable
 * with string inequality — same trick as `class-detail-buckets.ts`.
 */
function localIsoDate(date: Date): string {
  const parts = new Intl.DateTimeFormat("sv-SE", {
    timeZone: CLASS_DETAIL_TZ,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date);
  const y = parts.find((p) => p.type === "year")?.value ?? "1970";
  const m = parts.find((p) => p.type === "month")?.value ?? "01";
  const d = parts.find((p) => p.type === "day")?.value ?? "01";
  return `${y}-${m}-${d}`;
}

/** Whole-day difference between two YYYY-MM-DD strings (b - a). */
function calendarDaysBetween(aYmd: string, bYmd: string): number {
  const toUtcNoon = (s: string) => {
    const y = Number(s.slice(0, 4));
    const m = Number(s.slice(5, 7));
    const d = Number(s.slice(8, 10));
    return Date.UTC(y, m - 1, d, 12, 0, 0);
  };
  return Math.round((toUtcNoon(bYmd) - toUtcNoon(aYmd)) / 86_400_000);
}
