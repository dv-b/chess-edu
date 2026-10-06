import { getRequestConfig } from "next-intl/server";
import { notFound } from "next/navigation";
import { routing } from "@/i18n/routing";

export default getRequestConfig(async ({ requestLocale }) => {
  // `requestLocale` is the locale detected from the URL by the middleware.
  let locale = await requestLocale;

  if (!locale || !routing.locales.includes(locale as (typeof routing.locales)[number])) {
    locale = routing.defaultLocale;
  }

  try {
    const messages = (await import(`../messages/${locale}.json`)).default;
    return {
      locale,
      messages,
      timeZone: "Asia/Tashkent",
      now: new Date(),
    };
  } catch {
    notFound();
  }
});
