"use client";

import { useLocale, useTranslations } from "next-intl";
import { Link, usePathname, routing } from "@/i18n/routing";
import { ThemeToggle } from "./theme-toggle";

/**
 * Sticky top bar with brand + locale switcher + login CTA.
 *
 * The locale switcher uses next-intl's typed `Link` with a `locale` prop,
 * which swaps the leading locale segment while keeping the user on the
 * same page. `usePathname()` from next-intl returns the path without the
 * locale prefix, so it can be reused as the href for each locale.
 */
export function TopBar() {
  const t = useTranslations();
  const pathname = usePathname();
  const currentLocale = useLocale();

  return (
    <header className="topbar">
      <ThemeToggle />
      <div className="container topbar__inner">
        <Link href="/" className="brand" aria-label={t("brand.name")}>
          <span className="brand__mark" aria-hidden="true">
            {/* Knight silhouette — matches the reference landing. */}
            <svg viewBox="0 0 45 45" fill="currentColor" aria-hidden="true" focusable="false">
              <path d="M22 10c10.5 1 16.5 8 16 29H15c0-9 10-6.5 8-21M24 18c.38 2.91-5.55 7.37-8 9-3 2-2.82 4.34-5 4-1.042-.94 1.41-3.04 0-3-1 0 .19 1.23-1 2-1 0-4.003 1-4-4 0-2 6-12 6-12s1.89-1.9 2-3.5c-.73-.994-.5-2-.5-3 1-1 3 2.5 3 2.5h2s.78-1.992 2.5-3c1 0 1 3 1 3" />
            </svg>
          </span>
          <span className="brand__name">{t("brand.name")}</span>
        </Link>

        <nav className="nav" aria-label={t("nav.features")}>
          <div className="locale-switch" role="group" aria-label={t("locale.switch")}>
            {routing.locales.map((locale) => {
              const isCurrent = locale === currentLocale;
              // `pathname` comes from next-intl's usePathname() — it's the
              // route *without* the locale prefix and it's a plain string.
              // Next 15's typedRoutes wants a Route literal here, but our
              // intent is "switch the locale on whatever route I'm on", so
              // we pass the dynamic string deliberately. Cast through the
              // typed-Link's expected href type to keep Next happy.
              const href = pathname as Parameters<typeof Link>[0]["href"];
              return (
                <Link
                  key={locale}
                  href={href}
                  locale={locale}
                  aria-current={isCurrent ? "page" : undefined}
                  lang={locale}
                >
                  {t(`locale.${locale}` as "locale.uz" | "locale.ru" | "locale.en")}
                </Link>
              );
            })}
          </div>

          <Link href="/auth/login" className="btn btn--ghost">
            {t("nav.login")}
          </Link>
        </nav>
      </div>
    </header>
  );
}
