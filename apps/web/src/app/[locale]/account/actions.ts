"use server";

import { revalidatePath } from "next/cache";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";

export type UsernameActionState = {
  ok?: boolean;
  error?: "invalid" | "taken" | "unauthorised" | "network" | "unknown";
  value?: string;
};

export type PasswordActionState = {
  ok?: boolean;
  error?: "mismatch" | "wrongCurrent" | "invalid" | "unauthorised" | "network" | "unknown";
};

/** Self-service username. The API normalises (trim + lowercase) and validates the pattern. */
export async function setUsernameAction(
  _prev: UsernameActionState,
  formData: FormData,
): Promise<UsernameActionState> {
  const locale = String(formData.get("locale") ?? "");
  const username = String(formData.get("username") ?? "").trim();
  const access = await getAccessToken();
  if (!access) return { error: "unauthorised", value: username };

  const result = await apiFetch<{ username: string }>("/api/v1/auth/account/username", {
    method: "PUT",
    accessToken: access,
    body: JSON.stringify({ username }),
  });
  if (!result.ok) {
    const status = result.error.status;
    if (status === 0) return { error: "network", value: username };
    if (status === 401) return { error: "unauthorised", value: username };
    if (status === 409) return { error: "taken", value: username };
    if (status === 400 || status === 422) return { error: "invalid", value: username };
    return { error: "unknown", value: username };
  }
  revalidatePath(`/${locale}/account`);
  return { ok: true, value: result.data.username };
}

/**
 * Set (first time) or change a password. The confirm check is client-side
 * UX only — the API never sees it. `currentPassword` is sent only when the
 * form rendered that field, i.e. when the user already has a password.
 */
export async function setPasswordAction(
  _prev: PasswordActionState,
  formData: FormData,
): Promise<PasswordActionState> {
  const locale = String(formData.get("locale") ?? "");
  const hasCurrent = formData.has("currentPassword");
  const currentPassword = String(formData.get("currentPassword") ?? "");
  const newPassword = String(formData.get("newPassword") ?? "");
  const confirm = String(formData.get("confirmPassword") ?? "");

  if (newPassword.length < 8 || newPassword.length > 200) return { error: "invalid" };
  if (newPassword !== confirm) return { error: "mismatch" };

  const access = await getAccessToken();
  if (!access) return { error: "unauthorised" };

  const result = await apiFetch<{ hasPassword: boolean }>("/api/v1/auth/account/password", {
    method: "PUT",
    accessToken: access,
    body: JSON.stringify(
      hasCurrent ? { currentPassword, newPassword } : { newPassword },
    ),
  });
  if (!result.ok) {
    const status = result.error.status;
    if (status === 0) return { error: "network" };
    // 401 here is "current password wrong" (the session itself is fine —
    // an expired session would have failed at getAccessToken above).
    if (status === 401) return { error: hasCurrent ? "wrongCurrent" : "unauthorised" };
    if (status === 400 || status === 422) return { error: "invalid" };
    return { error: "unknown" };
  }
  revalidatePath(`/${locale}/account`);
  return { ok: true };
}
