"use server";

import { revalidatePath } from "next/cache";
import { apiFetch } from "@/lib/api";
import { getAccessToken, clearSession } from "@/lib/session";
import { redirect, routing, type AppLocale } from "@/i18n/routing";

const ALLOWED_ROLES = ["SCHOOL_ADMIN", "TEACHER", "PARENT"] as const;
type IssuableRole = (typeof ALLOWED_ROLES)[number];

const CHANNELS = ["EMAIL", "TELEGRAM"] as const;
type Channel = (typeof CHANNELS)[number];

export type IssueActionState = {
  error?:
    | "invalid"
    | "conflict"
    | "forbidden"
    | "network"
    | "unknown"
    | "unauthorised";
  /**
   * The backend's own message for 400 / 403 / 409 — e.g. "schoolId is
   * required when a platform admin invites a school user" or "Telegram bot
   * is not configured". Shown under the generic line so an admin isn't left
   * guessing which field to "check".
   */
  detail?: string;
  /** Preserve form values on error. */
  values?: {
    email?: string;
    displayName?: string;
    role?: string;
    preferredLocale?: string;
    deliveryChannel?: string;
    schoolId?: string;
    phone?: string;
  };
  /**
   * Set (instead of redirecting) after a TELEGRAM invite is issued: the
   * backend sends no email for that channel. With a phone recorded the
   * invitee just opens the bot and shares their contact; without one the
   * admin forwards the deeplink. Shown once — the raw token isn't stored
   * anywhere, and we deliberately don't round-trip it through a URL query.
   */
  issued?: {
    email: string;
    displayName: string;
    deeplink: string;
    phone: string | null;
    botUsername: string | null;
  };
};

type IssuedView = {
  id: string;
  deliveryChannel?: string;
  botDeeplink?: string;
  phone?: string;
  botUsername?: string;
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

function isChannel(value: string): value is Channel {
  return (CHANNELS as readonly string[]).includes(value);
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
  const deliveryChannel = String(formData.get("deliveryChannel") ?? "EMAIL");
  // Only present when the form rendered the platform-admin school picker.
  const schoolId = String(formData.get("schoolId") ?? "").trim();
  const phone = String(formData.get("phone") ?? "").trim();

  const values = { email, displayName, role, preferredLocale, deliveryChannel, schoolId, phone };

  if (!email || !displayName || !isRole(role) || !isChannel(deliveryChannel)) {
    return { error: "invalid", values };
  }

  const access = await getAccessToken();
  if (!access) {
    return { error: "unauthorised", values };
  }

  const result = await apiFetch<IssuedView>("/api/v1/admin/invitations", {
    method: "POST",
    accessToken: access,
    body: JSON.stringify({
      email,
      displayName,
      role,
      preferredLocale: preferredLocale || undefined,
      deliveryChannel,
      schoolId: schoolId || undefined,
      phone: phone || undefined,
    }),
  });

  if (!result.ok) {
    const status = result.error.status;
    const detail = result.error.message;
    if (status === 0) return { error: "network", values };
    if (status === 401) {
      await clearSession();
      return { error: "unauthorised", values };
    }
    if (status === 403) return { error: "forbidden", detail, values };
    if (status === 409) return { error: "conflict", detail, values };
    if (status === 400 || status === 422) return { error: "invalid", detail, values };
    return { error: "unknown", values };
  }

  // Nudge the list route to re-render with the new row.
  revalidatePath(`/${locale}/admin/invitations`);

  // Jackson drops nulls, so a missing deeplink arrives as undefined.
  if (deliveryChannel === "TELEGRAM" && result.data.botDeeplink != null) {
    return {
      issued: {
        email,
        displayName,
        deeplink: result.data.botDeeplink,
        phone: result.data.phone ?? null,
        botUsername: result.data.botUsername ?? null,
      },
    };
  }

  redirect({
    href: {
      pathname: "/admin/invitations",
      query: schoolId ? { sent: email, schoolId } : { sent: email },
    },
    locale,
  });
  // `redirect` throws — this return is unreachable but keeps TS's control-flow
  // analysis happy on the async function's declared return type.
  return { error: "unknown" };
}
