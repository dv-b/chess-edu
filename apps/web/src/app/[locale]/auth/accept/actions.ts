"use server";

import { redirect } from "next/navigation";
import { apiFetch } from "@/lib/api";
import { setSession } from "@/lib/session";
import { homeForRole } from "@/lib/home-for-role";
import { routing, type AppLocale } from "@/i18n/routing";

type TokenResponse = {
  accessToken: string;
  refreshToken: string;
  tokenType: "Bearer";
  expiresIn: number;
};

type MeResponse = {
  role: string | null;
  platformRole: string | null;
};

/**
 * Coarse error keys only — the page translates them. "expired" covers every
 * token failure the backend reports (unknown / already used / past TTL /
 * account suspended): it answers all of them with one opaque 401, and the
 * only thing the user can do about any of them is ask the bot for a new link.
 */
export type TelegramAcceptState = {
  error?: "expired" | "network" | "unknown";
};

function normalizeLocale(value: FormDataEntryValue | null): AppLocale {
  const str = String(value ?? "");
  return (routing.locales as readonly string[]).includes(str)
    ? (str as AppLocale)
    : routing.defaultLocale;
}

/**
 * Redeem the one-time token from the bot's deeplink for a session. Mirrors
 * the tail of the password login: install the cookie pair, look up /me, and
 * send the user to the shell for their role.
 */
export async function acceptTelegramAction(
  _prev: TelegramAcceptState,
  formData: FormData,
): Promise<TelegramAcceptState> {
  const locale = normalizeLocale(formData.get("locale"));
  const ott = String(formData.get("ott") ?? "").trim();
  if (!ott) return { error: "expired" };

  const result = await apiFetch<TokenResponse>("/api/v1/auth/telegram/accept", {
    method: "POST",
    body: JSON.stringify({ ott }),
  });

  if (!result.ok) {
    if (result.error.status === 0) return { error: "network" };
    if (result.error.status === 401 || result.error.status === 400) {
      return { error: "expired" };
    }
    return { error: "unknown" };
  }

  await setSession({
    accessToken: result.data.accessToken,
    refreshToken: result.data.refreshToken,
    expiresIn: result.data.expiresIn,
  });

  const me = await apiFetch<MeResponse>("/api/v1/auth/me", {
    accessToken: result.data.accessToken,
  });
  const home = me.ok ? homeForRole(me.data.role, me.data.platformRole) : "admin";

  // Branch so each literal template satisfies Next.js's typed-routes check.
  if (home === "parent") {
    redirect(`/${locale}/parent`);
  }
  if (home === "student") {
    redirect(`/${locale}/student`);
  }
  redirect(`/${locale}/admin`);
}
