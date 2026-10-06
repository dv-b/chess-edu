"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { apiFetch } from "@/lib/api";
import { clearSession, getAccessToken, getRefreshToken } from "@/lib/session";
import { routing, type AppLocale } from "@/i18n/routing";

export type ChildActionState = {
  error?: "invalid" | "unauthorised" | "forbidden" | "network" | "unknown";
};

function normalizeLocale(value: FormDataEntryValue | null): AppLocale {
  const str = String(value ?? "");
  return (routing.locales as readonly string[]).includes(str)
    ? (str as AppLocale)
    : routing.defaultLocale;
}

/**
 * Add a child to the calling parent. Pure server action so the page can stay
 * a server component; the resulting list refresh is handled by
 * {@link revalidatePath}, the same pattern the admin roster forms use.
 */
export async function addChildAction(
  _prev: ChildActionState,
  formData: FormData,
): Promise<ChildActionState> {
  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };

  const displayName = String(formData.get("displayName") ?? "").trim();
  const preferredLocaleRaw = String(formData.get("preferredLocale") ?? "");
  const locale = normalizeLocale(formData.get("locale"));

  if (!displayName) return { error: "invalid" };

  const preferredLocale = (routing.locales as readonly string[]).includes(preferredLocaleRaw)
    ? preferredLocaleRaw
    : undefined;

  const res = await apiFetch<unknown>("/api/v1/parent/children", {
    method: "POST",
    accessToken: access,
    body: JSON.stringify({
      displayName,
      ...(preferredLocale ? { preferredLocale } : {}),
    }),
  });

  if (!res.ok) {
    const s = res.error.status;
    if (s === 0) return { error: "network" };
    if (s === 401) return { error: "unauthorised" };
    if (s === 403) return { error: "forbidden" };
    if (s === 400 || s === 422) return { error: "invalid" };
    return { error: "unknown" };
  }

  revalidatePath(`/${locale}/parent`);
  return {};
}

export async function unlinkChildAction(
  _prev: ChildActionState,
  formData: FormData,
): Promise<ChildActionState> {
  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };

  const studentId = String(formData.get("studentId") ?? "");
  const locale = normalizeLocale(formData.get("locale"));
  if (!studentId) return { error: "invalid" };

  const res = await apiFetch<void>(`/api/v1/parent/children/${studentId}`, {
    method: "DELETE",
    accessToken: access,
  });

  if (!res.ok) {
    const s = res.error.status;
    if (s === 0) return { error: "network" };
    if (s === 401) return { error: "unauthorised" };
    if (s === 403) return { error: "forbidden" };
    if (s === 404) return { error: "invalid" };
    return { error: "unknown" };
  }

  revalidatePath(`/${locale}/parent`);
  return {};
}

/**
 * Same shape as the admin logoutAction — duplicated rather than imported so
 * we don't entangle the parent and admin route trees. Intent is identical:
 * revoke server-side, clear cookies, bounce home.
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
