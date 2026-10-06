"use server";

import { revalidatePath } from "next/cache";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";

export type RosterActionState = { error?: "unauthorised" | "forbidden" | "network" | "unknown" | "invalid" };

async function mutate(
  path: string,
  method: "POST" | "DELETE",
  body?: unknown,
): Promise<RosterActionState> {
  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };
  const res = await apiFetch<void>(path, {
    method,
    accessToken: access,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    const s = res.error.status;
    if (s === 0) return { error: "network" };
    if (s === 401) return { error: "unauthorised" };
    if (s === 403) return { error: "forbidden" };
    if (s === 400 || s === 422) return { error: "invalid" };
    return { error: "unknown" };
  }
  return {};
}

/**
 * Roster / metadata actions for a single class. Each action re-validates the
 * detail and list routes so the page reflects the change without a client
 * round-trip. The hidden `locale` field on every form keeps revalidation
 * scoped to the caller's current locale.
 */

export async function addTeacherAction(
  _prev: RosterActionState,
  formData: FormData,
): Promise<RosterActionState> {
  const classId = String(formData.get("classId") ?? "");
  const userId = String(formData.get("userId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  if (!classId || !userId) return { error: "invalid" };

  const out = await mutate(`/api/v1/admin/classes/${classId}/teachers`, "POST", { userId });
  if (!out.error) {
    revalidatePath(`/${locale}/admin/classes/${classId}`);
    revalidatePath(`/${locale}/admin/classes`);
  }
  return out;
}

export async function removeTeacherAction(
  _prev: RosterActionState,
  formData: FormData,
): Promise<RosterActionState> {
  const classId = String(formData.get("classId") ?? "");
  const userId = String(formData.get("userId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  if (!classId || !userId) return { error: "invalid" };

  const out = await mutate(`/api/v1/admin/classes/${classId}/teachers/${userId}`, "DELETE");
  if (!out.error) {
    revalidatePath(`/${locale}/admin/classes/${classId}`);
    revalidatePath(`/${locale}/admin/classes`);
  }
  return out;
}

export async function addStudentAction(
  _prev: RosterActionState,
  formData: FormData,
): Promise<RosterActionState> {
  const classId = String(formData.get("classId") ?? "");
  const userId = String(formData.get("userId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  if (!classId || !userId) return { error: "invalid" };

  const out = await mutate(`/api/v1/admin/classes/${classId}/students`, "POST", { userId });
  if (!out.error) {
    revalidatePath(`/${locale}/admin/classes/${classId}`);
    revalidatePath(`/${locale}/admin/classes`);
  }
  return out;
}

export async function removeStudentAction(
  _prev: RosterActionState,
  formData: FormData,
): Promise<RosterActionState> {
  const classId = String(formData.get("classId") ?? "");
  const userId = String(formData.get("userId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  if (!classId || !userId) return { error: "invalid" };

  const out = await mutate(`/api/v1/admin/classes/${classId}/students/${userId}`, "DELETE");
  if (!out.error) {
    revalidatePath(`/${locale}/admin/classes/${classId}`);
    revalidatePath(`/${locale}/admin/classes`);
  }
  return out;
}

async function patchStatus(
  classId: string,
  locale: string,
  status: "ACTIVE" | "ARCHIVED",
): Promise<RosterActionState> {
  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };
  const res = await apiFetch<{ id: string }>(`/api/v1/admin/classes/${classId}`, {
    method: "PATCH",
    accessToken: access,
    body: JSON.stringify({ status }),
  });
  if (!res.ok) {
    const s = res.error.status;
    if (s === 0) return { error: "network" };
    if (s === 401) return { error: "unauthorised" };
    if (s === 403) return { error: "forbidden" };
    return { error: "unknown" };
  }
  revalidatePath(`/${locale}/admin/classes/${classId}`);
  revalidatePath(`/${locale}/admin/classes`);
  return {};
}

export async function archiveClassAction(
  _prev: RosterActionState,
  formData: FormData,
): Promise<RosterActionState> {
  const classId = String(formData.get("classId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  if (!classId) return { error: "invalid" };
  return patchStatus(classId, locale, "ARCHIVED");
}

export async function reactivateClassAction(
  _prev: RosterActionState,
  formData: FormData,
): Promise<RosterActionState> {
  const classId = String(formData.get("classId") ?? "");
  const locale = String(formData.get("locale") ?? "en");
  if (!classId) return { error: "invalid" };
  return patchStatus(classId, locale, "ACTIVE");
}
