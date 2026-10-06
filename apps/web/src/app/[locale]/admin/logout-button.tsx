"use client";

import { useTranslations } from "next-intl";
import { logoutAction } from "./actions";

/**
 * Tiny wrapper around a Server Action so we get useful UX (translated label,
 * button state) without loading a full client-side auth store.
 */
export function LogoutButton({ locale }: { locale: string }) {
  const t = useTranslations("admin");
  return (
    <form action={logoutAction}>
      <input type="hidden" name="locale" value={locale} />
      <button type="submit" className="btn btn--ghost">
        {t("logout")}
      </button>
    </form>
  );
}
