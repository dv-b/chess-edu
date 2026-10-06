/**
 * Session cookie helpers — all server-only.
 *
 * Access + refresh tokens are stored in HttpOnly cookies so client-side
 * JavaScript can never read them. Every server action / server component
 * that needs to call the backend pulls them from here via the Next.js
 * `cookies()` API.
 *
 * Cookie names are namespaced with `session_` so we can add other app cookies
 * later without colliding.
 */

import { cookies } from "next/headers";

const ACCESS = "session_access";
const REFRESH = "session_refresh";

// Match the backend's JWT TTLs from application.yml (30m access / 14d refresh).
// These are just cookie lifetimes; the JWT itself carries its own `exp`.
const ACCESS_MAX_AGE_SECONDS = 60 * 30;
const REFRESH_MAX_AGE_SECONDS = 60 * 60 * 24 * 14;

export type Session = {
  accessToken: string;
  refreshToken: string;
};

function cookieOptions(maxAge: number) {
  return {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax" as const,
    path: "/",
    maxAge,
  };
}

export async function setSession({ accessToken, refreshToken }: Session) {
  const store = await cookies();
  store.set(ACCESS, accessToken, cookieOptions(ACCESS_MAX_AGE_SECONDS));
  store.set(REFRESH, refreshToken, cookieOptions(REFRESH_MAX_AGE_SECONDS));
}

export async function clearSession() {
  const store = await cookies();
  store.delete(ACCESS);
  store.delete(REFRESH);
}

export async function getAccessToken(): Promise<string | null> {
  const store = await cookies();
  return store.get(ACCESS)?.value ?? null;
}

export async function getRefreshToken(): Promise<string | null> {
  const store = await cookies();
  return store.get(REFRESH)?.value ?? null;
}
