import { redirect } from "next/navigation";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import { ChildrenPanels, type Child } from "./children-panels";
import { LogoutButton } from "./logout-button";

type MeResponse = {
  userId: string;
  email: string;
  displayName: string;
  preferredLocale: string;
  schoolId: string | null;
  schoolName: string | null;
  role: string | null;
  platformRole: string | null;
};

type PageProps = {
  params: Promise<{ locale: string }>;
};

/**
 * Parent home. Three responsibilities:
 *
 * <ol>
 *   <li>Confirm the caller is actually a PARENT — anyone else gets bounced
 *       to {@code /admin} (which has its own role gates) so a stale URL
 *       doesn't dump them on a confusing screen.</li>
 *   <li>Render a header (greeting + school name + logout).</li>
 *   <li>Hand off the list/add UX to the {@link ChildrenPanels} client island,
 *       which owns the form state but reads its data from this server pass.</li>
 * </ol>
 */
export default async function ParentDashboardPage({ params }: PageProps) {
  const { locale } = await params;
  setRequestLocale(locale);

  const access = await getAccessToken();
  if (!access) redirect(`/${locale}/auth/login`);

  const meRes = await apiFetch<MeResponse>("/api/v1/auth/me", { accessToken: access });
  if (!meRes.ok) {
    redirect(`/api/session/logout?next=/${locale}/auth/login`);
  }
  const me = meRes.data;
  if (me.role !== "PARENT") {
    // Non-parents land on the admin shell. PLATFORM_ADMIN has reasons to be
    // here too, but we keep this surface tightly scoped for now.
    redirect(`/${locale}/admin`);
  }

  const childrenRes = await apiFetch<Child[]>("/api/v1/parent/children", {
    accessToken: access,
  });
  if (!childrenRes.ok && (childrenRes.error.status === 401 || childrenRes.error.status === 403)) {
    redirect(`/api/session/logout?next=/${locale}/auth/login`);
  }
  const children = childrenRes.ok ? childrenRes.data : [];

  const t = await getTranslations("parent");
  const tShell = await getTranslations("parent");

  return (
    <main className="dashboard-shell">
      <header className="dashboard-header">
        <div>
          <h1 className="dashboard-title">{t("dashboard.title", { name: me.displayName })}</h1>
          {me.schoolName ? (
            <p className="dashboard-subtitle">{me.schoolName}</p>
          ) : null}
        </div>
        <div className="dashboard-header__actions">
          <a href={`/${locale}/account`} className="btn btn--ghost">{tShell("account")}</a>
          <LogoutButton locale={locale} />
        </div>
      </header>

      {!childrenRes.ok ? (
        <div className="form-error" role="alert">{t("dashboard.loadError")}</div>
      ) : null}

      <ChildrenPanels children={children} locale={locale} />
    </main>
  );
}
