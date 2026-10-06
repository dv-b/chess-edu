import type { ReactNode } from "react";
import { notFound, redirect } from "next/navigation";
import { setRequestLocale } from "next-intl/server";
import { getAccessToken } from "@/lib/session";
import { routing, type AppLocale } from "@/i18n/routing";

/**
 * Auth-gated shell for every `/admin/*` route. Anyone without a session
 * access cookie gets bounced to the login page for the same locale —
 * we deliberately do the check here (not in middleware) so individual
 * child layouts can layer further role-based checks on top.
 */
export default async function AdminLayout({
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
