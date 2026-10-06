import type { ReactNode } from "react";
import { notFound, redirect } from "next/navigation";
import { setRequestLocale } from "next-intl/server";
import { getAccessToken } from "@/lib/session";
import { routing, type AppLocale } from "@/i18n/routing";

/**
 * Auth-gated shell for every `/parent/*` route. Mirrors the `/admin` layout:
 * cookie presence is checked here, role-specific checks (must be a PARENT)
 * happen in the page itself based on {@code /auth/me}.
 */
export default async function ParentLayout({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  if (!routing.locales.includes(locale as AppLocale)) notFound();
  setRequestLocale(locale);

  const access = await getAccessToken();
  if (!access) {
    redirect(`/${locale}/auth/login`);
  }

  return <>{children}</>;
}
