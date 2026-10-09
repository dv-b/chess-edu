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

// ============================================================================
// "Log in with Telegram" — login-request handshake (no Login Widget needed)
// ============================================================================

/**
 * The browser's half of the handshake lives in an HttpOnly cookie:
 * `<requestId>.<clientSecret>`. The secret never reaches the client bundle;
 * the client only ever sees the t.me deeplink and a status word.
 */
const TG_LOGIN_COOKIE = "tg_login_request";
const TG_LOGIN_COOKIE_MAX_AGE = 60 * 5;

export type TelegramStartState = {
  deeplink?: string;
  error?: "unavailable" | "network" | "unknown";
};

export type TelegramPollState = {
  status: "pending" | "expired" | "network";
};

export async function startTelegramLoginAction(): Promise<TelegramStartState> {
  const result = await apiFetch<{
    id: string;
    clientSecret: string;
    deeplink: string;
    expiresAt: string;
  }>("/api/v1/auth/telegram/login-requests", { method: "POST" });

  if (!result.ok) {
    if (result.error.status === 0) return { error: "network" };
    // 400 = bot not configured on this deployment.
    if (result.error.status === 400) return { error: "unavailable" };
    return { error: "unknown" };
  }

  const store = await cookies();
  store.set(TG_LOGIN_COOKIE, `${result.data.id}.${result.data.clientSecret}`, {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: TG_LOGIN_COOKIE_MAX_AGE,
  });

  return { deeplink: result.data.deeplink };
}

/**
 * Polled by the login page every couple of seconds while it waits for the
 * bot. On COMPLETED it installs the session and redirects — the same tail as
 * the password login — so the client never handles tokens.
 */
export async function pollTelegramLoginAction(localeValue: string): Promise<TelegramPollState> {
  const locale = normalizeLocale(localeValue);
  const store = await cookies();
  const raw = store.get(TG_LOGIN_COOKIE)?.value ?? "";
  const dot = raw.indexOf(".");
  if (dot <= 0) return { status: "expired" };
  const id = raw.slice(0, dot);
  const clientSecret = raw.slice(dot + 1);

  const result = await apiFetch<{ status: "PENDING" | "COMPLETED"; tokens?: TokenResponse }>(
    `/api/v1/auth/telegram/login-requests/${encodeURIComponent(id)}/poll`,
    { method: "POST", body: JSON.stringify({ clientSecret }) },
  );

  if (!result.ok) {
    if (result.error.status === 0) return { status: "network" };
    // 404 / 401 / 410 — nothing to wait for any more.
    store.delete(TG_LOGIN_COOKIE);
    return { status: "expired" };
  }

  if (result.data.status !== "COMPLETED" || result.data.tokens == null) {
    return { status: "pending" };
  }

  store.delete(TG_LOGIN_COOKIE);
  await setSession({
    accessToken: result.data.tokens.accessToken,
    refreshToken: result.data.tokens.refreshToken,
    expiresIn: result.data.tokens.expiresIn,
  });

  const me = await apiFetch<MeResponse>("/api/v1/auth/me", {
    accessToken: result.data.tokens.accessToken,
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

export async function cancelTelegramLoginAction(): Promise<void> {
  const store = await cookies();
  store.delete(TG_LOGIN_COOKIE);
}
