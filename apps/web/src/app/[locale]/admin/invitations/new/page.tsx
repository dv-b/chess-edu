import { setRequestLocale } from "next-intl/server";
import { InviteForm } from "./invite-form";

type PageProps = {
  params: Promise<{ locale: string }>;
};

/**
 * Auth gate is handled by the parent `/admin` layout, so we can focus this
 * page on rendering the form. The form itself posts through a Server Action
 * that calls `POST /api/v1/admin/invitations` with the session cookie.
 */
export default async function NewInvitationPage({ params }: PageProps) {
  const { locale } = await params;
  setRequestLocale(locale);

  return (
    <main className="auth-shell">
      <InviteForm locale={locale} />
    </main>
  );
}
