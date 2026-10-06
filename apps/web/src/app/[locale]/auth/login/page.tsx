import { redirect } from "next/navigation";
import { setRequestLocale } from "next-intl/server";
import { getAccessToken } from "@/lib/session";
import { LoginForm } from "./login-form";

type PageProps = {
  params: Promise<{ locale: string }>;
};

export default async function LoginPage({ params }: PageProps) {
  const { locale } = await params;
  setRequestLocale(locale);

  // Already signed in? Skip the login screen.
  const access = await getAccessToken();
  if (access) {
    redirect(`/${locale}/admin`);
  }

  return <LoginForm locale={locale} />;
}
