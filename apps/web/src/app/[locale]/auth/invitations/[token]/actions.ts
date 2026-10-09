"use server";

import { redirect } from "next/navigation";
import { apiFetch } from "@/lib/api";
import { setSession } from "@/lib/session";
import { routing, type AppLocale } from "@/i18n/routing";

type TokenResponse = {
  accessToken: string;
  refreshToken: string;
  tokenType: "Bearer";
  expiresIn: number;
};

/**
 * State shape surfaced to the client form via `useActionState`.
 * Error keys are translated by the page; we never leak backend messages
 * directly into the UI.
 */
export type AcceptActionState = {
  error?: "invalid" | "password" | "gone" | "notFound" | "network" | "unknown";
  /** Preserve the display name on error so the user doesn't have to retype it. */
  displayName?: string;
};

function normalizeLocale(value: FormDataEntryValue | null): AppLocale {
  const str = String(value ?? "");
  return (routing.locales as readonly string[]).includes(str)
    ? (str as AppLocale)
    : routing.defaultLocale;
}

export async function acceptInvitationAction(
  _prev: AcceptActionState,
  formData: FormData,
): Promise<AcceptActionState> {
  const token = String(formData.get("token") ?? "");
  const password = String(formData.get("password") ?? "");
  const displayName = String(formData.get("displayName") ?? "").trim();
  const preferredLocale = String(formData.get("preferredLocale") ?? "").trim();
  const locale = normalizeLocale(formData.get("locale"));

  if (!token) {
    return { error: "notFound", displayName };
  }
  if (password.length < 8) {
    return { error: "password", displayName };
  }

  const result = await apiFetch<TokenResponse>("/api/v1/auth/invitations/accept", {
    method: "POST",
    body: JSON.stringify({
      token,
      password,
      displayName: displayName || undefined,
      preferredLocale: preferredLocale || undefined,
    }),
  });

  if (!result.ok) {
    if (result.error.status === 0) return { error: "network", displayName };
    if (result.error.status === 404) return { error: "notFound", displayName };
    if (result.error.status === 410) return { error: "gone", displayName };
    if (result.error.status === 400) return { error: "invalid", displayName };
    return { error: "unknown", displayName };
  }

  await setSession({
    accessToken: result.data.accessToken,
    refreshToken: result.data.refreshToken,
    expiresIn: result.data.expiresIn,
  });

  // Send the freshly-onboarded user straight into their admin workspace.
  // If later we add a per-role landing page, branch on preview.role here.
  redirect(`/${locale}/admin`);
}
