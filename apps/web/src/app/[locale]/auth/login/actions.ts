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
 * Shape returned to the client form via `useActionState`. We deliberately
 * surface only a coarse-grained error key — the page translates it — so we
 * never leak backend error messages directly into the UI.
 */
export type LoginActionState = {
  error?: "invalid" | "network" | "unknown";
  /** Preserve the email on error so the user doesn't have to retype it. */
  email?: string;
};

function normalizeLocale(value: FormDataEntryValue | null): AppLocale {
  const str = String(value ?? "");
  return (routing.locales as readonly string[]).includes(str)
    ? (str as AppLocale)
    : routing.defaultLocale;
}

export async function loginAction(
  _prev: LoginActionState,
  formData: FormData,
): Promise<LoginActionState> {
  const email = String(formData.get("email") ?? "").trim();
  const password = String(formData.get("password") ?? "");
  const locale = normalizeLocale(formData.get("locale"));

  if (!email || !password) {
    return { error: "invalid", email };
  }

  const result = await apiFetch<TokenResponse>("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });

  if (!result.ok) {
    if (result.error.status === 0) return { error: "network", email };
    if (result.error.status === 401) return { error: "invalid", email };
    return { error: "unknown", email };
  }

  await setSession({
    accessToken: result.data.accessToken,
    refreshToken: result.data.refreshToken,
    expiresIn: result.data.expiresIn,
  });

  // Look up the user's role so we can route them to the right shell. If the
  // /me call fails for any reason (rare — we just got a fresh token) fall
  // back to /admin and let the page-level guards handle it.
  const me = await apiFetch<MeResponse>("/api/v1/auth/me", {
    accessToken: result.data.accessToken,
  });
  const home = me.ok ? homeForRole(me.data.role, me.data.platformRole) : "admin";

  // `redirect()` throws a special error that Next.js handles; it doesn't
  // return, so anything after this is unreachable. We branch on the home
  // target so each literal template satisfies Next.js's typed-routes check.
  if (home === "parent") {
    redirect(`/${locale}/parent`);
  }
  if (home === "student") {
    redirect(`/${locale}/student`);
  }
  redirect(`/${locale}/admin`);
}
