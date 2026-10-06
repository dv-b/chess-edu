"use server";

import { revalidatePath } from "next/cache";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";

/**
 * Uzbekistan is +05:00 year-round (no DST), so we can compose wall-clock
 * date + time inputs into UTC instants without pulling in zoneinfo.
 */
const TZ_OFFSET = "+05:00";

export type HomeworkSubmissionStatus = "NOT_STARTED" | "DONE" | "SKIPPED";

export type HomeworkSummary = {
  id: string;
  classId: string;
  sessionId: string | null;
  /**
   * Start instant of the linked session, denormalised onto the summary so the
   * row can render a session pill without a separate lookup (and without
   * missing past sessions, which the upcoming-only sessions prop excludes).
   * Null when sessionId is null or the linked session has been deleted.
   */
  sessionStartsAt: string | null;
  title: string;
  /** Short author-facing instructions, or null. Rendered as a preview. */
  instructions: string | null;
  dueAt: string | null;
  createdAt: string;
  archivedAt: string | null;
  rosterSize: number;
  doneCount: number;
  skippedCount: number;
};

export type HomeworkSubmissionView = {
  studentUserId: string;
  displayName: string | null;
  status: HomeworkSubmissionStatus | null;
  studentNote: string | null;
  submittedAt: string | null;
  markedBy: string | null;
  markedAt: string | null;
};

/**
 * Authored content block attached to a homework. v1 only knows {@code "BOARD"}
 * blocks, but the shape is open so future kinds (quizzes, videos) don't need a
 * frontend type rev. Extra fields on {@code payload} are preserved by the
 * structural typing — we only key off the ones we render.
 */
export type HomeworkResourceView = {
  id: string;
  position: number;
  kind: string;
  title: string | null;
  payload: Record<string, unknown>;
};

/**
 * Live block-level attempt as returned on the admin homework detail.
 * Mirrors the backend {@code BlockAttemptView} DTO. The admin surface
 * receives the full roster's live attempts (one per student/block);
 * replaced history rows are only available via the dedicated history
 * endpoint — this shape carries only the current row.
 */
export type BlockAttemptView = {
  id: string;
  homeworkId: string;
  studentUserId: string;
  blockIndex: number;
  moves: string[];
  status: "SUBMITTED" | "REVIEWED_OK" | "REVIEWED_NEEDS_WORK";
  teacherNote: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  solutionRevealedAt: string | null;
  replacedById: string | null;
  createdAt: string;
  updatedAt: string;
};

export type HomeworkDetail = {
  id: string;
  classId: string;
  sessionId: string | null;
  title: string;
  instructions: string | null;
  dueAt: string | null;
  createdBy: string | null;
  createdAt: string;
  archivedAt: string | null;
  submissions: HomeworkSubmissionView[];
  resources: HomeworkResourceView[];
  /**
   * Live per-student, per-block attempts. Empty when no student has
   * submitted anything on any block yet. Keyed logically by
   * {@code (studentUserId, blockIndex)} — the partial unique index in
   * the DB guarantees at most one live row per pair.
   */
  attempts: BlockAttemptView[];
};

export type HomeworkActionState = {
  error?:
    | "invalid"
    | "unauthorised"
    | "forbidden"
    | "conflict"
    | "notfound"
    | "network"
    | "unknown";
};

const INITIAL_OK: HomeworkActionState = {};

function mapErrorStatus(
  status: number,
): NonNullable<HomeworkActionState["error"]> {
  if (status === 0) return "network";
  if (status === 401) return "unauthorised";
  if (status === 403) return "forbidden";
  if (status === 404) return "notfound";
  if (status === 409) return "conflict";
  if (status === 400 || status === 422) return "invalid";
  return "unknown";
}

/** Compose a UTC ISO instant from a local date + time in {@link TZ_OFFSET}. */
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

function hwUrl(classId: string, suffix = ""): string {
  return `/api/v1/admin/classes/${classId}/homework${suffix}`;
}

/**
 * Parsed authored block as the editor serialises it into the hidden
 * {@code resources} form field. {@code payload} is whatever the editor put
 * together — the server validator is the source of truth for shape.
 */
type ParsedResource = {
  kind: string;
  title: string | null;
  payload: Record<string, unknown>;
};

type ResourcesParse =
  | { ok: true; resources: ParsedResource[] | null }
  | { ok: false };

/**
 * Pull the editor's serialised resources off the FormData. Conventions:
 * <ul>
 *   <li>No {@code resources} key → {@code resources: null} (leave alone on
 *       update; send nothing on create).</li>
 *   <li>Empty string or {@code "[]"} → {@code resources: []} (clear).</li>
 *   <li>Non-empty JSON array → validated list (loose — the authoritative
 *       validation runs server-side).</li>
 * </ul>
 */
function parseResources(formData: FormData): ResourcesParse {
  if (!formData.has("resources")) return { ok: true, resources: null };
  const raw = String(formData.get("resources") ?? "").trim();
  if (!raw) return { ok: true, resources: [] };
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { ok: false };
  }
  if (!Array.isArray(parsed)) return { ok: false };
  const out: ParsedResource[] = [];
  for (const item of parsed) {
    if (!item || typeof item !== "object") return { ok: false };
    const obj = item as Record<string, unknown>;
    const kind = typeof obj.kind === "string" ? obj.kind.trim() : "";
    if (!kind) return { ok: false };
    const titleRaw = obj.title;
    const title =
      typeof titleRaw === "string" && titleRaw.trim() !== ""
        ? titleRaw.trim()
        : null;
    const payload = obj.payload;
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      return { ok: false };
    }
    out.push({ kind, title, payload: payload as Record<string, unknown> });
  }
  return { ok: true, resources: out };
}

/** Shape expected by the backend {@code HomeworkResourceInput} DTO. */
function toBackendResource(r: ParsedResource): Record<string, unknown> {
  const payload: Record<string, unknown> = { kind: r.kind, payload: r.payload };
  if (r.title != null) payload.title = r.title;
  return payload;
}

// ------------------------------------------------------------------
// Detail fetch (used when the submission sheet is expanded)
// ------------------------------------------------------------------

export type HomeworkDetailResult =
  | { ok: true; detail: HomeworkDetail }
  | { ok: false; error: NonNullable<HomeworkActionState["error"]> };

export async function readHomeworkDetailAction(
  classId: string,
  homeworkId: string,
): Promise<HomeworkDetailResult> {
  if (!classId || !homeworkId) return { ok: false, error: "invalid" };
  const access = await getAccessToken();
  if (!access) return { ok: false, error: "unauthorised" };
  const res = await apiFetch<HomeworkDetail>(hwUrl(classId, `/${homeworkId}`), {
    accessToken: access,
  });
  if (!res.ok) return { ok: false, error: mapErrorStatus(res.error.status) };
  return { ok: true, detail: res.data };
}

// ------------------------------------------------------------------
// Create / update / archive
// ------------------------------------------------------------------

/**
 * Compose the dueAt body field from separate date + time fields. An empty
 * date leaves dueAt unset (assignment with no deadline). A partial pair
 * is invalid.
 */
function composeDueAt(dueDate: string, dueTime: string): string | null | "invalid" {
  if (!dueDate && !dueTime) return null;
  if (!dueDate || !dueTime) return "invalid";
  const iso = toInstant(dueDate, dueTime);
  if (!iso) return "invalid";
  return iso;
}

export async function createHomeworkAction(
  _prev: HomeworkActionState,
  formData: FormData,
): Promise<HomeworkActionState> {
  const classId = String(formData.get("classId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  const title = String(formData.get("title") ?? "").trim();
  const instructions = String(formData.get("instructions") ?? "").trim();
  const dueDate = String(formData.get("dueDate") ?? "").trim();
  const dueTime = String(formData.get("dueTime") ?? "").trim();
  const sessionIdRaw = String(formData.get("sessionId") ?? "").trim();

  if (!classId || !title) return { error: "invalid" };
  if (title.length > 200) return { error: "invalid" };

  const dueAt = composeDueAt(dueDate, dueTime);
  if (dueAt === "invalid") return { error: "invalid" };

  const resources = parseResources(formData);
  if (!resources.ok) return { error: "invalid" };

  const body: Record<string, unknown> = { title };
  if (instructions) body.instructions = instructions;
  if (dueAt) body.dueAt = dueAt;
  if (sessionIdRaw) body.sessionId = sessionIdRaw;
  // On create, only send the resources field when the editor was touched and
  // produced at least one block. An empty list would be a pointless no-op.
  if (resources.resources && resources.resources.length > 0) {
    body.resources = resources.resources.map(toBackendResource);
  }

  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };

  const res = await apiFetch<HomeworkDetail>(hwUrl(classId), {
    method: "POST",
    accessToken: access,
    body: JSON.stringify(body),
  });
  if (!res.ok) return { error: mapErrorStatus(res.error.status) };
  revalidateDetail(locale, classId);
  return INITIAL_OK;
}

/**
 * Partial update. Only fields the caller actually wants to change should be
 * present — omit to leave untouched. Explicit "clear" flags are used for
 * dueAt and sessionId so the user can detach them without having to reselect.
 */
export async function updateHomeworkAction(
  _prev: HomeworkActionState,
  formData: FormData,
): Promise<HomeworkActionState> {
  const classId = String(formData.get("classId") ?? "");
  const homeworkId = String(formData.get("homeworkId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  if (!classId || !homeworkId) return { error: "invalid" };

  const title = String(formData.get("title") ?? "").trim();
  const instructions = String(formData.get("instructions") ?? "").trim();
  const dueDate = String(formData.get("dueDate") ?? "").trim();
  const dueTime = String(formData.get("dueTime") ?? "").trim();
  const clearDueAt = formData.get("clearDueAt") === "on";
  const sessionIdRaw = String(formData.get("sessionId") ?? "").trim();
  const clearSession = formData.get("clearSession") === "on";

  const body: Record<string, unknown> = {};
  if (title) {
    if (title.length > 200) return { error: "invalid" };
    body.title = title;
  }
  // Always send instructions when present (empty string resets to null via
  // the backend's emptyToNull helper). A missing field leaves it alone.
  if (formData.has("instructions")) {
    body.instructions = instructions;
  }
  if (clearDueAt) {
    body.clearDueAt = true;
  } else if (dueDate || dueTime) {
    const dueAt = composeDueAt(dueDate, dueTime);
    if (dueAt === "invalid") return { error: "invalid" };
    if (dueAt) body.dueAt = dueAt;
  }
  if (clearSession) {
    body.clearSession = true;
  } else if (sessionIdRaw) {
    body.sessionId = sessionIdRaw;
  }

  // Three-state resources on update:
  //   no field in FormData   → leave existing blocks alone
  //   field present, empty   → clear all blocks
  //   field present, list    → replace
  const resources = parseResources(formData);
  if (!resources.ok) return { error: "invalid" };
  if (resources.resources !== null) {
    body.resources = resources.resources.map(toBackendResource);
  }

  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };

  const res = await apiFetch<HomeworkDetail>(hwUrl(classId, `/${homeworkId}`), {
    method: "PATCH",
    accessToken: access,
    body: JSON.stringify(body),
  });
  if (!res.ok) return { error: mapErrorStatus(res.error.status) };
  revalidateDetail(locale, classId);
  return INITIAL_OK;
}

export async function archiveHomeworkAction(
  _prev: HomeworkActionState,
  formData: FormData,
): Promise<HomeworkActionState> {
  const classId = String(formData.get("classId") ?? "");
  const homeworkId = String(formData.get("homeworkId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  if (!classId || !homeworkId) return { error: "invalid" };

  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };

  const res = await apiFetch<unknown>(hwUrl(classId, `/${homeworkId}`), {
    method: "DELETE",
    accessToken: access,
  });
  if (!res.ok) return { error: mapErrorStatus(res.error.status) };
  revalidateDetail(locale, classId);
  return INITIAL_OK;
}

// ------------------------------------------------------------------
// Submission override (teacher / admin self-service for a student)
// ------------------------------------------------------------------

export type UpdateSubmissionResult =
  | { ok: true; detail: HomeworkDetail }
  | { ok: false; error: NonNullable<HomeworkActionState["error"]> };

/**
 * Upsert (or clear, when {@code status === null}) one student's submission.
 * Called directly from the expanded submission sheet — no useActionState
 * ceremony because the payload doesn't map to FormData cleanly and the UI
 * wants the refreshed detail inline for snappy reconciliation.
 */
export async function updateHomeworkSubmissionAction(
  classId: string,
  homeworkId: string,
  studentUserId: string,
  locale: string,
  status: HomeworkSubmissionStatus | null,
  studentNote: string | null,
): Promise<UpdateSubmissionResult> {
  if (!classId || !homeworkId || !studentUserId) {
    return { ok: false, error: "invalid" };
  }

  const access = await getAccessToken();
  if (!access) return { ok: false, error: "unauthorised" };

  const body: Record<string, unknown> = { status };
  if (studentNote != null) body.studentNote = studentNote;

  const res = await apiFetch<HomeworkDetail>(
    hwUrl(classId, `/${homeworkId}/submissions/${studentUserId}`),
    {
      method: "PUT",
      accessToken: access,
      body: JSON.stringify(body),
    },
  );
  if (!res.ok) return { ok: false, error: mapErrorStatus(res.error.status) };
  revalidateDetail(locale, classId);
  return { ok: true, detail: res.data };
}

// ------------------------------------------------------------------
// Block-level attempt review (Task #40)
// ------------------------------------------------------------------

/**
 * Discriminated result for {@link reviewBlockAttemptAction}. Returns the
 * fresh {@link BlockAttemptView} on success so the caller can splice it
 * into its local list without a full detail refetch — mirrors the pattern
 * already used by {@code submitBlockAttemptAction} on the student side.
 */
export type ReviewBlockAttemptResult =
  | { ok: true; attempt: BlockAttemptView }
  | { ok: false; error: NonNullable<HomeworkActionState["error"]> };

/**
 * Review a single live block-attempt. Wraps the backend endpoint
 * {@code POST /api/v1/admin/classes/{classId}/homework/{homeworkId}/attempts/{attemptId}/review}.
 *
 * <p>{@code teacherNote} is sent as-is (trimmed by the caller); the
 * backend's service treats empty-or-whitespace as null so either empty
 * string or explicit null works. A 409 from the server means the row is
 * no longer live (the student resubmitted between the admin opening the
 * sheet and clicking) — surface to the UI so it can invalidate its
 * cached detail.
 */
export async function reviewBlockAttemptAction(
  classId: string,
  homeworkId: string,
  attemptId: string,
  status: "REVIEWED_OK" | "REVIEWED_NEEDS_WORK",
  teacherNote: string | null,
  locale: string,
): Promise<ReviewBlockAttemptResult> {
  if (!classId || !homeworkId || !attemptId) {
    return { ok: false, error: "invalid" };
  }

  const access = await getAccessToken();
  if (!access) return { ok: false, error: "unauthorised" };

  const body: Record<string, unknown> = { status };
  if (teacherNote != null) body.teacherNote = teacherNote;

  const res = await apiFetch<BlockAttemptView>(
    hwUrl(
      classId,
      `/${homeworkId}/attempts/${attemptId}/review`,
    ),
    {
      method: "POST",
      accessToken: access,
      body: JSON.stringify(body),
    },
  );
  if (!res.ok) return { ok: false, error: mapErrorStatus(res.error.status) };
  revalidateDetail(locale, classId);
  return { ok: true, attempt: res.data };
}

// -----------------------------------------------------------------
// Read action — student attempt history on a block
// -----------------------------------------------------------------

export type AttemptHistoryResult =
  | { ok: true; history: BlockAttemptView[] }
  | { ok: false; error: NonNullable<HomeworkActionState["error"]> };

/**
 * Fetch the full attempt history for one (homework, student, block)
 * triple, oldest first. Wraps the backend endpoint:
 * {@code GET /api/v1/admin/classes/{classId}/homework/{homeworkId}/students/{studentUserId}/blocks/{blockIndex}/attempts}.
 *
 * <p>Used by the "View past attempts" disclosure inside each
 * AttemptReviewCard on the Submissions sheet — lets a teacher
 * scroll back through prior submissions without leaving the sheet.
 * The returned list includes both replaced and live rows; the live
 * row is the one whose `replacedById` is null.
 */
export async function fetchAttemptHistoryAction(
  classId: string,
  homeworkId: string,
  studentUserId: string,
  blockIndex: number,
): Promise<AttemptHistoryResult> {
  if (!classId || !homeworkId || !studentUserId || blockIndex < 0) {
    return { ok: false, error: "invalid" };
  }

  const access = await getAccessToken();
  if (!access) return { ok: false, error: "unauthorised" };

  const res = await apiFetch<BlockAttemptView[]>(
    hwUrl(
      classId,
      `/${homeworkId}/students/${studentUserId}/blocks/${blockIndex}/attempts`,
    ),
    { accessToken: access },
  );
  if (!res.ok) return { ok: false, error: mapErrorStatus(res.error.status) };
  return { ok: true, history: res.data };
}
