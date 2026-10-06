"use client";

import { useActionState, useMemo, useState } from "react";
import { useTranslations } from "next-intl";
import { useRouter, useSearchParams, usePathname } from "next/navigation";
import type { Route } from "next";
import {
  createPatternAction,
  createSessionAction,
  deletePatternAction,
  deleteSessionAction,
  updateSessionStatusAction,
  type ScheduleActionState,
} from "./schedule-actions";
import { AttendancePanel } from "./attendance-panel";
import { isOnDay } from "@/lib/class-detail-buckets";
import { DayFilterBanner } from "./day-filter-banner";

export type SchedulePattern = {
  id: string;
  classId: string;
  dayOfWeek:
    | "MONDAY"
    | "TUESDAY"
    | "WEDNESDAY"
    | "THURSDAY"
    | "FRIDAY"
    | "SATURDAY"
    | "SUNDAY";
  // LocalTime comes back as "HH:mm:ss" from the API.
  startTime: string;
  endTime: string;
  // LocalDate comes back as "YYYY-MM-DD".
  startsOn: string;
  endsOn: string | null;
  timezone: string;
};

export type ScheduleSession = {
  id: string;
  classId: string;
  patternId: string | null;
  startsAt: string;
  endsAt: string;
  status: "SCHEDULED" | "CANCELLED" | "COMPLETED";
  notes: string | null;
};

const DAYS_OF_WEEK: SchedulePattern["dayOfWeek"][] = [
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY",
  "SUNDAY",
];

const INITIAL: ScheduleActionState = {};

/** All rendering is in the class's timezone (currently fixed to Asia/Tashkent). */
const DISPLAY_TZ = "Asia/Tashkent";

/**
 * Strict YYYY-MM-DD validator for the {@code ?day} query param. A crafted URL
 * like {@code ?day=yesterday} falls back silently to "no day pinned" rather
 * than crashing the date math downstream — mirrors the calendar strip's own
 * defensive validator so the two stay in sync.
 */
function isValidYmd(s: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(s);
}

/**
 * Narrow view of {@link useTranslations}'s return type so sub-components can
 * accept it as a prop without wrangling next-intl's generics. Only the shapes
 * we use are listed — no-args lookup and an interpolated overload.
 */
type TFn = {
  (key: string): string;
  (key: string, values: Record<string, string | number | Date>): string;
};

/**
 * Schedule tab for the class detail page. Two stacked panels — recurring
 * patterns and upcoming sessions — styled as proper cards with a stronger
 * visual hierarchy than the roster panels above. Every mutation flows
 * through a Server Action, so the page rerenders with fresh data without
 * any ad-hoc client state.
 *
 * <p>When {@code canEdit} is false (teacher view) all the mutation affordances
 * are suppressed; the backend also rejects the underlying requests so the UI
 * flag is purely cosmetic.
 */
export function SchedulePanels({
  classId,
  locale,
  patterns,
  sessions,
  canEdit,
  canMarkAttendance,
}: {
  classId: string;
  locale: string;
  patterns: SchedulePattern[];
  sessions: ScheduleSession[];
  canEdit: boolean;
  /**
   * Whether to render the "Attendance" affordance on each session. Admins and
   * assigned teachers see it; everyone else (students, parents viewing via
   * admin URL, unassigned teachers) does not. The backend still double-checks
   * on both read and write.
   */
  canMarkAttendance: boolean;
}) {
  const t = useTranslations("admin.classes.schedule");

  return (
    <section className="schedule-section">
      <PatternsPanel
        classId={classId}
        locale={locale}
        patterns={patterns}
        canEdit={canEdit}
        t={t}
      />
      <SessionsPanel
        classId={classId}
        locale={locale}
        sessions={sessions}
        canEdit={canEdit}
        canMarkAttendance={canMarkAttendance}
        t={t}
      />
    </section>
  );
}

// ------------------------------------------------------------------
// Patterns
// ------------------------------------------------------------------

function PatternsPanel({
  classId,
  locale,
  patterns,
  canEdit,
  t,
}: {
  classId: string;
  locale: string;
  patterns: SchedulePattern[];
  canEdit: boolean;
  t: TFn;
}) {
  const [showAdd, setShowAdd] = useState(false);
  const sorted = useMemo(() => {
    const order = new Map(DAYS_OF_WEEK.map((d, i) => [d, i]));
    return [...patterns].sort((a, b) => {
      const da = order.get(a.dayOfWeek) ?? 0;
      const db = order.get(b.dayOfWeek) ?? 0;
      if (da !== db) return da - db;
      return a.startTime.localeCompare(b.startTime);
    });
  }, [patterns]);

  return (
    <article className="sched-card">
      <header className="sched-card__header">
        <h2 className="sched-card__title">{t("patterns.title")}</h2>
        {canEdit ? (
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            onClick={() => setShowAdd((v) => !v)}
          >
            {showAdd ? t("sessions.hideAddForm") : t("patterns.addCta")}
          </button>
        ) : null}
      </header>

      {canEdit && showAdd ? (
        <NewPatternForm classId={classId} locale={locale} t={t} />
      ) : null}

      {sorted.length === 0 ? (
        <p className="sched-empty">{t("patterns.empty")}</p>
      ) : (
        <ul className="sched-pattern-grid">
          {sorted.map((p) => (
            <li key={p.id} className="sched-pattern">
              <div className="sched-day-chip" aria-hidden="true">
                {dayAbbrev(p.dayOfWeek, t)}
              </div>
              <div className="sched-pattern__body">
                <div className="sched-pattern__time">
                  {trimSeconds(p.startTime)} – {trimSeconds(p.endTime)}
                </div>
                <div className="sched-pattern__meta">
                  {t("patterns.startsOn", { date: p.startsOn })}
                  {p.endsOn
                    ? ` · ${t("patterns.endsOn", { date: p.endsOn })}`
                    : ""}
                </div>
              </div>
              {canEdit ? (
                <DeletePatternButton
                  classId={classId}
                  patternId={p.id}
                  locale={locale}
                  t={t}
                />
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}

function DeletePatternButton({
  classId,
  patternId,
  locale,
  t,
}: {
  classId: string;
  patternId: string;
  locale: string;
  t: TFn;
}) {
  const [state, formAction, pending] = useActionState(deletePatternAction, INITIAL);
  return (
    <form action={formAction} className="sched-inline-form">
      <input type="hidden" name="classId" value={classId} />
      <input type="hidden" name="patternId" value={patternId} />
      <input type="hidden" name="locale" value={locale} />
      <button className="btn btn--ghost btn--sm" type="submit" disabled={pending}>
        {pending ? t("patterns.removing") : t("patterns.remove")}
      </button>
      {state.error ? (
        <span className="sched-field-error" role="alert">
          {t(`error.${state.error}`)}
        </span>
      ) : null}
    </form>
  );
}

function NewPatternForm({
  classId,
  locale,
  t,
}: {
  classId: string;
  locale: string;
  t: TFn;
}) {
  const [state, formAction, pending] = useActionState(createPatternAction, INITIAL);
  const todayInTz = useMemo(() => todayIso(DISPLAY_TZ), []);
  return (
    <form className="sched-form" action={formAction}>
      <input type="hidden" name="classId" value={classId} />
      <input type="hidden" name="locale" value={locale} />

      <div className="sched-form__grid">
        <label className="sched-field">
          <span className="sched-field__label">{t("patterns.dayLabel")}</span>
          <select name="dayOfWeek" defaultValue="MONDAY" required>
            {DAYS_OF_WEEK.map((d) => (
              <option key={d} value={d}>
                {t(`day.${d}`)}
              </option>
            ))}
          </select>
        </label>
        <label className="sched-field">
          <span className="sched-field__label">{t("patterns.startLabel")}</span>
          <input type="time" name="startTime" defaultValue="16:00" required />
        </label>
        <label className="sched-field">
          <span className="sched-field__label">{t("patterns.endLabel")}</span>
          <input type="time" name="endTime" defaultValue="17:30" required />
        </label>
        <label className="sched-field">
          <span className="sched-field__label">{t("patterns.startsOnLabel")}</span>
          <input type="date" name="startsOn" defaultValue={todayInTz} required />
        </label>
        <label className="sched-field">
          <span className="sched-field__label">{t("patterns.endsOnLabel")}</span>
          <input type="date" name="endsOn" />
        </label>
      </div>

      <div className="sched-form__footer">
        <button
          className="btn btn--primary btn--sm"
          type="submit"
          disabled={pending}
        >
          {pending ? t("patterns.adding") : t("patterns.addCta")}
        </button>
        {state.error ? (
          <p className="sched-form__error" role="alert">
            {t(`error.${state.error}`)}
          </p>
        ) : null}
      </div>
    </form>
  );
}

// ------------------------------------------------------------------
// Sessions
// ------------------------------------------------------------------

function SessionsPanel({
  classId,
  locale,
  sessions,
  canEdit,
  canMarkAttendance,
  t,
}: {
  classId: string;
  locale: string;
  sessions: ScheduleSession[];
  canEdit: boolean;
  canMarkAttendance: boolean;
  t: TFn;
}) {
  const [showAdd, setShowAdd] = useState(false);
  const [query, setQuery] = useState("");
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  // Read the Phase 2b calendar strip's day-pin param. A crafted URL falls
  // back silently (see {@link isValidYmd}). Unlike the homework + lesson
  // panels, there's no archived tab to suppress the filter on — sessions
  // are always in one list — so the gate is just shape-validity.
  const rawDay = searchParams.get("day");
  const dayFilter = rawDay != null && isValidYmd(rawDay) ? rawDay : null;

  // Filter before bucketing so the "This week" badge reflects what the user
  // is actually looking at, not the pre-filter population.
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return sessions;
    return sessions.filter((s) => (s.notes ?? "").toLowerCase().includes(q));
  }, [sessions, query]);

  // Day-filter layered on top of the notes query so search + pinned-day
  // compose. A session with no start time can't happen (non-nullable on the
  // DTO), so {@code s.startsAt} is always a well-formed ISO instant the
  // bucketing helper can classify.
  const dayFiltered = useMemo(() => {
    if (!dayFilter) return filtered;
    return filtered.filter((s) => isOnDay(s.startsAt, dayFilter));
  }, [filtered, dayFilter]);

  // Keep the existing ISO-week bucketing (it groups sessions at the week
  // grain which reads much better than a single flat list for a full term)
  // but stamp the week that contains today with "This week" and the next
  // one with "Next week" so a scanning teacher lands on the right section
  // without reading dates. Everything else keeps its date-range label.
  // When {@code ?day} is active the groups collapse to at most one week
  // (typically one session), but the "This week" highlight still fires if
  // the pinned day lands inside today's ISO week — preserves the visual
  // coherence with the calendar strip's own today-outline.
  const groups = useMemo(
    () => labelWeekGroups(groupByIsoWeek(dayFiltered, locale), t),
    [dayFiltered, locale, t],
  );

  // Strip {@code ?day} from the URL while preserving any other params
  // (e.g. the strip's {@code ?week}, a future {@code ?tab}, etc.). Mirrors
  // the homework + lesson panels' handler so every "Clear filter" click
  // works the same regardless of which panel the user started from.
  function onClearDay() {
    const params = new URLSearchParams(searchParams.toString());
    params.delete("day");
    const qs = params.toString();
    const url = (qs ? `${pathname}?${qs}` : pathname) as Route;
    router.replace(url, { scroll: false });
  }

  return (
    <article className="sched-card">
      <header className="sched-card__header">
        <h2 className="sched-card__title">{t("sessions.title")}</h2>
        {canEdit ? (
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            onClick={() => setShowAdd((v) => !v)}
          >
            {showAdd ? t("sessions.hideAddForm") : t("sessions.addOneOff")}
          </button>
        ) : null}
      </header>

      {canEdit && showAdd ? (
        <NewSessionForm classId={classId} locale={locale} t={t} />
      ) : null}

      {dayFilter ? (
        <DayFilterBanner
          day={dayFilter}
          count={dayFiltered.length}
          onClear={onClearDay}
          locale={locale}
        />
      ) : null}

      {sessions.length > 0 ? (
        <div className="hw-toolbar">
          <label className="hw-search">
            <span className="sr-only">{t("sessions.searchLabel")}</span>
            <input
              type="search"
              className="hw-search__input"
              placeholder={t("sessions.searchPlaceholder")}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              aria-label={t("sessions.searchLabel")}
            />
          </label>
        </div>
      ) : null}

      {sessions.length === 0 ? (
        <p className="sched-empty">{t("sessions.empty")}</p>
      ) : filtered.length === 0 ? (
        <p className="sched-empty">{t("sessions.emptySearch")}</p>
      ) : dayFilter && dayFiltered.length === 0 ? (
        <p className="sched-empty">{t("sessions.emptyDay")}</p>
      ) : (
        <div className="sched-weeks">
          {groups.map((g) => (
            <section
              key={g.key}
              className={`sched-week${
                g.highlight ? ` sched-week--${g.highlight}` : ""
              }`}
            >
              <h3 className="sched-week__title">
                {g.label}
                {g.subLabel ? (
                  <span className="sched-week__sub"> · {g.subLabel}</span>
                ) : null}
              </h3>
              <ul className="sched-session-list">
                {g.sessions.map((s) => (
                  <SessionRow
                    key={s.id}
                    classId={classId}
                    locale={locale}
                    session={s}
                    canEdit={canEdit}
                    canMarkAttendance={canMarkAttendance}
                    t={t}
                  />
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
    </article>
  );
}

function NewSessionForm({
  classId,
  locale,
  t,
}: {
  classId: string;
  locale: string;
  t: TFn;
}) {
  const [state, formAction, pending] = useActionState(createSessionAction, INITIAL);
  const todayInTz = useMemo(() => todayIso(DISPLAY_TZ), []);
  return (
    <form className="sched-form" action={formAction}>
      <input type="hidden" name="classId" value={classId} />
      <input type="hidden" name="locale" value={locale} />

      <div className="sched-form__grid">
        <label className="sched-field">
          <span className="sched-field__label">{t("sessions.dateLabel")}</span>
          <input type="date" name="date" defaultValue={todayInTz} required />
        </label>
        <label className="sched-field">
          <span className="sched-field__label">{t("sessions.startLabel")}</span>
          <input type="time" name="startTime" defaultValue="16:00" required />
        </label>
        <label className="sched-field">
          <span className="sched-field__label">{t("sessions.endLabel")}</span>
          <input type="time" name="endTime" defaultValue="17:30" required />
        </label>
      </div>

      <label className="sched-field">
        <span className="sched-field__label">{t("sessions.notesLabel")}</span>
        <input type="text" name="notes" maxLength={200} />
      </label>

      <div className="sched-form__footer">
        <button
          className="btn btn--primary btn--sm"
          type="submit"
          disabled={pending}
        >
          {pending ? t("sessions.adding") : t("sessions.addCta")}
        </button>
        {state.error ? (
          <p className="sched-form__error" role="alert">
            {t(`error.${state.error}`)}
          </p>
        ) : null}
      </div>
    </form>
  );
}

function SessionRow({
  classId,
  locale,
  session,
  canEdit,
  canMarkAttendance,
  t,
}: {
  classId: string;
  locale: string;
  session: ScheduleSession;
  canEdit: boolean;
  canMarkAttendance: boolean;
  t: TFn;
}) {
  const when = formatSessionWhen(session.startsAt, session.endsAt, locale);
  const statusClass =
    session.status === "SCHEDULED"
      ? "sched-status sched-status--scheduled"
      : session.status === "CANCELLED"
      ? "sched-status sched-status--cancelled"
      : "sched-status sched-status--completed";

  return (
    <li className="sched-session">
      <div className="sched-date-badge" aria-hidden="true">
        <span className="sched-date-badge__dow">{when.dayShort}</span>
        <span className="sched-date-badge__day">{when.dayNum}</span>
        <span className="sched-date-badge__mon">{when.monShort}</span>
      </div>

      <div className="sched-session__body">
        <div className="sched-session__line">
          <span className="sched-session__time">{when.time}</span>
          <span className={statusClass}>{t(`sessions.status.${session.status}`)}</span>
          {session.patternId ? null : (
            <span className="sched-tag">{t("sessions.oneOff")}</span>
          )}
        </div>
        {session.notes ? (
          <p className="sched-session__notes">{session.notes}</p>
        ) : null}
      </div>

      {canEdit ? (
        <div className="sched-session__actions">
          {session.status === "SCHEDULED" ? (
            <>
              <StatusChangeButton
                classId={classId}
                locale={locale}
                sessionId={session.id}
                target="CANCELLED"
                label={t("sessions.cancel")}
                pendingLabel={t("sessions.cancelling")}
                t={t}
              />
              <StatusChangeButton
                classId={classId}
                locale={locale}
                sessionId={session.id}
                target="COMPLETED"
                label={t("sessions.complete")}
                pendingLabel={t("sessions.completing")}
                t={t}
              />
            </>
          ) : (
            <StatusChangeButton
              classId={classId}
              locale={locale}
              sessionId={session.id}
              target="SCHEDULED"
              label={t("sessions.reopen")}
              pendingLabel={t("sessions.reopening")}
              t={t}
            />
          )}
          <DeleteSessionButton
            classId={classId}
            locale={locale}
            sessionId={session.id}
            t={t}
          />
        </div>
      ) : null}

      {canMarkAttendance ? (
        <div className="sched-session__attendance">
          <AttendancePanel
            classId={classId}
            sessionId={session.id}
            locale={locale}
            sessionStatus={session.status}
          />
        </div>
      ) : null}
    </li>
  );
}

function StatusChangeButton({
  classId,
  locale,
  sessionId,
  target,
  label,
  pendingLabel,
  t,
}: {
  classId: string;
  locale: string;
  sessionId: string;
  target: "SCHEDULED" | "CANCELLED" | "COMPLETED";
  label: string;
  pendingLabel: string;
  t: TFn;
}) {
  const [state, formAction, pending] = useActionState(
    updateSessionStatusAction,
    INITIAL,
  );
  return (
    <form action={formAction} className="sched-inline-form">
      <input type="hidden" name="classId" value={classId} />
      <input type="hidden" name="sessionId" value={sessionId} />
      <input type="hidden" name="locale" value={locale} />
      <input type="hidden" name="status" value={target} />
      <button
        type="submit"
        className="btn btn--ghost btn--sm"
        disabled={pending}
      >
        {pending ? pendingLabel : label}
      </button>
      {state.error ? (
        <span className="sched-field-error" role="alert">
          {t(`error.${state.error}`)}
        </span>
      ) : null}
    </form>
  );
}

function DeleteSessionButton({
  classId,
  locale,
  sessionId,
  t,
}: {
  classId: string;
  locale: string;
  sessionId: string;
  t: TFn;
}) {
  const [state, formAction, pending] = useActionState(deleteSessionAction, INITIAL);
  return (
    <form action={formAction} className="sched-inline-form">
      <input type="hidden" name="classId" value={classId} />
      <input type="hidden" name="sessionId" value={sessionId} />
      <input type="hidden" name="locale" value={locale} />
      <button
        type="submit"
        className="btn btn--ghost btn--sm"
        disabled={pending}
      >
        {pending ? t("sessions.deleting") : t("sessions.delete")}
      </button>
      {state.error ? (
        <span className="sched-field-error" role="alert">
          {t(`error.${state.error}`)}
        </span>
      ) : null}
    </form>
  );
}

// ------------------------------------------------------------------
// Pure helpers
// ------------------------------------------------------------------

function trimSeconds(hms: string): string {
  // "16:00:00" → "16:00". Guards against a future backend change that might
  // drop seconds already.
  const parts = hms.split(":");
  if (parts.length < 2) return hms;
  return `${parts[0]}:${parts[1]}`;
}

/**
 * Two-character (or three for CJK) abbreviation for the weekday chip.
 * We ask the translation layer for the full weekday name and take the
 * first character or two so every locale stays self-consistent.
 */
function dayAbbrev(dow: SchedulePattern["dayOfWeek"], t: TFn): string {
  const full = t(`day.${dow}`);
  // Pull the first "word" (handles "Juma" vs "Понедельник") and uppercase 2 chars.
  const word = full.split(/\s+/)[0] ?? full;
  return word.slice(0, 2).toUpperCase();
}

/** Today's date in the given IANA timezone, formatted as YYYY-MM-DD. */
function todayIso(timezone: string): string {
  const parts = new Intl.DateTimeFormat("sv-SE", {
    timeZone: timezone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  const y = parts.find((p) => p.type === "year")?.value ?? "1970";
  const m = parts.find((p) => p.type === "month")?.value ?? "01";
  const d = parts.find((p) => p.type === "day")?.value ?? "01";
  return `${y}-${m}-${d}`;
}

type SessionGroup = {
  key: string;
  label: string;
  sessions: ScheduleSession[];
  /**
   * Optional highlight modifier used by the section's CSS class. `undefined`
   * for regular weeks; `"this-week"` for the week containing today and
   * `"next-week"` for the one after. Lets a scanning teacher land on the
   * right section without reading dates.
   */
  highlight?: "this-week" | "next-week";
  /**
   * Optional badge ("This week" / "Next week") appended after the date
   * range in the section heading. Translated at the call site so the
   * helper can stay i18n-agnostic here.
   */
  subLabel?: string;
};

/**
 * Decorate the current week and next week with a {@link SessionGroup.highlight}
 * modifier + a translated {@link SessionGroup.subLabel}. All other weeks pass
 * through unchanged. The date arithmetic mirrors the bucket helper in
 * {@code /lib/class-detail-buckets}: compute the Monday of today's ISO week
 * in the display timezone, add seven days for next week, match on the group
 * key (which is already Monday's YYYY-MM-DD).
 */
function labelWeekGroups(groups: SessionGroup[], t: TFn): SessionGroup[] {
  const todayIsoDate = todayIso(DISPLAY_TZ);
  const thisMondayIso = isoWeekMondayFromYmd(todayIsoDate);
  const nextMondayIso = addDaysYmd(thisMondayIso, 7);
  const thisWeekBadge = t("sessions.thisWeekBadge");
  const nextWeekBadge = t("sessions.nextWeekBadge");
  return groups.map((g) => {
    if (g.key === thisMondayIso) {
      return { ...g, highlight: "this-week", subLabel: thisWeekBadge };
    }
    if (g.key === nextMondayIso) {
      return { ...g, highlight: "next-week", subLabel: nextWeekBadge };
    }
    return g;
  });
}

/** Monday (YYYY-MM-DD) of the ISO week containing a plain YYYY-MM-DD. */
function isoWeekMondayFromYmd(ymd: string): string {
  const y = Number(ymd.slice(0, 4));
  const m = Number(ymd.slice(5, 7));
  const d = Number(ymd.slice(8, 10));
  const utc = new Date(Date.UTC(y, m - 1, d, 12, 0, 0));
  const day = utc.getUTCDay();
  const iso = day === 0 ? 7 : day;
  utc.setUTCDate(utc.getUTCDate() - (iso - 1));
  const yy = utc.getUTCFullYear();
  const mm = String(utc.getUTCMonth() + 1).padStart(2, "0");
  const dd = String(utc.getUTCDate()).padStart(2, "0");
  return `${yy}-${mm}-${dd}`;
}

/** Add whole days to a YYYY-MM-DD string (supports negative n). */
function addDaysYmd(ymd: string, n: number): string {
  const y = Number(ymd.slice(0, 4));
  const m = Number(ymd.slice(5, 7));
  const d = Number(ymd.slice(8, 10));
  const utc = new Date(Date.UTC(y, m - 1, d, 12, 0, 0));
  utc.setUTCDate(utc.getUTCDate() + n);
  const yy = utc.getUTCFullYear();
  const mm = String(utc.getUTCMonth() + 1).padStart(2, "0");
  const dd = String(utc.getUTCDate()).padStart(2, "0");
  return `${yy}-${mm}-${dd}`;
}

/**
 * Bucket sessions by the Monday of their ISO week in the display timezone.
 * Labels use the week's Monday → Sunday range (e.g. "20 – 26 Apr") so a
 * teacher can scan at a glance.
 */
function groupByIsoWeek(sessions: ScheduleSession[], locale: string): SessionGroup[] {
  const tag = localeTag(locale);
  const dayFmt = new Intl.DateTimeFormat(tag, {
    timeZone: DISPLAY_TZ,
    day: "numeric",
  });
  const dayMonFmt = new Intl.DateTimeFormat(tag, {
    timeZone: DISPLAY_TZ,
    day: "numeric",
    month: "short",
  });

  const buckets = new Map<string, SessionGroup>();
  for (const s of sessions) {
    const mondayIso = isoWeekMonday(new Date(s.startsAt), DISPLAY_TZ);
    let bucket = buckets.get(mondayIso);
    if (!bucket) {
      const mondayDate = new Date(`${mondayIso}T00:00:00+05:00`);
      const sunday = new Date(mondayDate.getTime() + 6 * 86400000);
      const label = `${dayFmt.format(mondayDate)} – ${dayMonFmt.format(sunday)}`;
      bucket = { key: mondayIso, label, sessions: [] };
      buckets.set(mondayIso, bucket);
    }
    bucket.sessions.push(s);
  }
  return [...buckets.values()].sort((a, b) => a.key.localeCompare(b.key));
}

/** YYYY-MM-DD of the Monday of the ISO week containing {@code date}, in {@code tz}. */
function isoWeekMonday(date: Date, tz: string): string {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: tz,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    weekday: "short",
  }).formatToParts(date);
  const y = Number(parts.find((p) => p.type === "year")?.value);
  const m = Number(parts.find((p) => p.type === "month")?.value);
  const d = Number(parts.find((p) => p.type === "day")?.value);
  const wd = parts.find((p) => p.type === "weekday")?.value ?? "Mon";
  const order: Record<string, number> = {
    Mon: 1,
    Tue: 2,
    Wed: 3,
    Thu: 4,
    Fri: 5,
    Sat: 6,
    Sun: 7,
  };
  const iso = order[wd] ?? 1;
  const asDate = new Date(Date.UTC(y, m - 1, d));
  asDate.setUTCDate(asDate.getUTCDate() - (iso - 1));
  const yy = asDate.getUTCFullYear();
  const mm = String(asDate.getUTCMonth() + 1).padStart(2, "0");
  const dd = String(asDate.getUTCDate()).padStart(2, "0");
  return `${yy}-${mm}-${dd}`;
}

function formatSessionWhen(
  startsAtIso: string,
  endsAtIso: string,
  locale: string,
): { dayShort: string; dayNum: string; monShort: string; time: string } {
  const tag = localeTag(locale);
  const dowFmt = new Intl.DateTimeFormat(tag, {
    timeZone: DISPLAY_TZ,
    weekday: "short",
  });
  const dayFmt = new Intl.DateTimeFormat(tag, {
    timeZone: DISPLAY_TZ,
    day: "numeric",
  });
  const monFmt = new Intl.DateTimeFormat(tag, {
    timeZone: DISPLAY_TZ,
    month: "short",
  });
  const timeFmt = new Intl.DateTimeFormat(tag, {
    timeZone: DISPLAY_TZ,
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
  const start = new Date(startsAtIso);
  const end = new Date(endsAtIso);
  return {
    dayShort: dowFmt.format(start).toUpperCase().replace(/\.$/, ""),
    dayNum: dayFmt.format(start),
    monShort: monFmt.format(start).toUpperCase().replace(/\.$/, ""),
    time: `${timeFmt.format(start)} – ${timeFmt.format(end)}`,
  };
}

function localeTag(locale: string): string {
  // Next-intl locale codes align with BCP-47 well enough for the cases we
  // ship today; add a translation layer here if we ever need something like
  // uz-Cyrl vs uz-Latn.
  return locale;
}
