import { redirect } from "next/navigation";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import { Link } from "@/i18n/routing";
import { LogoutButton } from "./logout-button";

/**
 * Mirrors `MeResponse` on the API. We repeat the shape rather than share a
 * DTO package because (a) the web app never codegens from the OpenAPI spec
 * yet, and (b) drift will surface immediately as a TS error when the API
 * changes something observable here.
 */
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

export default async function AdminDashboardPage({ params }: PageProps) {
  const { locale } = await params;
  setRequestLocale(locale);

  // The layout has already gated on cookie presence; we re-fetch to call the
  // backend and will wipe the session if the server rejects the token.
  const access = await getAccessToken();
  if (!access) redirect(`/${locale}/auth/login`);

  const me = await apiFetch<MeResponse>("/api/v1/auth/me", { accessToken: access });
  if (!me.ok) {
    // Access token rejected (expired / revoked / tampered). We can't clear
    // cookies from a Server Component in Next.js 15, so route through the
    // logout handler which clears them and redirects back to login.
    redirect(`/api/session/logout?next=/${locale}/auth/login`);
  }

  const t = await getTranslations("admin.dashboard");
  const tShell = await getTranslations("admin");
  const user = me.data;
  const displayRole = user.role ?? user.platformRole ?? "—";

  return (
    <main className="dashboard-shell">
      <header className="dashboard-header">
        <div>
          <h1 className="dashboard-title">
            {t("title", { name: user.displayName })}
          </h1>
          {user.schoolName ? (
            <p className="dashboard-subtitle">{user.schoolName}</p>
          ) : null}
        </div>
        <div className="dashboard-header__actions">
          <Link href="/admin/classes" className="btn btn--ghost">
            {t("classesCta")}
          </Link>
          <Link href="/admin/members" className="btn btn--ghost">
            {t("membersCta")}
          </Link>
          <Link href="/admin/invitations" className="btn btn--ghost">
            {t("invitationsCta")}
          </Link>
          <a href={`/${locale}/account`} className="btn btn--ghost">{tShell("account")}</a>
          <LogoutButton locale={locale} />
        </div>
      </header>

      <section className="dashboard-grid">
        <article className="dashboard-card">
          <h2>{t("role")}</h2>
          <p>{displayRole}</p>
        </article>
        <article className="dashboard-card">
          <h2>{t("email")}</h2>
          <p>{user.email}</p>
        </article>
        <article className="dashboard-card">
          <h2>{t("locale")}</h2>
          <p>{user.preferredLocale.toUpperCase()}</p>
        </article>
      </section>

      <section className="dashboard-note">
        <p>{t("placeholder")}</p>
      </section>
    </main>
  );
}
