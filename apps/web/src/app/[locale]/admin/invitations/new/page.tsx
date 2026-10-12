import { redirect } from "next/navigation";
import { setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import { InviteForm } from "./invite-form";
import type { SchoolOption } from "../school-picker";

type PageProps = {
  params: Promise<{ locale: string }>;
  searchParams: Promise<{ schoolId?: string | string[] }>;
};

type MeResponse = { platformRole: string | null };

/**
 * Auth gate is handled by the parent `/admin` layout. A platform admin has
 * no school of their own, so the form gets the school list and a preselected
 * `?schoolId=` (carried over from the list page's picker); the backend
 * requires one for every school-scoped invite from a platform admin.
 */
export default async function NewInvitationPage({ params, searchParams }: PageProps) {
  const { locale } = await params;
  setRequestLocale(locale);

  const access = await getAccessToken();
  if (!access) redirect(`/${locale}/auth/login`);

  const me = await apiFetch<MeResponse>("/api/v1/auth/me", { accessToken: access });
  let schools: SchoolOption[] = [];
  let initialSchoolId: string | null = null;
  if (me.ok && me.data.platformRole === "PLATFORM_ADMIN") {
    const sp = await searchParams;
    const requested = Array.isArray(sp.schoolId) ? sp.schoolId[0] : sp.schoolId;
    const list = await apiFetch<SchoolOption[]>("/api/v1/admin/schools", { accessToken: access });
    schools = list.ok ? list.data : [];
    initialSchoolId = schools.some((s) => s.id === requested) ? requested! : (schools[0]?.id ?? null);
  }

  return (
    <main className="auth-shell">
      <InviteForm locale={locale} schools={schools} initialSchoolId={initialSchoolId} />
    </main>
  );
}
