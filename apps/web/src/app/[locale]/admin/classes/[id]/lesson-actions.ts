"use server";

import { revalidatePath } from "next/cache";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";

/**
 * Admin / teacher-facing server actions for the Lessons panel on the class
 * detail page. Mirrors {@link ./homework-actions.ts} — same error mapping,
 * same revalidation pattern, same three-state resources semantics — with
 * two important contract differences:
 *
 * <ol>
 *   <li><b>No {@code dueAt}.</b> Lessons are revisitable study material.</li>
 *   <li><b>No admin override of per-student completion.</b> The backend only
 *       exposes student self-service endpoints for lesson completion; the
 *       admin detail ships a read-only per-student completion sheet. A DTO
 *       for mutating someone else's completion exists nowhere (not on the
 *       controller, not on the service) — if that ever gets added it'll
 *       need its own action in this module.</li>
 * </ol>
 *
 * <p>The {@code resources} field is the full ordered list of content blocks
 * (PROSE / BOARD_WALKTHROUGH / MCQ / VIDEO). Task #53 ships the metadata
 * surface only — Tasks #54 / #55 light up the block editor that feeds the
 * hidden form field this module parses. Until then, the forms leave
 * {@code resources} untouched (PATCH) or omit it entirely (POST), which
 * produces an instructions-only lesson with zero blocks — still valid
 * according to the backend validator.
 */
const TZ_OFFSET = "+05:00";

export type LessonCompletionStatus = "NOT_STARTED" | "READ";

/**
 * Row on the admin lesson list. Mirrors the backend {@code LessonSummary}
 * record. {@code readCount} + ({@code rosterSize} - {@code readCount}) is
 * the "unread" tally; both explicit {@code NOT_STARTED} and null rows count
 * as unread (the backend only counts {@code READ}).
 */
export type LessonSummary = {
  id: string;
  classId: string;
  sessionId: string | null;
  /**
   * Start instant of the linked session, denormalised onto the summary so
   * the row can render a session pill without a separate lookup — and
   * without missing past sessions, which the upcoming-only sessions prop
   * excludes. Null when sessionId is null or the linked session is gone.
   */
  sessionStartsAt: string | null;
  title: string;
  /** Short author-facing instructions, or null. Rendered as a preview. */
  instructions: string | null;
  createdAt: string;
  archivedAt: string | null;
  rosterSize: number;
  readCount: number;
  /** Number of authored content blocks — useful for the meta strip. */
  blockCount: number;
};

export type LessonCompletionView = {
  studentUserId: string;
  displayName: string | null;
  status: LessonCompletionStatus | null;
  markedAt: string | null;
};

/**
 * Authored content block attached to a lesson. v1 emits four kinds
 * ({@code PROSE / BOARD_WALKTHROUGH / MCQ / VIDEO}); the shape stays open
 * so a future kind addition doesn't need a frontend type rev. Unlike
 * homework, the backend does NOT strip any fields on the wire — even the
 * MCQ's {@code correctIndex} ships to students (the viewer reveals after
 * selection; client-side reveal UX, not a security boundary).
 */
export type LessonResourceView = {
  id: string;
  position: number;
  kind: string;
  title: string | null;
  payload: Record<string, unknown>;
};

export type LessonDetail = {
  id: string;
  classId: string;
  sessionId: string | null;
  title: string;
  instructions: string | null;
  createdBy: string | null;
  createdAt: string;
  archivedAt: string | null;
  completions: LessonCompletionView[];
  resources: LessonResourceView[];
};

export type LessonActionState = {
  error?:
    | "invalid"
    | "unauthorised"
    | "forbidden"
    | "conflict"
    | "notfound"
    | "network"
    | "unknown";
};

const INITIAL_OK: LessonActionState = {};

function mapErrorStatus(
  status: number,
): NonNullable<LessonActionState["error"]> {
  if (status === 0) return "network";
  if (status === 401) return "unauthorised";
  if (status === 403) return "forbidden";
  if (status === 404) return "notfound";
  if (status === 409) return "conflict";
  if (status === 400 || status === 422) return "invalid";
  return "unknown";
}

function revalidateDetail(locale: string, classId: string) {
  revalidatePath(`/${locale}/admin/classes/${classId}`);
}

function lessonUrl(classId: string, suffix = ""): string {
  return `/api/v1/admin/classes/${classId}/lessons${suffix}`;
}

/**
 * Parsed authored block as future block editors will serialise it into the
 * hidden {@code resources} form field. Matches the same shape this module's
 * homework twin uses — keeps the editor-to-action contract uniform across
 * both features.
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
 *   <li>Empty string or {@code "[]"} → {@code resources: []} (clear all
 *       blocks on update, no-op on create since the backend accepts a zero
 *       blocks anyway).</li>
 *   <li>Non-empty JSON array → validated list (loose — the authoritative
 *       validation runs server-side via
 *       {@code LessonResourceValidator}).</li>
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

/** Shape expected by the backend {@code LessonResourceInput} DTO. */
function toBackendResource(r: ParsedResource): Record<string, unknown> {
  const payload: Record<string, unknown> = { kind: r.kind, payload: r.payload };
  if (r.title != null) payload.title = r.title;
  return payload;
}

// Explicitly reference TZ_OFFSET so future helpers (e.g. due-date parity on
// scheduled-release lessons) have a single source of truth to pick up.
void TZ_OFFSET;

// ------------------------------------------------------------------
// Detail fetch (used when the completion sheet is expanded)
// ------------------------------------------------------------------

export type LessonDetailResult =
  | { ok: true; detail: LessonDetail }
  | { ok: false; error: NonNullable<LessonActionState["error"]> };

export async function readLessonDetailAction(
  classId: string,
  lessonId: string,
): Promise<LessonDetailResult> {
  if (!classId || !lessonId) return { ok: false, error: "invalid" };
  const access = await getAccessToken();
  if (!access) return { ok: false, error: "unauthorised" };
  const res = await apiFetch<LessonDetail>(lessonUrl(classId, `/${lessonId}`), {
    accessToken: access,
  });
  if (!res.ok) return { ok: false, error: mapErrorStatus(res.error.status) };
  return { ok: true, detail: res.data };
}

// ------------------------------------------------------------------
// Create / update / archive
// ------------------------------------------------------------------

export async function createLessonAction(
  _prev: LessonActionState,
  formData: FormData,
): Promise<LessonActionState> {
  const classId = String(formData.get("classId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  const title = String(formData.get("title") ?? "").trim();
  const instructions = String(formData.get("instructions") ?? "").trim();
  const sessionIdRaw = String(formData.get("sessionId") ?? "").trim();

  if (!classId || !title) return { error: "invalid" };
  if (title.length > 200) return { error: "invalid" };

  const resources = parseResources(formData);
  if (!resources.ok) return { error: "invalid" };

  const body: Record<string, unknown> = { title };
  if (instructions) body.instructions = instructions;
  if (sessionIdRaw) body.sessionId = sessionIdRaw;
  // Only send resources when the (future) editor added at least one block.
  if (resources.resources && resources.resources.length > 0) {
    body.resources = resources.resources.map(toBackendResource);
  }

  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };

  const res = await apiFetch<LessonDetail>(lessonUrl(classId), {
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
 * present — omit to leave untouched. {@code clearSession} detaches a linked
 * session without having to reselect.
 */
export async function updateLessonAction(
  _prev: LessonActionState,
  formData: FormData,
): Promise<LessonActionState> {
  const classId = String(formData.get("classId") ?? "");
  const lessonId = String(formData.get("lessonId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  if (!classId || !lessonId) return { error: "invalid" };

  const title = String(formData.get("title") ?? "").trim();
  const instructions = String(formData.get("instructions") ?? "").trim();
  const sessionIdRaw = String(formData.get("sessionId") ?? "").trim();
  const clearSession = formData.get("clearSession") === "on";

  const body: Record<string, unknown> = {};
  if (title) {
    if (title.length > 200) return { error: "invalid" };
    body.title = title;
  }
  // Always send instructions when the field is present (empty string resets
  // to null via the backend's emptyToNull helper). A missing field leaves
  // it alone.
  if (formData.has("instructions")) {
    body.instructions = instructions;
  }
  if (clearSession) {
    body.clearSession = true;
  } else if (sessionIdRaw) {
    body.sessionId = sessionIdRaw;
  }

  // Three-state resources on update (same as homework):
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

  const res = await apiFetch<LessonDetail>(lessonUrl(classId, `/${lessonId}`), {
    method: "PATCH",
    accessToken: access,
    body: JSON.stringify(body),
  });
  if (!res.ok) return { error: mapErrorStatus(res.error.status) };
  revalidateDetail(locale, classId);
  return INITIAL_OK;
}

export async function archiveLessonAction(
  _prev: LessonActionState,
  formData: FormData,
): Promise<LessonActionState> {
  const classId = String(formData.get("classId") ?? "");
  const lessonId = String(formData.get("lessonId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  if (!classId || !lessonId) return { error: "invalid" };

  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };

  const res = await apiFetch<unknown>(lessonUrl(classId, `/${lessonId}`), {
    method: "DELETE",
    accessToken: access,
  });
  if (!res.ok) return { error: mapErrorStatus(res.error.status) };
  revalidateDetail(locale, classId);
  return INITIAL_OK;
}
