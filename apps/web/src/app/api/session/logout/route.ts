import { NextResponse, type NextRequest } from "next/server";
import { clearSession } from "@/lib/session";
import { routing } from "@/i18n/routing";

/**
 * Clears the session cookies and redirects to `?next` (or the default-locale
 * login page if `next` is absent / not same-origin).
 *
 * Why a Route Handler? In Next.js 15, `cookies().delete()` and
 * `cookies().set()` throw if invoked from a Server Component's render path.
 * Server Components that discover a bad/expired session (e.g. after a 401
 * from /auth/me) therefore can't clear cookies themselves — instead they
 * redirect here, and we do the cookie surgery in a context where it's legal.
 */
export async function GET(req: NextRequest) {
  await clearSession();

  const requested = req.nextUrl.searchParams.get("next");
  const fallback = `/${routing.defaultLocale}/auth/login`;

  // Only allow relative, same-origin redirects to avoid open-redirect abuse.
  const next = requested && requested.startsWith("/") && !requested.startsWith("//")
    ? requested
    : fallback;

  return NextResponse.redirect(new URL(next, req.url));
}
