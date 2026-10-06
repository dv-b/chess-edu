"use client";

import { useCallback, useMemo } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import type { Route } from "next";

import { DEFAULT_TAB, TAB_ORDER, type ClassDetailTab } from "./tabs";

/**
 * Class detail page tab strip (Phase 2c of the Option 4 UX overhaul).
 *
 * <p>Replaces the Phase 1 `ClassDetailSubNav` (four hash-anchor jump links)
 * with a proper URL-driven tab strip. Each tab swaps which panel block the
 * page renders below the overview + calendar strip. The Phase 2a Overview
 * hero + Phase 2b Calendar strip stay pinned above the strip ("Always on
 * top" IA shape) so the reader's "what's happening?" signal survives the
 * tab switch.
 *
 * <p>State lives in the URL via the `?tab=` query param — `?tab=roster`
 * (default) / `?tab=homework` / `?tab=lessons` / `?tab=schedule`. The
 * default is omitted from the URL on purpose so a fresh load of
 * `/admin/classes/&lt;id&gt;` reads as the canonical shape; only non-default
 * tabs pin the param. Arrow nav / day selection from the calendar strip
 * merge into the existing `searchParams` so `?week=` / `?day=` survive
 * round-trips; same in the other direction (this strip merges too).
 *
 * <p>Anchor IDs from Phase 1 (`#roster` / `#homework` / `#lessons` /
 * `#schedule`) stay stable — each panel's wrapper `<div>` still carries
 * them. They now double as `aria-controls` targets for the tab buttons
 * so screen readers announce the tab/panel relationship, and bookmarks
 * made during Phase 1 keep working (a bookmark to `#homework` still
 * jumps into the page, and the page's default-tab behaviour means they
 * still land on the Roster tab — the anchor won't match anything
 * actionable, but nothing breaks).
 *
 * <p>Counts come pre-rolled from page.tsx via the {@link SubNavCounts}
 * record — kept nested to minimise churn in the caller. The badge on
 * each tab reads the relevant total; the two hint pills (homework "N
 * due this week", schedule "N this week") carry over from Phase 1 so
 * the viewer still scans urgency at a glance.
 */

export type SubNavCounts = {
  roster: number;
  homework: { total: number; dueThisWeek: number };
  lessons: { total: number };
  schedule: { total: number; thisWeek: number };
  qa: { total: number; unresolved: number };
};

export function ClassDetailSubNav({
  activeTab,
  counts,
}: {
  activeTab: ClassDetailTab;
  counts: SubNavCounts;
}) {
  const t = useTranslations("admin.classes.tabs");
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const pushTab = useCallback(
    (next: ClassDetailTab) => {
      const nextParams = new URLSearchParams(searchParams?.toString() ?? "");
      if (next === DEFAULT_TAB) {
        // Default tab omits `?tab=` for the canonical URL shape.
        nextParams.delete("tab");
      } else {
        nextParams.set("tab", next);
      }
      const qs = nextParams.toString();
      // Use `router.replace` (not push) so the tab strip doesn't pollute
      // browser history — backing out of a class detail page should return
      // to the class list, not cycle through every tab the viewer toggled.
      // `scroll: false` preserves scroll position so clicking a tab doesn't
      // teleport the viewer back to the top.
      // Build the URL with the full pathname so a default-tab click (which
      // produces an empty querystring) still navigates — passing just `""`
      // is treated as a no-op. We use Next's native `useRouter` (not
      // next-intl's) because `pathname` from `usePathname()` already
      // includes the locale segment (`/en/...`); next-intl's router
      // auto-prepends the locale and would produce `/en/en/...` → 404.
      // Sibling components on this page (calendar-strip, schedule-panels,
      // homework-panels, lesson-panels) all follow the same pattern.
      const url = (qs ? `${pathname}?${qs}` : pathname) as Route;
      router.replace(url, { scroll: false });
    },
    [pathname, router, searchParams],
  );

  const items = useMemo(
    () =>
      TAB_ORDER.map((tab) => {
        const isActive = tab === activeTab;
        const count =
          tab === "roster"
            ? counts.roster
            : tab === "homework"
              ? counts.homework.total
              : tab === "lessons"
                ? counts.lessons.total
                : tab === "schedule"
                  ? counts.schedule.total
                  : counts.qa.total;
        const hint =
          tab === "homework" && counts.homework.dueThisWeek > 0
            ? t("dueThisWeek", { count: counts.homework.dueThisWeek })
            : tab === "schedule" && counts.schedule.thisWeek > 0
              ? t("scheduleThisWeek", { count: counts.schedule.thisWeek })
              : tab === "qa" && counts.qa.unresolved > 0
                ? t("qaUnresolved", { count: counts.qa.unresolved })
                : null;
        return { tab, isActive, count, hint };
      }),
    [activeTab, counts, t],
  );

  return (
    <nav
      className="class-tabs"
      aria-label={t("ariaLabel")}
      data-class-subnav=""
    >
      <div className="class-tabs__list" role="tablist">
        {items.map(({ tab, isActive, count, hint }) => (
          <button
            key={tab}
            type="button"
            role="tab"
            id={`class-detail-tab-${tab}`}
            aria-selected={isActive}
            aria-controls={tab}
            className={`class-tabs__tab${isActive ? " class-tabs__tab--on" : ""}`}
            onClick={() => {
              if (!isActive) pushTab(tab);
            }}
          >
            <span className="class-tabs__label">{t(tab)}</span>
            <span className="class-tabs__count" aria-hidden="true">
              {count}
            </span>
            {hint ? <span className="class-tabs__hint">{hint}</span> : null}
          </button>
        ))}
      </div>
    </nav>
  );
}
