import { defineRouting } from "next-intl/routing";
import { createNavigation } from "next-intl/navigation";

/**
 * Three locales ship at launch: Uzbek (default), Russian, English.
 * Locale lives in the URL segment (`/uz/...`) — simple, shareable,
 * and trivial to cache per-locale at the edge later on.
 */
export const routing = defineRouting({
  locales: ["uz", "ru", "en"] as const,
  defaultLocale: "uz",
  localePrefix: "always",
});

export type AppLocale = (typeof routing.locales)[number];

export const { Link, redirect, usePathname, useRouter } = createNavigation(routing);
