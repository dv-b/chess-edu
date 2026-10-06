"use client";

import { useTranslations } from "next-intl";
import { logoutAction } from "./actions";

/**
 * Small "sign out" affordance tucked into the sidebar's user pill — icon
 * only with an accessible label so it sits cleanly at the row's right edge
 * instead of competing with the name / meta copy.
 */
export function LogoutButton({ locale }: { locale: string }) {
  const t = useTranslations("student");
  return (
    <form action={logoutAction} className="student-sidebar__logout">
      <input type="hidden" name="locale" value={locale} />
      <button
        type="submit"
        className="student-sidebar__logout-btn"
        aria-label={t("logout")}
        title={t("logout")}
      >
        <svg
          viewBox="0 0 20 20"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.6"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M12 3h3.5A1.5 1.5 0 0 1 17 4.5v11a1.5 1.5 0 0 1-1.5 1.5H12" />
          <path d="M8 14l-4-4 4-4" />
          <path d="M4 10h9" />
        </svg>
      </button>
    </form>
  );
}
