import { getTranslations, setRequestLocale } from "next-intl/server";
import { TopBar } from "@/components/top-bar";
import { TelegramAcceptForm } from "./accept-form";

type PageProps = {
  params: Promise<{ locale: string }>;
  searchParams: Promise<{ ott?: string | string[] }>;
};

/**
 * `/<locale>/auth/accept?ott=<token>` — where the Telegram bot sends a user
 * after signup (and, later, where the Login Widget hands off too). Both flows
 * end in the same one-time-token redemption, so this page is the single web
 * entry point for Telegram auth.
 *
 * The page itself only validates that a token is present; the redemption
 * happens in a Server Action (cookies can't be set during render).
 */
export default async function TelegramAcceptPage({ params, searchParams }: PageProps) {
  const { locale } = await params;
  setRequestLocale(locale);

  const sp = await searchParams;
  const raw = Array.isArray(sp.ott) ? sp.ott[0] : sp.ott;
  const ott = (raw ?? "").trim();

  if (!ott) {
    const t = await getTranslations("auth.telegramAccept");
    return (
      <>
        <TopBar />
        <main className="auth-shell">
          <div className="auth-card">
            <h1>{t("errorTitle")}</h1>
            <p className="auth-meta">{t("error.missing")}</p>
            <a
              href={`/${locale}/auth/login`}
              className="btn btn--primary"
              style={{ display: "inline-block", marginTop: "1rem" }}
            >
              {t("backToLogin")}
            </a>
          </div>
        </main>
      </>
    );
  }

  return <TelegramAcceptForm locale={locale} ott={ott} />;
}

export async function generateMetadata({ params }: PageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "auth.telegramAccept" });
  return {
    title: t("title"),
    robots: { index: false, follow: false },
  };
}
