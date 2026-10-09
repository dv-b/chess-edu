import createMiddleware from "next-intl/middleware";
import { NextResponse, type NextRequest } from "next/server";
import { routing } from "@/i18n/routing";
import {
  ACCESS_COOKIE,
  REFRESH_COOKIE,
  REFRESH_MAX_AGE_SECONDS,
  sessionCookieOptions,
  accessCookieMaxAge,
} from "@/lib/session-cookies";

const intl = createMiddleware(routing);

// Edge runtime: `process.env` is inlined at build time / read from .env.local in dev.
const API_BASE_URL = process.env.API_BASE_URL ?? "http://localhost:8080";

type RefreshOutcome =
  | { ok: true; accessToken: string; refreshToken: string; expiresIn: number }
  | { ok: false };

/**
 * Single-flight guard for refreshes.
 *
 * CAREFUL: the backend rotates refresh tokens and treats a re-presented
 * (already rotated) token as theft — it revokes the whole family, logging
 * the user out everywhere. A page load fans out into several parallel
 * requests (RSC payloads, prefetches), and if the access cookie has just
 * expired every one of them would try to refresh with the *same* old token.
 * Only the first would win; the rest would trip reuse detection.
 *
 * So we key an in-flight/just-finished refresh by the presented token and
 * hand every concurrent caller the same result for a short window. Module
 * scope persists across requests within one middleware isolate, which is
 * enough for a single web instance (the same single-instance assumption the
 * Telegram bot worker makes). Multi-instance would need the backend to add
 * a reuse grace window instead.
 */
const inflight = new Map<string, { at: number; p: Promise<RefreshOutcome> }>();
const SINGLE_FLIGHT_TTL_MS = 15_000;

function refreshOnce(refreshToken: string, req: NextRequest): Promise<RefreshOutcome> {
  const now = Date.now();
  const hit = inflight.get(refreshToken);
  if (hit && now - hit.at < SINGLE_FLIGHT_TTL_MS) return hit.p;

  const p = (async (): Promise<RefreshOutcome> => {
    try {
      const res = await fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
        method: "POST",
        cache: "no-store",
        headers: {
          "content-type": "application/json",
          // The refresh-token row records where the session was minted from.
          "user-agent": req.headers.get("user-agent") ?? "",
          ...(req.headers.get("x-forwarded-for")
            ? { "x-forwarded-for": req.headers.get("x-forwarded-for")! }
            : {}),
        },
        body: JSON.stringify({ refreshToken }),
      });
      if (!res.ok) return { ok: false };
      const data = (await res.json()) as {
        accessToken: string;
        refreshToken: string;
        expiresIn: number;
      };
      return { ok: true, ...data };
    } catch {
      return { ok: false };
    }
  })();

  inflight.set(refreshToken, { at: now, p });
  // Opportunistic sweep so the map can't grow unbounded.
  for (const [k, v] of inflight) {
    if (now - v.at > SINGLE_FLIGHT_TTL_MS) inflight.delete(k);
  }
  return p;
}

/**
 * Silent session refresh in front of the locale middleware.
 *
 * The API issues a 30-minute access token and a 14-day rotating refresh
 * token; both live in HttpOnly cookies. When the access cookie is gone but
 * the refresh cookie is still there, we swap the refresh token for a new
 * pair *before* the page renders, then answer with a redirect to the very
 * same URL carrying the new cookies. The browser re-requests the page with a
 * valid session and the user never sees the login screen.
 *
 * Why a self-redirect rather than rewriting the request's cookie header:
 * Server Components read cookies from the incoming request, and forwarding
 * a modified header through next-intl's response relies on Next internals.
 * One extra round trip every ~30 minutes is a fair price for not depending
 * on them.
 *
 * On a failed refresh (expired, revoked, user suspended) both cookies are
 * cleared and the request proceeds unauthenticated — the shell layouts then
 * send the user to /auth/login exactly as before.
 */
export default async function middleware(req: NextRequest) {
  const hasAccess = Boolean(req.cookies.get(ACCESS_COOKIE)?.value);
  const refreshToken = req.cookies.get(REFRESH_COOKIE)?.value;

  if (hasAccess || !refreshToken) {
    return intl(req);
  }

  const result = await refreshOnce(refreshToken, req);

  if (result.ok) {
    const res = NextResponse.redirect(req.nextUrl, 307);
    res.cookies.set(ACCESS_COOKIE, result.accessToken, sessionCookieOptions(accessCookieMaxAge(result.expiresIn)));
    res.cookies.set(REFRESH_COOKIE, result.refreshToken, sessionCookieOptions(REFRESH_MAX_AGE_SECONDS));
    return res;
  }

  const res = intl(req);
  res.cookies.delete(ACCESS_COOKIE);
  res.cookies.delete(REFRESH_COOKIE);
  return res;
}

export const config = {
  // Match all pathnames except those starting with:
  //   _next (Next internals), assets under /static, and any path with a file extension.
  matcher: ["/((?!api|_next|_vercel|.*\\..*).*)"],
};
