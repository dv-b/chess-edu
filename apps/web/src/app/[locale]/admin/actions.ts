"use server";

import { redirect } from "next/navigation";
import { apiFetch } from "@/lib/api";
import { clearSession, getRefreshToken } from "@/lib/session";
import { routing, type AppLocale } from "@/i18n/routing";

function normalizeLocale(value: FormDataEntryValue | null): AppLocale {
  const str = String(value ?? "");
  return (routing.locales as readonly string[]).includes(str)
    ? (str as AppLocale)
    : routing.defaultLocale;
}

/**
 * Revoke the current refresh token server-side (best-effort), then clear
 * both session cookies and bounce back to the login screen. We ignore any
 * backend error — the user intent is "log me out", so we log out locally
 * even if the server couldn't be reached.
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
