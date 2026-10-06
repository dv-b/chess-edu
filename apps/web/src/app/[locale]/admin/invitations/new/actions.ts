"use server";

import { revalidatePath } from "next/cache";
import { apiFetch } from "@/lib/api";
import { getAccessToken, clearSession } from "@/lib/session";
import { redirect, routing, type AppLocale } from "@/i18n/routing";

const ALLOWED_ROLES = ["SCHOOL_ADMIN", "TEACHER", "PARENT"] as const;
type IssuableRole = (typeof ALLOWED_ROLES)[number];

export type IssueActionState = {
  error?:
    | "invalid"
    | "conflict"
    | "forbidden"
    | "network"
    | "unknown"
    | "unauthorised";
  /** Preserve form values on error. */
  values?: {
    email?: string;
    displayName?: string;
    role?: string;
    preferredLocale?: string;
  };
};

function normalizeLocale(value: FormDataEntryValue | null): AppLocale {
  const str = String(value ?? "");
  return (routing.locales as readonly string[]).includes(str)
    ? (str as AppLocale)
    : routing.defaultLocale;
}

function isRole(value: string): value is IssuableRole {
  return (ALLOWED_ROLES as readonly string[]).includes(value);
}

export async function issueInvitationAction(
  _prev: IssueActionState,
  formData: FormData,
): Promise<IssueActionState> {
  const locale = normalizeLocale(formData.get("locale"));
  const email = String(formData.get("email") ?? "").trim();
  const displayName = String(formData.get("displayName") ?? "").trim();
  const role = String(formData.get("role") ?? "");
  const preferredLocale = String(formData.get("preferredLocale") ?? "").trim();

  const values = { email, displayName, role, preferredLocale };

  if (!email || !displayName || !isRole(role)) {
    return { error: "invalid", values };
  }

  const access = await getAccessToken();
  if (!access) {
    return { error: "unauthorised", values };
  }

  const result = await apiFetch<{ id: string }>("/api/v1/admin/invitations", {
    method: "POST",
    accessToken: access,
    body: JSON.stringify({
      email,
      displayName,
      role,
      preferredLocale: preferredLocale || undefined,
    }),
  });

  if (!result.ok) {
    const status = result.error.status;
    if (status === 0) return { error: "network", values };
    if (status === 401) {
      await clearSession();
      return { error: "unauthorised", values };
    }
    if (status === 403) return { error: "forbidden", values };
    if (status === 409) return { error: "conflict", values };
    if (status === 400 || status === 422) return { error: "invalid", values };
    return { error: "unknown", values };
  }

  // Nudge the list route to re-render with the new row before we redirect.
  revalidatePath(`/${locale}/admin/invitations`);
  redirect({
    href: { pathname: "/admin/invitations", query: { sent: email } },
    locale,
  });
  // `redirect` throws — this return is unreachable but keeps TS's control-flow
  // analysis happy on the async function's declared return type.
  return { error: "unknown" };
}
