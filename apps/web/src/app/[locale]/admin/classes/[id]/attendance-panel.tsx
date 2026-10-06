"use client";

import { useState, useTransition } from "react";
import { useTranslations } from "next-intl";
import {
  readAttendanceAction,
  saveAttendanceAction,
  type AttendanceEntry,
  type AttendanceMark,
  type AttendanceSheet,
  type AttendanceStatus,
} from "./schedule-actions";

/**
 * Narrow subset of next-intl's `useTranslations` return type so we can pass it
 * as a prop without fighting the generics.
 */
type TFn = {
  (key: string): string;
  (key: string, values: Record<string, string | number | Date>): string;
};

const STATUSES: AttendanceStatus[] = ["PRESENT", "ABSENT", "LATE", "EXCUSED"];

/**
 * Expandable attendance sheet for a single session, inlined below the session
 * row. The panel starts collapsed; opening it kicks off a server-action fetch
 * for the current marks. The user can then edit statuses locally and submit
 * a bulk save, which re-returns the authoritative sheet.
 *
 * <p>We don't preload the sheet server-side — a class with twelve upcoming
 * sessions would trigger twelve attendance queries per page render, and
 * attendance is expanded lazily in the UI anyway.
 */
export function AttendancePanel({
  classId,
  sessionId,
  locale,
  sessionStatus,
  disabled,
}: {
  classId: string;
  sessionId: string;
  locale: string;
  /** Current session status — governs the "cancelled, can't mark" banner. */
  sessionStatus: "SCHEDULED" | "CANCELLED" | "COMPLETED";
  /** UI-level guard for roles that shouldn't even see the affordance. */
  disabled?: boolean;
}) {
  const t = useTranslations("admin.classes.attendance");
  const [open, setOpen] = useState(false);
  const [sheet, setSheet] = useState<AttendanceSheet | null>(null);
  const [edits, setEdits] = useState<Map<string, AttendanceEntry>>(new Map());
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, startSaving] = useTransition();

  async function toggle() {
    if (open) {
      setOpen(false);
      return;
    }
    setOpen(true);
    if (sheet) return; // cached, no refetch
    setLoading(true);
    setError(null);
    const res = await readAttendanceAction(classId, sessionId);
    setLoading(false);
    if (!res.ok) {
      setError(t(`error.${res.error}`));
      return;
    }
    setSheet(res.sheet);
    setEdits(new Map());
  }

  function setMark(studentId: string, next: AttendanceStatus | null, note: string | null) {
    setEdits((prev) => {
      const copy = new Map(prev);
      copy.set(studentId, { studentUserId: studentId, status: next, note });
      return copy;
    });
  }

  function effective(mark: AttendanceMark): {
    status: AttendanceStatus | null;
    note: string;
  } {
    const e = edits.get(mark.studentUserId);
    if (e) return { status: e.status, note: e.note ?? "" };
    return { status: mark.status, note: mark.note ?? "" };
  }

  function save() {
    if (edits.size === 0) return; // nothing to do
    const entries = [...edits.values()];
    setError(null);
    startSaving(async () => {
      const res = await saveAttendanceAction(classId, sessionId, locale, entries);
      if (res.error) {
        setError(t(`error.${res.error}`));
        return;
      }
      if (res.sheet) {
        setSheet(res.sheet);
        setEdits(new Map());
      }
    });
  }

  if (disabled) return null;

  return (
    <div className="att-panel">
      <button
        type="button"
        className="btn btn--ghost btn--sm att-panel__toggle"
        onClick={toggle}
        aria-expanded={open}
      >
        {open ? t("collapse") : t("expand")}
      </button>

      {open ? (
        <div className="att-panel__body">
          {loading ? (
            <p className="att-loading">{t("loading")}</p>
          ) : sheet === null ? (
            error ? (
              <p className="att-error" role="alert">{error}</p>
            ) : null
          ) : (
            <AttendanceEditor
              sheet={sheet}
              edits={edits}
              effective={effective}
              setMark={setMark}
              save={save}
              saving={saving}
              error={error}
              sessionStatus={sessionStatus}
              t={t}
            />
          )}
        </div>
      ) : null}
    </div>
  );
}

// ------------------------------------------------------------------

function AttendanceEditor({
  sheet,
  edits,
  effective,
  setMark,
  save,
  saving,
  error,
  sessionStatus,
  t,
}: {
  sheet: AttendanceSheet;
  edits: Map<string, AttendanceEntry>;
  effective: (m: AttendanceMark) => { status: AttendanceStatus | null; note: string };
  setMark: (studentId: string, next: AttendanceStatus | null, note: string | null) => void;
  save: () => void;
  saving: boolean;
  error: string | null;
  sessionStatus: "SCHEDULED" | "CANCELLED" | "COMPLETED";
  t: TFn;
}) {
  const canEdit = sessionStatus !== "CANCELLED";
  const dirtyCount = edits.size;

  if (sheet.marks.length === 0) {
    return <p className="att-empty">{t("empty")}</p>;
  }

  return (
    <>
      {sessionStatus === "CANCELLED" ? (
        <p className="att-note" role="note">{t("cancelledBanner")}</p>
      ) : null}

      <ul className="att-list">
        {sheet.marks.map((m) => {
          const eff = effective(m);
          return (
            <li key={m.studentUserId} className="att-row">
              <div className="att-row__name">
                {m.displayName ?? m.studentUserId}
              </div>
              <div className="att-row__status">
                {STATUSES.map((s) => {
                  const active = eff.status === s;
                  return (
                    <button
                      key={s}
                      type="button"
                      className={
                        "att-chip " +
                        (active ? `att-chip--active att-chip--${s.toLowerCase()}` : "")
                      }
                      aria-pressed={active}
                      disabled={!canEdit}
                      onClick={() => setMark(m.studentUserId, active ? null : s, eff.note || null)}
                    >
                      {t(`status.${s}`)}
                    </button>
                  );
                })}
              </div>
              <input
                type="text"
                className="att-row__note"
                placeholder={t("notePlaceholder")}
                value={eff.note}
                maxLength={500}
                disabled={!canEdit || eff.status === null}
                onChange={(e) =>
                  setMark(m.studentUserId, eff.status, e.target.value || null)
                }
              />
            </li>
          );
        })}
      </ul>

      <div className="att-footer">
        <button
          type="button"
          className="btn btn--primary btn--sm"
          onClick={save}
          disabled={!canEdit || saving || dirtyCount === 0}
          aria-busy={saving}
        >
          {saving ? t("saving") : t("save", { count: dirtyCount })}
        </button>
        {error ? (
          <span className="att-error" role="alert">{error}</span>
        ) : null}
      </div>
    </>
  );
}
