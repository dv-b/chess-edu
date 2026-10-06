"use client";

import { useTranslations } from "next-intl";

/**
 * Banner rendered above the Homework / Lessons / Schedule panel toolbars
 * whenever the class detail page's {@code ?day=YYYY-MM-DD} query param is
 * active. Reads the same wall-clock timezone as the bucketing helpers so the
 * label on the banner ("Fri · 24 Apr") stays coherent with the calendar
 * strip's selected day cell and with any bucket header the item would
 * otherwise appear under.
 *
 * <p>Kept i18n-agnostic at the call-site boundary by accepting a raw day
 * string + item count + locale; strings are resolved via the shared
 * {@code admin.classes.dayFilter} namespace so the three panels render
 * identical copy without duplicating keys.
 *
 * <p>{@link onClear} is the caller's "strip {@code ?day} from the URL"
 * handler. The banner deliberately does not own the {@code useRouter}
 * round-trip — each panel has its own reason to update the URL (clearing
 * the filter, follow-up nav, etc.) so it's cleaner to hand the mutation
 * back up rather than re-implement {@code pushParams} here.
 */
export function DayFilterBanner({
  day,
  count,
  onClear,
  locale,
}: {
  /** YYYY-MM-DD in Asia/Tashkent display timezone. */
  day: string;
  /** Number of items the panel will render under the active filter. */
  count: number;
  /** Invoked when the user clicks "Clear filter". */
  onClear: () => void;
  /** BCP-47 locale code ({@code en}, {@code ru}, {@code uz}). */
  locale: string;
}) {
  const t = useTranslations("admin.classes.dayFilter");
  const dateLabel = formatDayLabel(day, locale);
  return (
    <div className="day-filter" role="status" aria-live="polite">
      <div className="day-filter__copy">
        <span className="day-filter__heading">
          {t("heading", { date: dateLabel })}
        </span>
        <span className="day-filter__count">{t("count", { count })}</span>
      </div>
      <button
        type="button"
        className="btn btn--ghost btn--sm day-filter__clear"
        onClick={onClear}
        aria-label={t("clearAriaLabel", { date: dateLabel })}
      >
        {t("clear")}
      </button>
    </div>
  );
}

/**
 * Format a YYYY-MM-DD (in display timezone) into a locale-aware
 * "weekday · day mon" label, e.g. "Fri · 24 Apr" or "ПТ · 24 АПР".
 *
 * <p>Constructs the Date at UTC noon so the timezone shift for the formatter
 * never bumps the wall-clock day (+05:00 means the Asia/Tashkent formatter
 * reads "noon UTC" as 17:00 local, still the same YMD). Matches the pattern
 * the calendar strip uses for its day-cell labels.
 */
function formatDayLabel(ymd: string, locale: string): string {
  const y = Number(ymd.slice(0, 4));
  const m = Number(ymd.slice(5, 7));
  const d = Number(ymd.slice(8, 10));
  const utc = new Date(Date.UTC(y, m - 1, d, 12, 0, 0));
  const fmt = new Intl.DateTimeFormat(locale, {
    timeZone: "Asia/Tashkent",
    weekday: "short",
    day: "numeric",
    month: "short",
  });
  // Some Intl locales emit a trailing period on abbreviated month names
  // ("апр." in Russian). Strip so the banner reads cleanly next to the
  // surrounding chrome.
  return fmt.format(utc).replace(/\.$/, "");
}
