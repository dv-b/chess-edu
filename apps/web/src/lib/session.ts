/**
 * Session cookie helpers — all server-only.
 *
 * Access + refresh tokens are stored in HttpOnly cookies so client-side
 * JavaScript can never read them. Every server action / server component
 * that needs to call the backend pulls them from here via the Next.js
 * `cookies()` API.
 *
 * Cookie names are namespaced with `session_` so we can add other app cookies
 * later without colliding. Names, lifetimes and options live in
 * `session-cookies.ts` so `middleware.ts` (which silently refreshes expired
 * sessions) can share them without importing `next/headers`.
 */

import { cookies } from "next/headers";
import {
  ACCESS_COOKIE as ACCESS,
  REFRESH_COOKIE as REFRESH,
  REFRESH_MAX_AGE_SECONDS,
  accessCookieMaxAge,
  sessionCookieOptions as cookieOptions,
} from "@/lib/session-cookies";

export type Session = {
  accessToken: string;
  refreshToken: string;
  /** Seconds until the access token expires, as reported by the backend. */
  expiresIn?: number;
};

export async function setSession({ accessToken, refreshToken, expiresIn }: Session) {
  const store = await cookies();
  store.set(ACCESS, accessToken, cookieOptions(accessCookieMaxAge(expiresIn)));
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
