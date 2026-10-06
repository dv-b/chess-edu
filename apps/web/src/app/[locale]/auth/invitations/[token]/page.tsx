import { getTranslations, setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { TopBar } from "@/components/top-bar";
import { AcceptInvitationForm } from "./accept-form";

type InvitationPreview = {
  email: string;
  displayName: string;
  role: string;
  schoolName: string | null;
  preferredLocale: string | null;
  expiresAt: string;
};

type PageProps = {
  params: Promise<{ locale: string; token: string }>;
};

/**
 * Server-side preview fetch. The preview endpoint is public and returns the
 * invitee's email + role + school so the page can render a trustworthy
 * summary before asking for a password. We surface the common failure modes
 * (404 = bad/revoked token, 410 = already accepted or expired) as friendly
 * states; anything else renders the generic error card.
 */
export default async function AcceptInvitationPage({ params }: PageProps) {
  const { locale, token } = await params;
  setRequestLocale(locale);

  // The token travels as a URL segment; Next.js has already decoded it.
  // Re-encode for the outbound fetch so any `/` or `+` in the raw value
  // survives the round trip.
  const preview = await apiFetch<InvitationPreview>(
    `/api/v1/auth/invitations/${encodeURIComponent(token)}`,
  );

  const t = await getTranslations("auth.invitation.accept");

  if (!preview.ok) {
    const status = preview.error.status;
    const key =
      status === 404 ? "error.notFound"
      : status === 410 ? "error.gone"
      : status === 0 ? "error.network"
      : "error.unknown";
    return (
      <InvitationErrorCard
        title={t("errorTitle")}
        message={t(key)}
        backLabel={t("backToLogin")}
        backHref={`/${locale}/auth/login`}
      />
    );
  }

  const expiresAtMs = new Date(preview.data.expiresAt).getTime();
  if (Number.isFinite(expiresAtMs) && expiresAtMs < Date.now()) {
    return (
      <InvitationErrorCard
        title={t("errorTitle")}
        message={t("error.gone")}
        backLabel={t("backToLogin")}
        backHref={`/${locale}/auth/login`}
      />
    );
  }

  const expiresAtLabel = new Intl.DateTimeFormat(locale, {
    dateStyle: "long",
    timeStyle: "short",
  }).format(new Date(preview.data.expiresAt));

  return (
    <AcceptInvitationForm
      locale={locale}
      token={token}
      preview={preview.data}
      expiresAtLabel={expiresAtLabel}
    />
  );
}

/**
 * Small presentational card for the three terminal error states. Hoisted out
 * of the page component so React treats it as a stable type and we can keep
 * it synchronous (translations are resolved by the caller).
 */
function InvitationErrorCard({
  title,
  message,
  backLabel,
  backHref,
}: {
  title: string;
  message: string;
  backLabel: string;
  backHref: string;
}) {
  return (
    <>
      <TopBar />
      <main className="auth-shell">
        <div className="auth-card">
          <h1>{title}</h1>
          <p className="auth-meta">{message}</p>
          <a
            href={backHref}
            className="btn btn--primary"
            style={{ display: "inline-block", marginTop: "1rem" }}
          >
            {backLabel}
          </a>
        </div>
      </main>
    </>
  );
}

export async function generateMetadata({ params }: PageProps) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "auth.invitation.accept" });
  return {
    title: t("titleNoSchool"),
    robots: { index: false, follow: false },
  };
}
