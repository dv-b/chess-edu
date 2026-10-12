/**
 * Session cookie constants shared by `lib/session.ts` (Server Actions /
 * Route Handlers, via `next/headers`) and `middleware.ts` (edge runtime,
 * via `NextRequest`/`NextResponse`). Kept dependency-free so the middleware
 * bundle doesn't pull in `next/headers`.
 */

export const ACCESS_COOKIE = "session_access";
export const REFRESH_COOKIE = "session_refresh";

// Backend defaults from application.yml: 30m access / 14d refresh.
export const ACCESS_MAX_AGE_SECONDS = 60 * 30;
export const REFRESH_MAX_AGE_SECONDS = 60 * 60 * 24 * 14;

/**
 * The access cookie must disappear *before* the JWT inside it expires,
 * otherwise there's a window where the cookie is present but every API call
 * 401s and the page bounces to logout instead of refreshing. We shave a
 * safety margin off whatever the backend reports as `expiresIn`.
 */
export const ACCESS_COOKIE_SAFETY_SECONDS = 60;

export function accessCookieMaxAge(expiresInSeconds?: number): number {
  const ttl = expiresInSeconds && expiresInSeconds > 0 ? expiresInSeconds : ACCESS_MAX_AGE_SECONDS;
  return Math.max(30, ttl - ACCESS_COOKIE_SAFETY_SECONDS);
}

export function sessionCookieOptions(maxAge: number) {
  return {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax" as const,
    path: "/",
    maxAge,
  };
}
