import type { ReactNode } from "react";
import { NextIntlClientProvider } from "next-intl";
import { getMessages, setRequestLocale } from "next-intl/server";
import { notFound } from "next/navigation";
import { Inter, JetBrains_Mono, Newsreader } from "next/font/google";
import { routing, type AppLocale } from "@/i18n/routing";

/*
 * Design-kit typography (Kingside). Loaded via next/font so the browser
 * self-hosts the files — no FOUT, no external request. Each font exposes a
 * CSS variable that globals.css maps onto the --font-* tokens referenced
 * by the design-tokens package. Subsets are limited to what our UI
 * actually needs (latin + latin-ext) to keep bundle size sane.
 *
 * Newsreader for serif display headings (h-display / h-title), Inter for
 * body + chrome, JetBrains Mono for move notation, counters, and the
 * occasional mono stat. All three use display: "swap" so the browser
 * paints text immediately with a fallback and swaps when the web font is
 * ready.
 */
const inter = Inter({
  subsets: ["latin", "latin-ext"],
  variable: "--font-inter",
  display: "swap",
});

const newsreader = Newsreader({
  subsets: ["latin", "latin-ext"],
  weight: ["400", "500", "600", "700"],
  style: ["normal", "italic"],
  variable: "--font-newsreader",
  display: "swap",
});

const jetbrains = JetBrains_Mono({
  subsets: ["latin", "latin-ext"],
  weight: ["400", "500", "600"],
  variable: "--font-jetbrains",
  display: "swap",
});

export function generateStaticParams() {
  return routing.locales.map((locale) => ({ locale }));
}

type LayoutProps = {
  children: ReactNode;
  params: Promise<{ locale: string }>;
};

export default async function LocaleLayout({ children, params }: LayoutProps) {
  const { locale } = await params;

  if (!routing.locales.includes(locale as AppLocale)) {
    notFound();
  }

  setRequestLocale(locale);

  const messages = await getMessages();

  return (
    <html
      lang={locale}
      suppressHydrationWarning
      className={`${inter.variable} ${newsreader.variable} ${jetbrains.variable}`}
    >
      <head>
        {/*
         * Apply the persisted theme before React hydrates. Without this the
         * tokens flash to the system default and then snap to the user's
         * choice a moment later. Runs once, inline, as soon as <head> is
         * parsed, so it's cheap and blocks for < 1 ms.
         */}
        <script
          dangerouslySetInnerHTML={{
            __html: `(function(){try{var t=localStorage.getItem('ks-theme');if(t==='light'||t==='dark'){document.documentElement.setAttribute('data-theme',t);}}catch(e){}})();`,
          }}
        />
      </head>
      <body suppressHydrationWarning>
        <NextIntlClientProvider locale={locale} messages={messages} timeZone="Asia/Tashkent">
          {children}
        </NextIntlClientProvider>
      </body>
    </html>
  );
}
