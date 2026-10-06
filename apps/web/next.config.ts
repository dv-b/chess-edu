import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

const withNextIntl = createNextIntlPlugin("./src/i18n.ts");

const nextConfig: NextConfig = {
  reactStrictMode: true,
  // `typedRoutes` graduated out of `experimental` in Next 15; `turbopack` is
  // the new top-level slot for turbopack options (we don't need any today).
  typedRoutes: true,
  env: {
    NEXT_PUBLIC_APP_NAME: "Kingside",
  },
};

export default withNextIntl(nextConfig);
