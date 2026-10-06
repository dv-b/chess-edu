/**
 * Pure module shared by the server-rendered class detail page (`page.tsx`)
 * and the client-rendered tab strip (`sub-nav.tsx`). Lives outside of
 * `"use client"` boundaries so the page's server component can call
 * {@link normaliseClassDetailTab} on the awaited `searchParams` without
 * tripping Next 15's "client-only export imported from server" runtime
 * error. The `ClassDetailTab` literal union is exported here too so the
 * page can type its `activeTab` derivation against the same canonical
 * shape the client component consumes.
 */

export type ClassDetailTab = "roster" | "homework" | "lessons" | "schedule" | "qa";

export const TAB_ORDER: readonly ClassDetailTab[] = [
  "roster",
  "homework",
  "lessons",
  "schedule",
  "qa",
] as const;

const TAB_SLUGS: ReadonlySet<string> = new Set<string>(TAB_ORDER);

export const DEFAULT_TAB: ClassDetailTab = "roster";

/**
 * Coerce a raw `?tab=` value into one of the four known slugs. Unknown
 * values (including `null` / `undefined` / `string[]` from a duplicate
 * param) fall back to the default tab so a crafted URL like
 * `?tab=garbage` reads as roster without a redirect.
 */
export function normaliseClassDetailTab(
  raw: string | string[] | null | undefined,
): ClassDetailTab {
  const value = Array.isArray(raw) ? raw[0] : raw;
  return value != null && TAB_SLUGS.has(value)
    ? (value as ClassDetailTab)
    : DEFAULT_TAB;
}
