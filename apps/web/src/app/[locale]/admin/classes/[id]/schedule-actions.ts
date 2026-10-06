"use server";

import { revalidatePath } from "next/cache";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";

/**
 * Uzbekistan does not observe DST, so Asia/Tashkent is a fixed +05:00 all
 * year. That keeps wall-clock ↔ UTC conversion trivial in a Server Action
 * without pulling in a full zoneinfo library. If we ever support schools
 * in DST-observing regions we'll want a real tz-aware helper instead.
 */
const TZ_OFFSET = "+05:00";

export type ScheduleActionState = {
  error?: "invalid" | "unauthorised" | "forbidden" | "network" | "unknown";
};

const INITIAL_OK: ScheduleActionState = {};

function mapErrorStatus(
  status: number,
): NonNullable<ScheduleActionState["error"]> {
  if (status === 0) return "network";
  if (status === 401) return "unauthorised";
  if (status === 403) return "forbidden";
  if (status === 400 || status === 422) return "invalid";
  return "unknown";
}

async function sendJson(
  path: string,
  method: "POST" | "PATCH" | "DELETE",
  body?: unknown,
): Promise<ScheduleActionState> {
  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };
  const res = await apiFetch<unknown>(path, {
    method,
    accessToken: access,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) return { error: mapErrorStatus(res.error.status) };
  return INITIAL_OK;
}

/**
 * Compose a UTC ISO instant from a local wall-clock date + time in
 * {@link TZ_OFFSET}. We reject impossible parses up front so the server
 * never sees a malformed ISO string.
 */
function toInstant(dateStr: string, timeStr: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(dateStr)) return null;
  if (!/^\d{2}:\d{2}$/.test(timeStr)) return null;
  const iso = `${dateStr}T${timeStr}:00${TZ_OFFSET}`;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  return d.toISOString();
}

function revalidateDetail(locale: string, classId: string) {
  revalidatePath(`/${locale}/admin/classes/${classId}`);
}

// ------------------------------------------------------------------
// Patterns
// ------------------------------------------------------------------

/**
 * Create a recurring schedule pattern. The form posts wall-clock times
 * (HH:mm) plus an ISO date; the backend handles timezone expansion into
 * concrete sessions up to the 8-week horizon.
 */
export async function createPatternAction(
  _prev: ScheduleActionState,
  formData: FormData,
): Promise<ScheduleActionState> {
  const classId = String(formData.get("classId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  const dayOfWeek = String(formData.get("dayOfWeek") ?? "");
  const startTime = String(formData.get("startTime") ?? "");
  const endTime = String(formData.get("endTime") ?? "");
  const startsOn = String(formData.get("startsOn") ?? "");
  const endsOnRaw = String(formData.get("endsOn") ?? "").trim();

  if (!classId || !dayOfWeek || !startTime || !endTime || !startsOn) {
    return { error: "invalid" };
  }
  if (!/^\d{2}:\d{2}$/.test(startTime) || !/^\d{2}:\d{2}$/.test(endTime)) {
    return { error: "invalid" };
  }
  if (endTime <= startTime) {
    return { error: "invalid" };
  }

  const body: Record<string, unknown> = {
    dayOfWeek,
    startTime: `${startTime}:00`,
    endTime: `${endTime}:00`,
    startsOn,
  };
  if (endsOnRaw) body.endsOn = endsOnRaw;

  const out = await sendJson(`/api/v1/admin/classes/${classId}/patterns`, "POST", body);
  if (!out.error) revalidateDetail(locale, classId);
  return out;
}

export async function deletePatternAction(
  _prev: ScheduleActionState,
  formData: FormData,
): Promise<ScheduleActionState> {
  const classId = String(formData.get("classId") ?? "");
  const patternId = String(formData.get("patternId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  if (!classId || !patternId) return { error: "invalid" };

  const out = await sendJson(
    `/api/v1/admin/classes/${classId}/patterns/${patternId}`,
    "DELETE",
  );
  if (!out.error) revalidateDetail(locale, classId);
  return out;
}

// ------------------------------------------------------------------
// Sessions
// ------------------------------------------------------------------

/**
 * Add an ad-hoc (non-pattern) session. The form collects date + start/end
 * time in the school's local wall-clock; we compose UTC instants here
 * before hitting the API.
 */
export async function createSessionAction(
  _prev: ScheduleActionState,
  formData: FormData,
): Promise<ScheduleActionState> {
  const classId = String(formData.get("classId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  const date = String(formData.get("date") ?? "");
  const startTime = String(formData.get("startTime") ?? "");
  const endTime = String(formData.get("endTime") ?? "");
  const notes = String(formData.get("notes") ?? "").trim();

  if (!classId || !date || !startTime || !endTime) return { error: "invalid" };
  if (endTime <= startTime) return { error: "invalid" };

  const startsAt = toInstant(date, startTime);
  const endsAt = toInstant(date, endTime);
  if (!startsAt || !endsAt) return { error: "invalid" };

  const body: Record<string, unknown> = { startsAt, endsAt };
  if (notes) body.notes = notes;

  const out = await sendJson(
    `/api/v1/admin/classes/${classId}/sessions`,
    "POST",
    body,
  );
  if (!out.error) revalidateDetail(locale, classId);
  return out;
}

/**
 * Flip a session's status. Used both for "Cancel" and "Mark complete",
 * with the target status encoded in a hidden form field. The backend
 * treats status changes as a human override — the parent pattern's
 * regeneration pass will no longer touch this row.
 */
export async function updateSessionStatusAction(
  _prev: ScheduleActionState,
  formData: FormData,
): Promise<ScheduleActionState> {
  const classId = String(formData.get("classId") ?? "");
  const sessionId = String(formData.get("sessionId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  const status = String(formData.get("status") ?? "");
  if (!classId || !sessionId || !status) return { error: "invalid" };
  if (
    status !== "SCHEDULED" &&
    status !== "CANCELLED" &&
    status !== "COMPLETED"
  ) {
    return { error: "invalid" };
  }

  const out = await sendJson(
    `/api/v1/admin/classes/${classId}/sessions/${sessionId}`,
    "PATCH",
    { status },
  );
  if (!out.error) revalidateDetail(locale, classId);
  return out;
}

export async function deleteSessionAction(
  _prev: ScheduleActionState,
  formData: FormData,
): Promise<ScheduleActionState> {
  const classId = String(formData.get("classId") ?? "");
  const sessionId = String(formData.get("sessionId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  if (!classId || !sessionId) return { error: "invalid" };

  const out = await sendJson(
    `/api/v1/admin/classes/${classId}/sessions/${sessionId}`,
    "DELETE",
  );
  if (!out.error) revalidateDetail(locale, classId);
  return out;
}

// ------------------------------------------------------------------
// Attendance
// ------------------------------------------------------------------

export type AttendanceStatus = "PRESENT" | "ABSENT" | "LATE" | "EXCUSED";

export type AttendanceMark = {
  studentUserId: string;
  displayName: string | null;
  status: AttendanceStatus | null;
  note: string | null;
  markedBy: string | null;
  markedAt: string | null;
};

export type AttendanceSheet = {
  sessionId: string;
  classId: string;
  startsAt: string;
  endsAt: string;
  sessionStatus: "SCHEDULED" | "CANCELLED" | "COMPLETED";
  marks: AttendanceMark[];
};

export type AttendanceReadResult =
  | { ok: true; sheet: AttendanceSheet }
  | { ok: false; error: NonNullable<ScheduleActionState["error"]> };

/**
 * Load the attendance sheet for a single session. Returned as a discriminated
 * union rather than throwing so the client component can render an inline
 * error without a boundary.
 */
export async function readAttendanceAction(
  classId: string,
  sessionId: string,
): Promise<AttendanceReadResult> {
  if (!classId || !sessionId) return { ok: false, error: "invalid" };

  const access = await getAccessToken();
  if (!access) return { ok: false, error: "unauthorised" };

  const res = await apiFetch<AttendanceSheet>(
    `/api/v1/admin/classes/${classId}/sessions/${sessionId}/attendance`,
    { accessToken: access },
  );
  if (!res.ok) return { ok: false, error: mapErrorStatus(res.error.status) };
  return { ok: true, sheet: res.data };
}

/** Shape of the per-student edits the client sends back. */
export type AttendanceEntry = {
  studentUserId: string;
  /** Null clears the mark (returns the student to "unmarked"). */
  status: AttendanceStatus | null;
  note: string | null;
};

export type AttendanceSaveState = {
  error?: NonNullable<ScheduleActionState["error"]> | "conflict";
  /** On success we return the refreshed sheet inline so the UI can update without a round-trip. */
  sheet?: AttendanceSheet;
};

/**
 * Bulk-upsert attendance. Called directly (not via a `<form action>`) because
 * the payload — an arbitrary list of student marks — doesn't map cleanly onto
 * FormData. useActionState would still work but adds ceremony with no payoff
 * for this shape.
 */
export async function saveAttendanceAction(
  classId: string,
  sessionId: string,
  locale: string,
  entries: AttendanceEntry[],
): Promise<AttendanceSaveState> {
  if (!classId || !sessionId) return { error: "invalid" };
  if (!Array.isArray(entries) || entries.length === 0) return { error: "invalid" };

  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };

  const res = await apiFetch<AttendanceSheet>(
    `/api/v1/admin/classes/${classId}/sessions/${sessionId}/attendance`,
    {
      method: "PUT",
      accessToken: access,
      body: JSON.stringify({ entries }),
    },
  );
  if (!res.ok) {
    if (res.error.status === 409) return { error: "conflict" };
    return { error: mapErrorStatus(res.error.status) };
  }
  revalidateDetail(locale, classId);
  return { sheet: res.data };
}
