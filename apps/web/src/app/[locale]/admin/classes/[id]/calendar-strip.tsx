"use client";

import { useMemo } from "react";
import type { Route } from "next";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import {
  CLASS_DETAIL_TZ,
  addDays,
  isoWeekMondayYmd,
  localIsoDate,
} from "@/lib/class-detail-buckets";
import type { ScheduleSession } from "./schedule-panels";
import type { HomeworkSummary } from "./homework-actions";
import type { LessonSummary } from "./lesson-actions";

/**
 * Horizontal 7-day calendar strip for the class detail page. Phase 2b of
 * the Option 4 UX overhaul: sits between the Overview widget and the
 * sticky sub-nav so the reader can scan "what's happening this week?"
 * at a glance before drilling into panels.
 *
 * <p>Each day cell carries up to three coloured dots — green session,
 * blue lesson (lessons anchored to a session starting that day), and
 * amber homework (homework with a dueAt on that day). Today is ringed;
 * the selected day (if any) is filled. Arrow buttons step the window by
 * a week in either direction, unbounded; a "Jump to today" affordance
 * appears whenever the viewer has navigated away from the current week.
 *
 * <h2>URL state</h2>
 *
 * <p>Two optional query params:
 * <ul>
 *   <li><b>?week=YYYY-MM-DD</b> — Monday of the visible window.
 *       Defaults to the Monday of today's ISO week.</li>
 *   <li><b>?day=YYYY-MM-DD</b> — currently-selected day within the
 *       window (visual only in this slice; filter wiring is Task #64).</li>
 * </ul>
 *
 * <p>Arrow nav clears the day selection — a selection from a different
 * week isn't meaningful once the viewer has moved on. "Jump to today"
 * clears both params. Day-click toggles: clicking the already-selected
 * cell deselects it, matching the {@code aria-pressed} semantic.
 *
 * <h2>Data limitations (known)</h2>
 *
 * <p>The {@code sessions} prop comes from page.tsx's {@code ?upcomingOnly=true}
 * fetch, so past weeks render empty in the "sessions" column even when a
 * class has met before. Homework + lessons are fetched archive-inclusive so
 * past-dated rows DO show dots. Widening the sessions fetch to cover past
 * weeks is deferred — it would double the wire size of a typical class
 * detail page just to light up historical session dots that the viewer
 * can already see in the Schedule panel's Past bucket one click away.
 *
 * <h2>Cancelled sessions</h2>
 *
 * <p>Counted toward the session-dot tally so the strip stays consistent
 * with Phase 1's sub-nav "N this week" count. Visual distinction (struck
 * dot / dim tint) for cancelled-only days is a polish item.
 */

export type CalendarStripProps = {
  sessions: ScheduleSession[];
  homework: HomeworkSummary[];
  lessons: LessonSummary[];
  locale: string;
};

type DayTally = {
  sessions: number;
  lessons: number;
  homework: number;
};

export function CalendarStrip({
  sessions,
  homework,
  lessons,
  locale,
}: CalendarStripProps) {
  const t = useTranslations("admin.classes.calendar");
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  // ---- Anchor dates (all YYYY-MM-DD in Asia/Tashkent) --------------------
  const todayYmd = localIsoDate(new Date());
  const todayWeekMonday = isoWeekMondayYmd(todayYmd);

  const weekParam = searchParams.get("week");
  const dayParam = searchParams.get("day");
  // Normalise: a malformed ?week falls back silently to today's week so a
  // crafted URL can't crash the strip. Same for ?day — fall back to null.
  const weekMonday = isValidYmd(weekParam)
    ? isoWeekMondayYmd(weekParam)
    : todayWeekMonday;
  const selectedDay = isValidYmd(dayParam) ? dayParam : null;
  const onCurrentWeek = weekMonday === todayWeekMonday;

  // ---- 7-day window ------------------------------------------------------
  const days = useMemo(() => {
    const out: string[] = [];
    for (let i = 0; i < 7; i++) out.push(addDays(weekMonday, i));
    return out;
  }, [weekMonday]);

  // ---- Per-day tallies ---------------------------------------------------
  const tallies = useMemo(() => {
    const map = new Map<string, DayTally>();
    for (const day of days) {
      map.set(day, { sessions: 0, lessons: 0, homework: 0 });
    }
    for (const s of sessions) {
      const key = localIsoDate(new Date(s.startsAt));
      const row = map.get(key);
      if (row) row.sessions++;
    }
    for (const l of lessons) {
      if (!l.sessionStartsAt) continue;
      const key = localIsoDate(new Date(l.sessionStartsAt));
      const row = map.get(key);
      if (row) row.lessons++;
    }
    for (const h of homework) {
      if (!h.dueAt) continue;
      const key = localIsoDate(new Date(h.dueAt));
      const row = map.get(key);
      if (row) row.homework++;
    }
    return map;
  }, [days, sessions, lessons, homework]);

  // ---- Week range label ("20 – 26 Apr" / "28 Apr – 4 May") --------------
  const rangeLabel = useMemo(() => {
    const first = days[0]!;
    const last = days[6]!;
    const firstDate = ymdToUtcNoon(first);
    const lastDate = ymdToUtcNoon(last);
    const dayFmt = new Intl.DateTimeFormat(locale, {
      timeZone: CLASS_DETAIL_TZ,
      day: "numeric",
    });
    const monthFmt = new Intl.DateTimeFormat(locale, {
      timeZone: CLASS_DETAIL_TZ,
      month: "short",
    });
    const firstDay = dayFmt.format(firstDate);
    const lastDay = dayFmt.format(lastDate);
    const firstMonth = monthFmt.format(firstDate).replace(/\.$/, "");
    const lastMonth = monthFmt.format(lastDate).replace(/\.$/, "");
    if (firstMonth === lastMonth) {
      return `${firstDay} – ${lastDay} ${firstMonth}`;
    }
    return `${firstDay} ${firstMonth} – ${lastDay} ${lastMonth}`;
  }, [days, locale]);

  // ---- Nav handlers ------------------------------------------------------
  /**
   * Replace `?week` / `?day` params in place. We use router.replace rather
   * than push so the browser back button doesn't cycle through every
   * calendar click — URL state is a viewer preference, not navigation.
   * scroll: false keeps the user's scroll position; the strip lives at the
   * top of the page so it shouldn't yank them anywhere on click.
   */
  const pushParams = (next: { week?: string | null; day?: string | null }) => {
    const params = new URLSearchParams(searchParams.toString());
    if (next.week === null) params.delete("week");
    else if (next.week !== undefined) params.set("week", next.week);
    if (next.day === null) params.delete("day");
    else if (next.day !== undefined) params.set("day", next.day);
    const qs = params.toString();
    // Next 15's typedRoutes wants a known-at-compile-time RouteImpl; our URL
    // is dynamic (pathname from the runtime + query string), so we cast to
    // the generic `Route` type. Runtime shape is identical to what router
    // would accept without typed routes turned on.
    const url = (qs ? `${pathname}?${qs}` : pathname) as Route;
    router.replace(url, { scroll: false });
  };

  const onPrevWeek = () => {
    pushParams({ week: addDays(weekMonday, -7), day: null });
  };
  const onNextWeek = () => {
    pushParams({ week: addDays(weekMonday, 7), day: null });
  };
  const onJumpToday = () => {
    pushParams({ week: null, day: null });
  };
  const onDayClick = (day: string) => {
    pushParams({ day: day === selectedDay ? null : day });
  };

  // ---- Formatters (locale-aware; locked to Asia/Tashkent) ---------------
  const weekdayFmt = new Intl.DateTimeFormat(locale, {
    timeZone: CLASS_DETAIL_TZ,
    weekday: "short",
  });
  const dayNumFmt = new Intl.DateTimeFormat(locale, {
    timeZone: CLASS_DETAIL_TZ,
    day: "numeric",
  });
  const dayAriaFmt = new Intl.DateTimeFormat(locale, {
    timeZone: CLASS_DETAIL_TZ,
    weekday: "long",
    day: "numeric",
    month: "long",
  });

  return (
    <section className="cal-strip" aria-label={t("ariaLabel")}>
      <div className="cal-strip__head">
        <div className="cal-strip__nav-group">
          <button
            type="button"
            className="cal-strip__nav"
            onClick={onPrevWeek}
            aria-label={t("prevWeek")}
            title={t("prevWeek")}
          >
            <span aria-hidden="true">‹</span>
          </button>
          <span className="cal-strip__range" aria-live="polite">
            {rangeLabel}
          </span>
          <button
            type="button"
            className="cal-strip__nav"
            onClick={onNextWeek}
            aria-label={t("nextWeek")}
            title={t("nextWeek")}
          >
            <span aria-hidden="true">›</span>
          </button>
        </div>
        {!onCurrentWeek ? (
          <button
            type="button"
            className="cal-strip__today"
            onClick={onJumpToday}
          >
            {t("jumpToday")}
          </button>
        ) : (
          <span className="cal-strip__today cal-strip__today--placeholder" aria-hidden="true" />
        )}
      </div>

      <ol className="cal-strip__days" role="list">
        {days.map((day) => {
          const tally = tallies.get(day)!;
          const isToday = day === todayYmd;
          const isSelected = day === selectedDay;
          const date = ymdToUtcNoon(day);
          const weekdayRaw = weekdayFmt.format(date).replace(/\.$/, "");
          const weekday = weekdayRaw.toUpperCase();
          const num = dayNumFmt.format(date);
          const itemCount = tally.sessions + tally.lessons + tally.homework;
          const ariaLabel = t("dayAriaLabel", {
            day: dayAriaFmt.format(date),
            count: itemCount,
          });
          const classes = [
            "cal-strip__day",
            isToday ? "cal-strip__day--today" : "",
            isSelected ? "cal-strip__day--selected" : "",
            itemCount === 0 ? "cal-strip__day--empty" : "",
          ]
            .filter(Boolean)
            .join(" ");

          return (
            <li key={day} className="cal-strip__day-wrap">
              <button
                type="button"
                className={classes}
                onClick={() => onDayClick(day)}
                aria-pressed={isSelected}
                aria-label={ariaLabel}
                aria-current={isToday ? "date" : undefined}
              >
                <span className="cal-strip__weekday">
                  {isToday ? t("todayLabel") : weekday}
                </span>
                <span className="cal-strip__daynum">{num}</span>
                <span className="cal-strip__dots" aria-hidden="true">
                  {tally.sessions > 0 ? (
                    <span className="cal-strip__dot cal-strip__dot--session" />
                  ) : null}
                  {tally.lessons > 0 ? (
                    <span className="cal-strip__dot cal-strip__dot--lesson" />
                  ) : null}
                  {tally.homework > 0 ? (
                    <span className="cal-strip__dot cal-strip__dot--homework" />
                  ) : null}
                </span>
              </button>
            </li>
          );
        })}
      </ol>

      <div className="cal-strip__legend" aria-hidden="true">
        <span className="cal-strip__legend-item">
          <span className="cal-strip__dot cal-strip__dot--session" />
          {t("legendSession")}
        </span>
        <span className="cal-strip__legend-item">
          <span className="cal-strip__dot cal-strip__dot--lesson" />
          {t("legendLesson")}
        </span>
        <span className="cal-strip__legend-item">
          <span className="cal-strip__dot cal-strip__dot--homework" />
          {t("legendHomework")}
        </span>
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Narrow a search-param string to a YYYY-MM-DD. Accepts only strict
 * 10-char ISO dates — anything else (undefined, empty, "today",
 * "2026-4-1", "garbage") falls through to the caller's fallback.
 */
function isValidYmd(s: string | null | undefined): s is string {
  if (!s) return false;
  return /^\d{4}-\d{2}-\d{2}$/.test(s);
}

/**
 * Build a Date at UTC-noon for a YYYY-MM-DD string. Noon-UTC is used here
 * (not midnight-UTC) so that `Intl.DateTimeFormat` with
 * `timeZone: "Asia/Tashkent"` reliably lands on the same calendar date
 * the string represents. A midnight-UTC date would roll back to the prior
 * day for negative-offset timezones; noon buffers against both directions.
 */
function ymdToUtcNoon(yyyyMmDd: string): Date {
  const y = Number(yyyyMmDd.slice(0, 4));
  const m = Number(yyyyMmDd.slice(5, 7));
  const d = Number(yyyyMmDd.slice(8, 10));
  return new Date(Date.UTC(y, m - 1, d, 12, 0, 0));
}
