"use server";

import { revalidatePath } from "next/cache";
import { apiFetch } from "@/lib/api";
import { getAccessToken, clearSession } from "@/lib/session";
import { redirect, routing, type AppLocale } from "@/i18n/routing";

const ALLOWED_LEVELS = ["BEGINNER", "INTERMEDIATE", "ADVANCED"] as const;
type AllowedLevel = (typeof ALLOWED_LEVELS)[number];

export type CreateClassActionState = {
  error?: "invalid" | "unauthorised" | "forbidden" | "network" | "unknown";
  values?: {
    name?: string;
    description?: string;
    level?: string;
  };
};

function normalizeLocale(value: FormDataEntryValue | null): AppLocale {
  const str = String(value ?? "");
  return (routing.locales as readonly string[]).includes(str)
    ? (str as AppLocale)
    : routing.defaultLocale;
}

function isLevel(value: string): value is AllowedLevel {
  return (ALLOWED_LEVELS as readonly string[]).includes(value);
}

export async function createClassAction(
  _prev: CreateClassActionState,
  formData: FormData,
): Promise<CreateClassActionState> {
  const locale = normalizeLocale(formData.get("locale"));
  const name = String(formData.get("name") ?? "").trim();
  const description = String(formData.get("description") ?? "").trim();
  const levelRaw = String(formData.get("level") ?? "");

  const values = { name, description, level: levelRaw };

  if (!name) {
    return { error: "invalid", values };
  }

  const access = await getAccessToken();
  if (!access) {
    return { error: "unauthorised", values };
  }

  const payload: Record<string, unknown> = { name };
  if (description) payload.description = description;
  if (isLevel(levelRaw)) payload.level = levelRaw;

  const result = await apiFetch<{ id: string }>("/api/v1/admin/classes", {
    method: "POST",
    accessToken: access,
    body: JSON.stringify(payload),
  });

  if (!result.ok) {
    const status = result.error.status;
    if (status === 0) return { error: "network", values };
    if (status === 401) {
      await clearSession();
      return { error: "unauthorised", values };
    }
    if (status === 403) return { error: "forbidden", values };
    if (status === 400 || status === 422) return { error: "invalid", values };
    return { error: "unknown", values };
  }

  // Refresh the list page so the new row appears immediately, then send the
  // user to the detail page where they can populate the roster.
  revalidatePath(`/${locale}/admin/classes`);
  redirect({
    href: `/admin/classes/${result.data.id}`,
    locale,
  });
  return { error: "unknown" }; // unreachable; TS control-flow pacifier
}
