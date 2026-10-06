/**
 * Thin, typed fetch wrapper around the Spring Boot API.
 *
 * - Server-only (reads `API_BASE_URL` at runtime; never exposed to the browser).
 * - Always uses `cache: "no-store"` so server components don't accidentally
 *   cache personalised data across requests.
 * - Returns a discriminated union so callers handle success / failure
 *   explicitly without try/catch ceremony.
 *
 * The frontend never talks to the backend from the browser — every call goes
 * through a Next.js Server Action or Route Handler first. That's why bearer
 * tokens travel as plain function arguments here rather than being pulled out
 * of cookies inside this module.
 */

const BASE_URL = process.env.API_BASE_URL ?? "http://localhost:8080";

export type ApiError = {
  status: number;
  code?: string;
  message?: string;
};

export type ApiResult<T> =
  | { ok: true; data: T }
  | { ok: false; error: ApiError };

type FetchOpts = RequestInit & { accessToken?: string };

export async function apiFetch<T>(
  path: string,
  opts: FetchOpts = {},
): Promise<ApiResult<T>> {
  const { accessToken, headers, ...rest } = opts;

  let res: Response;
  try {
    res = await fetch(`${BASE_URL}${path}`, {
      ...rest,
      cache: "no-store",
      headers: {
        "content-type": "application/json",
        ...(accessToken ? { authorization: `Bearer ${accessToken}` } : {}),
        ...headers,
      },
    });
  } catch {
    // DNS failure, connection refused, etc.
    return { ok: false, error: { status: 0, code: "network" } };
  }

  if (!res.ok) {
    let code: string | undefined;
    let message: string | undefined;
    try {
      const body = (await res.json()) as { code?: string; message?: string };
      code = body.code;
      message = body.message;
    } catch {
      // Non-JSON error body — fall through.
    }
    return { ok: false, error: { status: res.status, code, message } };
  }

  if (res.status === 204) {
    return { ok: true, data: undefined as T };
  }
  return { ok: true, data: (await res.json()) as T };
}
