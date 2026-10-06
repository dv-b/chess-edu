"use server";

import { revalidatePath } from "next/cache";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";

/**
 * Revoke (delete) an invitation by id. Returns nothing on success — the
 * caller will re-fetch via `revalidatePath`. Errors are swallowed into a
 * coarse discriminated result because the admin UI only distinguishes
 * "it worked" from "it didn't".
 */
export async function revokeInvitationAction(formData: FormData): Promise<void> {
  const id = String(formData.get("id") ?? "");
  const locale = String(formData.get("locale") ?? "uz");
  if (!id) return;

  const access = await getAccessToken();
  if (!access) return;

  await apiFetch<void>(`/api/v1/admin/invitations/${encodeURIComponent(id)}`, {
    method: "DELETE",
    accessToken: access,
  });

  // Next.js will re-run the server component on next render.
  revalidatePath(`/${locale}/admin/invitations`);
}
