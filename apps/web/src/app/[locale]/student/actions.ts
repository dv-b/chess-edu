"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { apiFetch } from "@/lib/api";
import { clearSession, getAccessToken, getRefreshToken } from "@/lib/session";
import { routing, type AppLocale } from "@/i18n/routing";

export type StudentSubmissionStatus = "NOT_STARTED" | "DONE" | "SKIPPED";

/**
 * Lesson completion states the student self-service endpoint accepts.
 *
 * <p>Contrast with {@link StudentSubmissionStatus}: lessons have a binary
 * READ / NOT_STARTED grain (plus the implicit "no row yet" state surfaced
 * to the client as {@code null}). Unlike the homework equivalent, the
 * backend <em>accepts</em> an explicit {@code NOT_STARTED} write so the
 * "opened-but-didn't-finish" trail is preserved — see the
 * {@code LessonCompletionStatus} enum docstring on the backend.
 */
export type StudentLessonCompletionStatus = "NOT_STARTED" | "READ";

export type StudentActionState = {
  error?:
    | "invalid"
    | "unauthorised"
    | "forbidden"
    | "conflict"
    | "notfound"
    | "network"
    | "unknown";
};

/**
 * Read shape returned by the attempt endpoints — mirrors the backend
 * {@code BlockAttemptView} DTO. Re-exported here so the client panels
 * can type the action's return value.
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

/**
 * Discriminated result for {@link submitBlockAttemptAction}. Clients render
 * the attempt on success and the error code on failure — separate from the
 * form-driven {@link StudentActionState} because attempt submission is
 * invoked from a button handler (not a plain form) and the caller wants the
 * fresh attempt back to update its local view optimistically.
 */
export type SubmitBlockAttemptResult =
  | { ok: true; attempt: BlockAttemptView }
  | { ok: false; error: NonNullable<StudentActionState["error"]> };

const INITIAL_OK: StudentActionState = {};

function normalizeLocale(value: FormDataEntryValue | null): AppLocale {
  const str = String(value ?? "");
  return (routing.locales as readonly string[]).includes(str)
    ? (str as AppLocale)
    : routing.defaultLocale;
}

function mapErrorStatus(
  status: number,
): NonNullable<StudentActionState["error"]> {
  if (status === 0) return "network";
  if (status === 401) return "unauthorised";
  if (status === 403) return "forbidden";
  if (status === 404) return "notfound";
  if (status === 409) return "conflict";
  if (status === 400 || status === 422) return "invalid";
  return "unknown";
}

/**
 * Upsert (or clear, when {@code status === null}) the caller's own
 * submission on a homework. Matches the backend's {@code /me/homework/{id}/status}
 * endpoint and its "send null to clear" convention.
 *
 * <p>This is a FormData-driven server action so the student detail page
 * can drop in plain {@code <form>} elements (one per status chip) without
 * having to hand-roll optimistic state — server-side revalidation will
 * bring the detail back with the new status.
 */
export async function updateMyHomeworkAction(
  _prev: StudentActionState,
  formData: FormData,
): Promise<StudentActionState> {
  const homeworkId = String(formData.get("homeworkId") ?? "");
  const locale = normalizeLocale(formData.get("locale"));
  const statusRaw = String(formData.get("status") ?? "").toUpperCase();
  if (!homeworkId) return { error: "invalid" };

  const allowed = ["DONE", "SKIPPED", ""] as const;
  if (!(allowed as readonly string[]).includes(statusRaw)) {
    return { error: "invalid" };
  }

  // Empty string means "clear". Anything else passes through as-is.
  const body: Record<string, unknown> = {
    status: statusRaw === "" ? null : statusRaw,
  };

  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };

  const res = await apiFetch<unknown>(
    `/api/v1/me/homework/${homeworkId}/status`,
    {
      method: "PUT",
      accessToken: access,
      body: JSON.stringify(body),
    },
  );
  if (!res.ok) return { error: mapErrorStatus(res.error.status) };

  // Revalidate both the detail and the list so the tally on the list page
  // updates after a round-trip.
  revalidatePath(`/${locale}/student`);
  revalidatePath(`/${locale}/student/homework/${homeworkId}`);
  return INITIAL_OK;
}

/**
 * Submit a set of UCI moves as the caller's attempt on one block of one
 * homework. Mirrors the backend's
 * {@code POST /api/v1/me/homework/{id}/blocks/{blockIndex}/attempts}
 * endpoint, which handles the overwrite / live-row chain server-side.
 *
 * <p>This is invoked directly from a button handler (not a form), so it
 * takes the inputs as plain arguments and returns the fresh attempt on
 * success. The detail page is revalidated so any subsequent navigation
 * picks up the new state; callers typically also stash the returned
 * attempt in local state for immediate feedback without waiting for the
 * full round-trip.
 *
 * @param homeworkId   the target homework's UUID
 * @param blockIndex   0-based position of the block inside the homework
 * @param moves        UCI move list to submit (non-empty; server validates)
 * @param locale       used to revalidate the correct detail path
 */
export async function submitBlockAttemptAction(
  homeworkId: string,
  blockIndex: number,
  moves: string[],
  locale: AppLocale,
): Promise<SubmitBlockAttemptResult> {
  if (
    !homeworkId ||
    !Number.isInteger(blockIndex) ||
    blockIndex < 0 ||
    !Array.isArray(moves) ||
    moves.length === 0
  ) {
    return { ok: false, error: "invalid" };
  }

  const access = await getAccessToken();
  if (!access) return { ok: false, error: "unauthorised" };

  const res = await apiFetch<BlockAttemptView>(
    `/api/v1/me/homework/${homeworkId}/blocks/${blockIndex}/attempts`,
    {
      method: "POST",
      accessToken: access,
      body: JSON.stringify({ moves }),
    },
  );
  if (!res.ok) {
    return { ok: false, error: mapErrorStatus(res.error.status) };
  }

  // Bring the cached detail page in line with the new attempt. The list
  // page doesn't depend on block-level attempts so skip it here.
  const safeLocale = (routing.locales as readonly string[]).includes(locale)
    ? locale
    : routing.defaultLocale;
  revalidatePath(`/${safeLocale}/student/homework/${homeworkId}`);
  return { ok: true, attempt: res.data };
}

/**
 * Result of {@link revealSolutionAction}. Mirrors
 * {@link SubmitBlockAttemptResult} but with the reveal endpoint's
 * response shape: a {@link BlockAttemptView} with {@code solutionRevealedAt}
 * stamped.
 *
 * <p>The authored {@code solution.uci} itself arrives on the next fetch of
 * the student homework detail (the backend un-strips it once the block has
 * a revealed attempt). Callers typically pair this action with a
 * {@code router.refresh()} so the server component re-fetches and the
 * solution flows through to the viewer.
 */
export type RevealSolutionResult =
  | { ok: true; attempt: BlockAttemptView }
  | { ok: false; error: NonNullable<StudentActionState["error"]> };

/**
 * Reveal the authored solution for one block, turning the student's live
 * {@code REVIEWED_NEEDS_WORK} attempt into the derived
 * {@code SOLUTION_REVEALED} display state (the row's persisted status
 * stays NEEDS_WORK — the flag is {@code solutionRevealedAt}).
 *
 * <p>The backend returns 409 if there's no live NEEDS_WORK attempt OR if
 * the block has no authored {@code solution.uci}. The UI maps both to
 * {@code "conflict"} and surfaces a friendly notice — there's no way to
 * distinguish them from the wire without a dedicated error code, so the
 * copy reads "Solution isn't available for this block" which covers both.
 * The call is idempotent: a second reveal on the same attempt returns the
 * same timestamp.
 */
export async function revealSolutionAction(
  homeworkId: string,
  blockIndex: number,
  locale: AppLocale,
): Promise<RevealSolutionResult> {
  if (!homeworkId || !Number.isInteger(blockIndex) || blockIndex < 0) {
    return { ok: false, error: "invalid" };
  }

  const access = await getAccessToken();
  if (!access) return { ok: false, error: "unauthorised" };

  const res = await apiFetch<BlockAttemptView>(
    `/api/v1/me/homework/${homeworkId}/blocks/${blockIndex}/reveal-solution`,
    {
      method: "POST",
      accessToken: access,
    },
  );
  if (!res.ok) {
    return { ok: false, error: mapErrorStatus(res.error.status) };
  }

  const safeLocale = (routing.locales as readonly string[]).includes(locale)
    ? locale
    : routing.defaultLocale;
  // Revalidate so the next render of the student detail page ships the
  // un-stripped solution payload. The caller also typically kicks a
  // {@code router.refresh()} inside a transition for immediate feedback.
  revalidatePath(`/${safeLocale}/student/homework/${homeworkId}`);
  return { ok: true, attempt: res.data };
}

/**
 * Form-action variant of {@link updateMyHomeworkAction} that ignores the
 * {@code useActionState} "prev" argument so a plain
 * {@code <form action={finishHomeworkAction}>} can bind directly. Used
 * by the list-page "Mark as finished" micro-action, which only appears
 * when every block is in a terminal state (reviewed OK / needs-work /
 * solution revealed).
 *
 * <p>Errors are intentionally swallowed silently — the list page is a
 * server component without a place to surface a per-card error banner,
 * and the worst case ("status didn't flip") is recoverable by opening
 * the detail page where the same chip exists with full error handling.
 */
export async function finishHomeworkAction(formData: FormData): Promise<void> {
  await updateMyHomeworkAction({}, formData);
}

/**
 * Upsert (or clear, when {@code status === null}) the caller's own
 * completion on a lesson. Matches the backend's
 * {@code PUT /api/v1/me/lessons/{id}/completion} endpoint and its
 * "send null to clear" convention.
 *
 * <p>Form-action shape mirrors {@link updateMyHomeworkAction} so the
 * lesson detail page (once it lands in Task #57 / #58) can drop in
 * plain {@code <form>} chips per status without hand-rolling optimistic
 * state. Unlike the homework twin, an explicit {@code NOT_STARTED}
 * write is accepted — that's a deliberate backend contract: lessons
 * are revisitable study material and the "opened but didn't finish"
 * state is meaningful signal for the admin completion sheet.
 *
 * <p>Allowed raw form values: {@code "READ"}, {@code "NOT_STARTED"},
 * and empty string (clear → null). Anything else is rejected with
 * {@code "invalid"} client-side without a round-trip.
 */
export async function updateMyLessonCompletionAction(
  _prev: StudentActionState,
  formData: FormData,
): Promise<StudentActionState> {
  const lessonId = String(formData.get("lessonId") ?? "");
  const locale = normalizeLocale(formData.get("locale"));
  const statusRaw = String(formData.get("status") ?? "").toUpperCase();
  if (!lessonId) return { error: "invalid" };

  const allowed = ["READ", "NOT_STARTED", ""] as const;
  if (!(allowed as readonly string[]).includes(statusRaw)) {
    return { error: "invalid" };
  }

  // Empty string means "clear". Anything else passes through as-is.
  const body: Record<string, unknown> = {
    status: statusRaw === "" ? null : statusRaw,
  };

  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };

  const res = await apiFetch<unknown>(
    `/api/v1/me/lessons/${lessonId}/completion`,
    {
      method: "PUT",
      accessToken: access,
      body: JSON.stringify(body),
    },
  );
  if (!res.ok) return { error: mapErrorStatus(res.error.status) };

  // Revalidate both the list and the (forthcoming) detail so the badge
  // in the layout + the row-level chip both pick up the new state after
  // a round-trip.
  revalidatePath(`/${locale}/student/lessons`);
  revalidatePath(`/${locale}/student/lessons/${lessonId}`);
  return INITIAL_OK;
}

/**
 * Form-action variant of {@link updateMyLessonCompletionAction} that
 * ignores the {@code useActionState} "prev" argument so a plain
 * {@code <form action={finishLessonAction}>} can bind directly. Used
 * by the list-page "Mark as read" micro-action on each lesson card.
 *
 * <p>Mirrors {@link finishHomeworkAction}: errors are intentionally
 * swallowed silently — the list page is a server component without a
 * place to surface a per-card error banner, and the worst case
 * ("status didn't flip") is recoverable by opening the lesson's
 * detail page (once it ships) where the same chip exists with full
 * error handling.
 */
export async function finishLessonAction(formData: FormData): Promise<void> {
  await updateMyLessonCompletionAction({}, formData);
}

/**
 * Same shape as the parent/admin logoutAction — duplicated rather than
 * imported so the route trees stay decoupled. Intent is identical: revoke
 * server-side, clear cookies, bounce home.
 */
export async function logoutAction(formData: FormData) {
  const locale = normalizeLocale(formData.get("locale"));
  const refresh = await getRefreshToken();

  if (refresh) {
    await apiFetch<void>("/api/v1/auth/logout", {
      method: "POST",
      body: JSON.stringify({ refreshToken: refresh }),
    });
  }

  await clearSession();
  redirect(`/${locale}/auth/login`);
}
