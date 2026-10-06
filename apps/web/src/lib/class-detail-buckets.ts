/**
 * Temporal bucketing helpers used across the class detail page's three
 * time-driven panels (Homework, Lessons, Schedule). The shape is a five-way
 * split keyed on the item's primary timestamp:
 *
 * <ul>
 *   <li><b>thisWeek</b> — today through end-of-week (Sunday) in the display
 *       timezone. Today's items always live here, including items whose
 *       dueAt/startsAt has already passed within today.</li>
 *   <li><b>nextWeek</b> — the Monday–Sunday window immediately after.</li>
 *   <li><b>later</b> — anything further in the future.</li>
 *   <li><b>noDate</b> — items with a null timestamp (e.g. a homework without
 *       a due date, or a lesson that isn't anchored to a session).</li>
 *   <li><b>past</b> — anything before the start of today. Rendered collapsed
 *       by default so overdue homework + finished sessions stay one click
 *       away without eating scroll space up top.</li>
 * </ul>
 *
 * <p>All math is done in wall-clock Asia/Tashkent. Uzbekistan has not
 * observed DST since 2005, so the fixed +05:00 offset is safe; if that
 * ever changes we'd need to switch to `Intl.DateTimeFormat` for every
 * offset lookup instead of the simple constant below.
 *
 * <p>The caller supplies an accessor that maps an item → its primary ISO
 * timestamp string (or null). Items within a bucket preserve input order,
 * so an upstream "newest first" sort carries through unchanged.
 */

export const CLASS_DETAIL_TZ = "Asia/Tashkent";

export type TemporalBucket =
  | "thisWeek"
  | "nextWeek"
  | "later"
  | "noDate"
  | "past";

/**
 * Stable priority order for bucket rendering. "noDate" sits after
 * "later" because it's the least actionable; "past" is always last so
 * the collapsed section doesn't fight with above-the-fold content.
 */
export const BUCKET_ORDER: TemporalBucket[] = [
  "thisWeek",
  "nextWeek",
  "later",
  "noDate",
  "past",
];

export type BucketGroup<T> = {
  bucket: TemporalBucket;
  items: T[];
};

/**
 * Classify a single ISO timestamp against `now`. Pure function; exposed so
 * callers (e.g. the sticky sub-nav count rollups) can ask about individual
 * items without building a whole bucket list.
 */
export function classifyTimestamp(
  iso: string | null | undefined,
  now: Date = new Date(),
): TemporalBucket {
  if (iso == null) return "noDate";
  const today = localIsoDate(now);
  const itemDay = localIsoDate(new Date(iso));

  // Same day, regardless of time-of-day — today's items are always "this week".
  if (itemDay === today) return "thisWeek";
  if (itemDay < today) return "past";

  const { thisMonday, nextMonday, laterMonday } = weekBoundsForToday(today);
  if (itemDay < nextMonday) return "thisWeek";
  if (itemDay < laterMonday) return "nextWeek";
  return "later";
}

/**
 * Group a list of items into their temporal buckets. Buckets are returned
 * in {@link BUCKET_ORDER}; empty buckets are skipped. Items within a bucket
 * keep input order, so the caller's sort carries through.
 */
export function bucketByTimestamp<T>(
  items: T[],
  getTimestamp: (item: T) => string | null | undefined,
  now: Date = new Date(),
): BucketGroup<T>[] {
  const map = new Map<TemporalBucket, T[]>();
  for (const item of items) {
    const bucket = classifyTimestamp(getTimestamp(item), now);
    const existing = map.get(bucket);
    if (existing) {
      existing.push(item);
    } else {
      map.set(bucket, [item]);
    }
  }
  const out: BucketGroup<T>[] = [];
  for (const bucket of BUCKET_ORDER) {
    const rows = map.get(bucket);
    if (rows && rows.length > 0) out.push({ bucket, items: rows });
  }
  return out;
}

/**
 * Count items whose primary timestamp lands in "this week". Callers use
 * this for the sticky sub-nav badge (e.g. "14 homework · 3 due this week").
 */
export function countThisWeek<T>(
  items: T[],
  getTimestamp: (item: T) => string | null | undefined,
  now: Date = new Date(),
): number {
  let n = 0;
  for (const item of items) {
    if (classifyTimestamp(getTimestamp(item), now) === "thisWeek") n++;
  }
  return n;
}

/**
 * True when an ISO instant lands on the given {@code ymd} (YYYY-MM-DD) in
 * the display timezone. Items with a null / undefined timestamp never match
 * — a "no due date" homework can't be scoped to a specific day. Shared with
 * the Phase 2d day-filter that reads {@code ?day} from the URL and scopes
 * the three time-driven panels down to rows that land on the pinned day.
 *
 * <p>Symmetric with {@link classifyTimestamp}: both round the item's
 * timestamp through {@link localIsoDate} so an item classified "this week"
 * and landing on today's calendar cell stays coherent with an item that
 * passes the day-filter for today.
 */
export function isOnDay(
  iso: string | null | undefined,
  ymd: string,
): boolean {
  if (iso == null) return false;
  return localIsoDate(new Date(iso)) === ymd;
}

/**
 * Count items whose primary timestamp lands on {@code ymd}. Used by the
 * day-filter banner to show "N items on this day" rollups. Same accessor
 * shape as {@link bucketByTimestamp} for drop-in reuse.
 */
export function countOnDay<T>(
  items: T[],
  getTimestamp: (item: T) => string | null | undefined,
  ymd: string,
): number {
  let n = 0;
  for (const item of items) {
    if (isOnDay(getTimestamp(item), ymd)) n++;
  }
  return n;
}

// ---------------------------------------------------------------------------
// Internal date helpers (Asia/Tashkent wall-clock)
// ---------------------------------------------------------------------------

/**
 * Format an instant as YYYY-MM-DD in the display timezone. Uses sv-SE
 * because that locale's short-date output already matches ISO-8601, so we
 * don't need to reassemble parts by hand.
 *
 * <p>Exported so downstream chrome (calendar strip / day-selection filter
 * / future rollups) can share the exact same "today" math as the bucket
 * classifier — if these drifted we'd see an item classified as "this week"
 * here but landing on yesterday's calendar cell, or vice versa.
 */
export function localIsoDate(date: Date): string {
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

/**
 * Cached weekday lookup: YYYY-MM-DD → 1..7 (Mon..Sun). The cache key is the
 * plain date string so reading the same `today` for Homework + Lessons +
 * Schedule doesn't pay three Intl round-trips.
 */
const weekdayCache = new Map<string, number>();
function isoDayOfWeek(yyyyMmDd: string): number {
  const cached = weekdayCache.get(yyyyMmDd);
  if (cached != null) return cached;
  // Parse as a plain date at UTC noon to dodge any residual DST weirdness
  // in the host locale; we only need the weekday, not the wall-clock time.
  const y = Number(yyyyMmDd.slice(0, 4));
  const m = Number(yyyyMmDd.slice(5, 7));
  const d = Number(yyyyMmDd.slice(8, 10));
  const utc = new Date(Date.UTC(y, m - 1, d, 12, 0, 0));
  // getUTCDay: 0 = Sunday, 1 = Monday … 6 = Saturday. Convert to ISO 1..7.
  const day = utc.getUTCDay();
  const iso = day === 0 ? 7 : day;
  weekdayCache.set(yyyyMmDd, iso);
  return iso;
}

/**
 * Given "today" as YYYY-MM-DD (in display TZ), compute three anchor dates
 * (all YYYY-MM-DD) used by {@link classifyTimestamp}:
 *   - thisMonday: Monday of today's ISO week.
 *   - nextMonday: Monday of the week after.
 *   - laterMonday: Monday two weeks after (i.e. start of "later").
 */
function weekBoundsForToday(todayYmd: string): {
  thisMonday: string;
  nextMonday: string;
  laterMonday: string;
} {
  const iso = isoDayOfWeek(todayYmd);
  const thisMonday = addDays(todayYmd, -(iso - 1));
  const nextMonday = addDays(thisMonday, 7);
  const laterMonday = addDays(thisMonday, 14);
  return { thisMonday, nextMonday, laterMonday };
}

/**
 * Compute the Monday YYYY-MM-DD of the ISO week containing `yyyyMmDd`
 * (in the display timezone). Exported so the calendar strip can anchor
 * its 7-day window to the same Monday the schedule panel uses for its
 * "This week" group heading.
 */
export function isoWeekMondayYmd(yyyyMmDd: string): string {
  const iso = isoDayOfWeek(yyyyMmDd);
  return addDays(yyyyMmDd, -(iso - 1));
}

/**
 * Add (or subtract, with negative n) whole days to a YYYY-MM-DD string.
 * Exported for downstream chrome that walks a week window day-by-day.
 */
export function addDays(yyyyMmDd: string, n: number): string {
  const y = Number(yyyyMmDd.slice(0, 4));
  const m = Number(yyyyMmDd.slice(5, 7));
  const d = Number(yyyyMmDd.slice(8, 10));
  const utc = new Date(Date.UTC(y, m - 1, d, 12, 0, 0));
  utc.setUTCDate(utc.getUTCDate() + n);
  const yy = utc.getUTCFullYear();
  const mm = String(utc.getUTCMonth() + 1).padStart(2, "0");
  const dd = String(utc.getUTCDate()).padStart(2, "0");
  return `${yy}-${mm}-${dd}`;
}
