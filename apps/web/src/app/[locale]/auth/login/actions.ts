"use server";

import { cookies } from "next/headers";
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
  const email = String(formData.get("login") ?? formData.get("email") ?? "").trim();
  const password = String(formData.get("password") ?? "");
  const locale = normalizeLocale(formData.get("locale"));

  if (!email || !password) {
    return { error: "invalid", email };
  }

  const result = await apiFetch<TokenResponse>("/api/v1/auth/login", {
    method: "POST",
    // `login` is an email or a username; the API tells them apart by the "@".
    body: JSON.stringify({ login: email, password }),
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

// ============================================================================
// "Log in with Telegram" — one-time code sent by the bot
// ============================================================================

/**
 * The pending code request's id lives in an HttpOnly cookie between the two
 * steps (ask for a code → type the code), so the client bundle never sees
 * anything but a status word. The code itself only ever exists in the
 * user's Telegram chat and in the form field they type it into.
 */
const TG_CODE_COOKIE = "tg_login_code";
const TG_CODE_COOKIE_MAX_AGE = 60 * 5;

export type TelegramCodeRequestState = {
  sent?: boolean;
  identifier?: string;
  error?: "invalid" | "unavailable" | "network" | "unknown";
};

export type TelegramCodeVerifyState = {
  error?: "wrongCode" | "expired" | "unavailable" | "network" | "unknown";
};

/** Step 1: who are you? The API answers 200 either way (no account probing). */
export async function requestTelegramCodeAction(
  _prev: TelegramCodeRequestState,
  formData: FormData,
): Promise<TelegramCodeRequestState> {
  const identifier = String(formData.get("identifier") ?? "").trim();
  if (!identifier) return { error: "invalid", identifier };

  const result = await apiFetch<{ id: string; expiresAt: string }>(
    "/api/v1/auth/telegram/login-codes",
    { method: "POST", body: JSON.stringify({ identifier }) },
  );
  if (!result.ok) {
    if (result.error.status === 0) return { error: "network", identifier };
    // 400 = bot not configured / not running on this deployment.
    if (result.error.status === 400) return { error: "unavailable", identifier };
    return { error: "unknown", identifier };
  }

  const store = await cookies();
  store.set(TG_CODE_COOKIE, result.data.id, {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: TG_CODE_COOKIE_MAX_AGE,
  });
  return { sent: true, identifier };
}

/** Step 2: the code. On success installs the session and redirects by role. */
export async function verifyTelegramCodeAction(
  _prev: TelegramCodeVerifyState,
  formData: FormData,
): Promise<TelegramCodeVerifyState> {
  const locale = normalizeLocale(formData.get("locale"));
  const code = String(formData.get("code") ?? "").replace(/\s+/g, "");
  const store = await cookies();
  const id = store.get(TG_CODE_COOKIE)?.value ?? "";
  if (!id) return { error: "expired" };
  if (!code) return { error: "wrongCode" };

  const result = await apiFetch<TokenResponse>(
    `/api/v1/auth/telegram/login-codes/${encodeURIComponent(id)}/verify`,
    { method: "POST", body: JSON.stringify({ code }) },
  );
  if (!result.ok) {
    const status = result.error.status;
    if (status === 0) return { error: "network" };
    if (status === 401) return { error: "wrongCode" };
    // 404 / 410: unknown, expired, consumed or too many attempts — start over.
    store.delete(TG_CODE_COOKIE);
    if (status === 404 || status === 410) return { error: "expired" };
    return { error: "unknown" };
  }

  store.delete(TG_CODE_COOKIE);
  await setSession({
    accessToken: result.data.accessToken,
    refreshToken: result.data.refreshToken,
    expiresIn: result.data.expiresIn,
  });

  const me = await apiFetch<MeResponse>("/api/v1/auth/me", {
    accessToken: result.data.accessToken,
  });
  const home = me.ok ? homeForRole(me.data.role, me.data.platformRole) : "admin";
  if (home === "parent") {
    redirect(`/${locale}/parent`);
  }
  if (home === "student") {
    redirect(`/${locale}/student`);
  }
  redirect(`/${locale}/admin`);
}

export async function cancelTelegramCodeAction(): Promise<void> {
  const store = await cookies();
  store.delete(TG_CODE_COOKIE);
}
