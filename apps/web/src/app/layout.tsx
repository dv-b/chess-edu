import "./globals.css";
import type { Metadata } from "next";
import type { ReactNode } from "react";

export const metadata: Metadata = {
  title: {
    default: "Kingside",
    template: "%s · Kingside",
  },
  description: "A chess education platform for schools.",
};

/**
 * Root layout is intentionally thin — every real page lives under
 * `[locale]/` so the next-intl provider and HTML `lang` attribute
 * can be set correctly from the URL.
 */
export default function RootLayout({ children }: { children: ReactNode }) {
  return children;
}
