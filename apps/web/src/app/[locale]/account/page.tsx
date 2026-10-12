import { redirect } from "next/navigation";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import { homeForRole } from "@/lib/home-for-role";
import { TopBar } from "@/components/top-bar";
import { AccountForms } from "./account-forms";

type MeResponse = {
  email: string;
  username?: string | null;
  displayName: string;
  role: string | null;
  platformRole: string | null;
  hasPassword: boolean;
  telegramLinked: boolean;
};

type PageProps = {
  params: Promise<{ locale: string }>;
};

/**
 * `/<locale>/account` — sign-in settings for the current user, whatever
 * their role: username, and set/change password. Deliberately outside the
 * role shells (each has its own chrome) and styled like the auth pages, so
 * one page serves admins, teachers, parents and — later — students.
 */
export default async function AccountPage({ params }: PageProps) {
  const { locale } = await params;
  setRequestLocale(locale);

  const access = await getAccessToken();
  if (!access) redirect(`/${locale}/auth/login`);

  const me = await apiFetch<MeResponse>("/api/v1/auth/me", { accessToken: access });
  if (!me.ok) {
    redirect(`/api/session/logout?next=/${locale}/auth/login`);
  }

  const t = await getTranslations("account");
  const home = homeForRole(me.data.role, me.data.platformRole);

  return (
    <>
      <TopBar />
      <main className="auth-shell account-shell">
        <div className="account-intro">
          <h1>{t("title")}</h1>
          <p className="auth-meta">
            {t("signedInAs", { name: me.data.displayName, email: me.data.email })}
          </p>
          <p className="auth-meta">
            {me.data.telegramLinked ? t("telegramLinked") : t("telegramNotLinked")}
          </p>
          <a href={`/${locale}/${home}`} className="btn btn--ghost btn--sm">
            {t("back")}
          </a>
        </div>

        <AccountForms
          locale={locale}
          me={{
            email: me.data.email,
            username: me.data.username ?? null,
            hasPassword: me.data.hasPassword,
            telegramLinked: me.data.telegramLinked,
          }}
        />
      </main>
    </>
  );
}

export async function generateMetadata({ params }: PageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "account" });
  return { title: t("title"), robots: { index: false, follow: false } };
}
