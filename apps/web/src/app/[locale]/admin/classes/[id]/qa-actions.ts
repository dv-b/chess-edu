"use server";

import { revalidatePath } from "next/cache";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";

/**
 * Admin / teacher server actions for the Q&A inbox panel on the class
 * detail page. Wraps the four endpoints that ship with Task #66:
 *
 * <ul>
 *   <li>GET /api/v1/admin/classes/{classId}/questions — inbox preview
 *       (called server-side from page.tsx, not from this module).</li>
 *   <li>GET /api/v1/admin/classes/{classId}/questions/{qid} — fetch full
 *       thread (head + replies) on expand.</li>
 *   <li>POST /api/v1/admin/classes/{classId}/questions/{qid}/replies —
 *       teacher reply.</li>
 *   <li>POST /api/v1/admin/classes/{classId}/questions/{qid}/resolve —
 *       close the thread.</li>
 * </ul>
 *
 * <p>Both write actions revalidate the class detail page so the count
 * badge on the tab strip + the inbox preview list refresh after a
 * resolve removes a thread or a reply changes the last-reply meta.
 */

// -----------------------------------------------------------------
// Types — mirror the backend DTOs verbatim
// -----------------------------------------------------------------

/** Polymorphic parent kind tag. */
export type BlockQuestionParentKind = "HOMEWORK" | "LESSON";

/** Heuristic role label on a reply (STUDENT == asker, TEACHER == else). */
export type BlockQuestionReplyRole = "STUDENT" | "TEACHER";

export type BlockQuestionView = {
  id: string;
  classId: string;
  parentKind: BlockQuestionParentKind;
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

export type BlockQuestionReplyView = {
  id: string;
  questionId: string;
  authorUserId: string;
  authorDisplayName: string | null;
  authorRole: BlockQuestionReplyRole;
  body: string;
  createdAt: string;
};

/** Full thread — what the read / reply / resolve endpoints return. */
export type BlockQuestionThreadView = {
  question: BlockQuestionView;
  parentTitle: string | null;
  replies: BlockQuestionReplyView[];
};

/** Inbox preview row — what the inbox endpoint returns per thread. */
export type BlockQuestionInboxRow = {
  question: BlockQuestionView;
  parentTitle: string | null;
  replyCount: number;
  lastReplyAt: string | null;
  lastReplyAuthorDisplayName: string | null;
};

// -----------------------------------------------------------------
// Error mapping — matches the homework + lesson action union shapes
// -----------------------------------------------------------------

export type QaErrorCode =
  | "invalid"
  | "unauthorised"
  | "forbidden"
  | "notfound"
  | "conflict"
  | "network"
  | "unknown";

function mapStatus(status: number): QaErrorCode {
  if (status === 401) return "unauthorised";
  if (status === 403) return "forbidden";
  if (status === 404) return "notfound";
  if (status === 409) return "conflict";
  if (status === 0) return "network";
  if (status >= 400 && status < 500) return "invalid";
  return "unknown";
}

// -----------------------------------------------------------------
// URL helpers
// -----------------------------------------------------------------

function questionsBase(classId: string): string {
  return `/api/v1/admin/classes/${classId}/questions`;
}

// -----------------------------------------------------------------
// Read action — fetch per-block overlay (every student's threads on
// one block of a homework / lesson). Drives the per-block QA panel
// inside the homework Submissions sheet.
// -----------------------------------------------------------------

export type FetchBlockOverlayResult =
  | { ok: true; threads: BlockQuestionThreadView[] }
  | { ok: false; error: QaErrorCode };

export async function fetchBlockOverlayAction(
  classId: string,
  parentKind: BlockQuestionParentKind,
  parentId: string,
  blockIndex: number,
): Promise<FetchBlockOverlayResult> {
  const access = await getAccessToken();
  if (!access) return { ok: false, error: "unauthorised" };

  const base = parentKind === "HOMEWORK" ? "homework" : "lessons";
  const res = await apiFetch<BlockQuestionThreadView[]>(
    `/api/v1/admin/classes/${classId}/${base}/${parentId}/blocks/${blockIndex}/questions`,
    { accessToken: access },
  );
  if (!res.ok) return { ok: false, error: mapStatus(res.error.status) };
  return { ok: true, threads: res.data };
}

// -----------------------------------------------------------------
// Read action — fetch full thread
// -----------------------------------------------------------------

export type FetchThreadResult =
  | { ok: true; thread: BlockQuestionThreadView }
  | { ok: false; error: QaErrorCode };

/**
 * Lazy-fetch a full thread (head + replies) when the user expands a
 * row. Called from the client component via `useTransition` /
 * `useActionState`.
 */
export async function fetchThreadAction(
  classId: string,
  questionId: string,
): Promise<FetchThreadResult> {
  const access = await getAccessToken();
  if (!access) return { ok: false, error: "unauthorised" };

  const res = await apiFetch<BlockQuestionThreadView>(
    `${questionsBase(classId)}/${questionId}`,
    { accessToken: access },
  );
  if (!res.ok) return { ok: false, error: mapStatus(res.error.status) };
  return { ok: true, thread: res.data };
}

// -----------------------------------------------------------------
// Write action — reply
// -----------------------------------------------------------------

export type ReplyActionState =
  | null
  | { ok: true; thread: BlockQuestionThreadView }
  | { error: QaErrorCode };

/**
 * Reply to a thread. `useActionState`-shaped so the caller can wire
 * the reply form natively.
 *
 * <p>FormData payload: `classId`, `questionId`, `locale`, `body`. Body
 * is trimmed + non-blank-checked client-side; the backend re-validates
 * (≤2000 chars, non-blank) and returns 400 on violation.
 */
export async function replyToThreadAction(
  _prev: ReplyActionState,
  formData: FormData,
): Promise<ReplyActionState> {
  const classId = String(formData.get("classId") ?? "");
  const questionId = String(formData.get("questionId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  const body = String(formData.get("body") ?? "").trim();

  if (!classId || !questionId) return { error: "unknown" };
  if (!body) return { error: "invalid" };

  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };

  const res = await apiFetch<BlockQuestionThreadView>(
    `${questionsBase(classId)}/${questionId}/replies`,
    {
      accessToken: access,
      method: "POST",
      body: JSON.stringify({ body }),
    },
  );
  if (!res.ok) return { error: mapStatus(res.error.status) };

  // Refresh the inbox preview + count badge.
  revalidatePath(`/${locale}/admin/classes/${classId}`);
  return { ok: true, thread: res.data };
}

// -----------------------------------------------------------------
// Write action — resolve
// -----------------------------------------------------------------

export type ResolveResult =
  | { ok: true; thread: BlockQuestionThreadView }
  | { ok: false; error: QaErrorCode };

/**
 * Mark a thread as resolved. Returns the assembled thread so the UI
 * can repaint with the resolved meta inline.
 */
export async function resolveThreadAction(
  classId: string,
  questionId: string,
  locale: string,
): Promise<ResolveResult> {
  const access = await getAccessToken();
  if (!access) return { ok: false, error: "unauthorised" };

  const res = await apiFetch<BlockQuestionThreadView>(
    `${questionsBase(classId)}/${questionId}/resolve`,
    {
      accessToken: access,
      method: "POST",
    },
  );
  if (!res.ok) return { ok: false, error: mapStatus(res.error.status) };

  revalidatePath(`/${locale}/admin/classes/${classId}`);
  return { ok: true, thread: res.data };
}
