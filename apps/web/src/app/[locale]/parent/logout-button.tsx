"use client";

import { useTranslations } from "next-intl";
import { logoutAction } from "./actions";

export function LogoutButton({ locale }: { locale: string }) {
  const t = useTranslations("parent");
  return (
    <form action={logoutAction}>
      <input type="hidden" name="locale" value={locale} />
      <button type="submit" className="btn btn--ghost">
        {t("logout")}
      </button>
    </form>
  );
}
