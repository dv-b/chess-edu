"use server";

import { revalidatePath } from "next/cache";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";

/**
 * Student self-service Q&A actions, paired with the per-block
 * "Ask teacher" affordance on the homework + lesson detail pages.
 * Wraps the seven endpoints from Task #67:
 *
 * <ul>
 *   <li>GET  /api/v1/me/homework/{id}/blocks/{idx}/questions — list mine</li>
 *   <li>POST /api/v1/me/homework/{id}/blocks/{idx}/questions — open</li>
 *   <li>GET  /api/v1/me/lessons/{id}/blocks/{idx}/questions — list mine</li>
 *   <li>POST /api/v1/me/lessons/{id}/blocks/{idx}/questions — open</li>
 *   <li>GET  /api/v1/me/questions/{qid} — single thread (own only)</li>
 *   <li>POST /api/v1/me/questions/{qid}/replies — reply</li>
 *   <li>POST /api/v1/me/questions/{qid}/resolve — self-resolve</li>
 * </ul>
 *
 * <p>Revalidates the relevant detail page on every write so the page
 * stays consistent if the student bounces around. The panel itself
 * also splices fresh threads into local state for instant repaint.
 */

// -----------------------------------------------------------------
// Types — match backend DTOs
// -----------------------------------------------------------------

export type StudentQaParentKind = "HOMEWORK" | "LESSON";
export type StudentQaReplyRole = "STUDENT" | "TEACHER";

export type StudentQaQuestion = {
  id: string;
  classId: string;
  parentKind: StudentQaParentKind;
  parentId: string;
  blockIndex: number;
  studentUserId: string;
  studentDisplayName: string | null;
  body: string;
  resolvedAt: string | null;
  resolvedBy: string | null;
  resolvedByDisplayName: string | null;
  createdAt: string;
  updatedAt: string;
};

export type StudentQaReply = {
  id: string;
  questionId: string;
  authorUserId: string;
  authorDisplayName: string | null;
  authorRole: StudentQaReplyRole;
  body: string;
  createdAt: string;
};

export type StudentQaThread = {
  question: StudentQaQuestion;
  parentTitle: string | null;
  replies: StudentQaReply[];
};

// -----------------------------------------------------------------
// Error mapping
// -----------------------------------------------------------------

export type StudentQaError =
  | "invalid"
  | "unauthorised"
  | "forbidden"
  | "notfound"
  | "conflict"
  | "network"
  | "unknown";

function mapStatus(status: number): StudentQaError {
  if (status === 401) return "unauthorised";
  if (status === 403) return "forbidden";
  if (status === 404) return "notfound";
  if (status === 409) return "conflict";
  if (status === 0) return "network";
  if (status >= 400 && status < 500) return "invalid";
  return "unknown";
}

function detailPathFor(
  locale: string,
  parentKind: StudentQaParentKind,
  parentId: string,
): string {
  return parentKind === "HOMEWORK"
    ? `/${locale}/student/homework/${parentId}`
    : `/${locale}/student/lessons/${parentId}`;
}

function blockUrl(
  parentKind: StudentQaParentKind,
  parentId: string,
  blockIndex: number,
): string {
  const base = parentKind === "HOMEWORK" ? "homework" : "lessons";
  return `/api/v1/me/${base}/${parentId}/blocks/${blockIndex}/questions`;
}

// -----------------------------------------------------------------
// Read action — list own threads on a block
// -----------------------------------------------------------------

export type StudentQaListResult =
  | { ok: true; threads: StudentQaThread[] }
  | { ok: false; error: StudentQaError };

export async function fetchMyBlockThreadsAction(
  parentKind: StudentQaParentKind,
  parentId: string,
  blockIndex: number,
): Promise<StudentQaListResult> {
  const access = await getAccessToken();
  if (!access) return { ok: false, error: "unauthorised" };

  const res = await apiFetch<StudentQaThread[]>(
    blockUrl(parentKind, parentId, blockIndex),
    { accessToken: access },
  );
  if (!res.ok) return { ok: false, error: mapStatus(res.error.status) };
  return { ok: true, threads: res.data };
}

// -----------------------------------------------------------------
// Read action — cross-class rollup of all my threads (Lane B / B4)
// -----------------------------------------------------------------

/**
 * Fetch every thread the calling student has opened across all
 * classes, newest first. Powers the {@code /student/questions} list
 * page so the student can scan all conversations in one place.
 */
export async function fetchAllMyThreadsAction(): Promise<StudentQaListResult> {
  const access = await getAccessToken();
  if (!access) return { ok: false, error: "unauthorised" };

  const res = await apiFetch<StudentQaThread[]>("/api/v1/me/questions", {
    accessToken: access,
  });
  if (!res.ok) return { ok: false, error: mapStatus(res.error.status) };
  return { ok: true, threads: res.data };
}

// -----------------------------------------------------------------
// Write action — open a new thread
// -----------------------------------------------------------------

export type OpenThreadActionState =
  | null
  | { ok: true; thread: StudentQaThread }
  | { error: StudentQaError };

/**
 * Open a new thread. `useActionState`-shaped so the new-question
 * form binds directly. FormData payload: `parentKind`, `parentId`,
 * `blockIndex`, `locale`, `body`.
 */
export async function openMyThreadAction(
  _prev: OpenThreadActionState,
  formData: FormData,
): Promise<OpenThreadActionState> {
  const parentKind = String(formData.get("parentKind") ?? "") as StudentQaParentKind;
  const parentId = String(formData.get("parentId") ?? "");
  const blockIndexRaw = String(formData.get("blockIndex") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  const body = String(formData.get("body") ?? "").trim();

  if (parentKind !== "HOMEWORK" && parentKind !== "LESSON") {
    return { error: "unknown" };
  }
  const blockIndex = Number.parseInt(blockIndexRaw, 10);
  if (!parentId || !Number.isInteger(blockIndex) || blockIndex < 0) {
    return { error: "unknown" };
  }
  if (!body) return { error: "invalid" };

  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };

  const res = await apiFetch<StudentQaThread>(
    blockUrl(parentKind, parentId, blockIndex),
    {
      accessToken: access,
      method: "POST",
      body: JSON.stringify({ body }),
    },
  );
  if (!res.ok) return { error: mapStatus(res.error.status) };

  revalidatePath(detailPathFor(locale, parentKind, parentId));
  return { ok: true, thread: res.data };
}

// -----------------------------------------------------------------
// Write action — reply on existing thread
// -----------------------------------------------------------------

export type ReplyMyThreadActionState =
  | null
  | { ok: true; thread: StudentQaThread }
  | { error: StudentQaError };

/**
 * Reply on an existing thread. FormData: `questionId`, `parentKind`,
 * `parentId`, `locale`, `body`. The parent triple is only needed for
 * the revalidatePath; the backend authorises by question id +
 * caller identity.
 */
export async function replyMyThreadAction(
  _prev: ReplyMyThreadActionState,
  formData: FormData,
): Promise<ReplyMyThreadActionState> {
  const questionId = String(formData.get("questionId") ?? "");
  const parentKind = String(formData.get("parentKind") ?? "") as StudentQaParentKind;
  const parentId = String(formData.get("parentId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  const body = String(formData.get("body") ?? "").trim();

  if (!questionId) return { error: "unknown" };
  if (!body) return { error: "invalid" };

  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };

  const res = await apiFetch<StudentQaThread>(
    `/api/v1/me/questions/${questionId}/replies`,
    {
      accessToken: access,
      method: "POST",
      body: JSON.stringify({ body }),
    },
  );
  if (!res.ok) return { error: mapStatus(res.error.status) };

  if (parentKind === "HOMEWORK" || parentKind === "LESSON") {
    revalidatePath(detailPathFor(locale, parentKind, parentId));
  }
  return { ok: true, thread: res.data };
}

// -----------------------------------------------------------------
// Write action — self-resolve
// -----------------------------------------------------------------

export type ResolveMyThreadResult =
  | { ok: true; thread: StudentQaThread }
  | { ok: false; error: StudentQaError };

export async function resolveMyThreadAction(
  questionId: string,
  parentKind: StudentQaParentKind,
  parentId: string,
  locale: string,
): Promise<ResolveMyThreadResult> {
  const access = await getAccessToken();
  if (!access) return { ok: false, error: "unauthorised" };

  const res = await apiFetch<StudentQaThread>(
    `/api/v1/me/questions/${questionId}/resolve`,
    {
      accessToken: access,
      method: "POST",
    },
  );
  if (!res.ok) return { ok: false, error: mapStatus(res.error.status) };

  revalidatePath(detailPathFor(locale, parentKind, parentId));
  return { ok: true, thread: res.data };
}
