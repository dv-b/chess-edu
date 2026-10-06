"use client";

import { useActionState } from "react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { issueInvitationAction, type IssueActionState } from "./actions";

const INITIAL: IssueActionState = {};

/**
 * "Invite someone new" form. The role dropdown only offers the three roles
 * a school admin is actually allowed to issue (SCHOOL_ADMIN, TEACHER,
 * PARENT) — STUDENT onboarding is deliberately routed through parent
 * invitations in M3, and PLATFORM_ADMIN is out of scope for this screen.
 */
export function InviteForm({ locale }: { locale: string }) {
  const t = useTranslations("admin.invitations.new");
  const [state, formAction, pending] = useActionState(
    issueInvitationAction,
    INITIAL,
  );
  const v = state.values ?? {};

  return (
    <form className="auth-card" action={formAction} noValidate>
      <input type="hidden" name="locale" value={locale} />
      <h1>{t("title")}</h1>
      <p className="auth-meta">{t("subtitle")}</p>

      {state.error ? (
        <div className="form-error" role="alert">
          {t(`error.${state.error}`)}
        </div>
      ) : null}

      <div className="field">
        <label htmlFor="email">{t("emailLabel")}</label>
        <input
          id="email"
          name="email"
          type="email"
          required
          autoComplete="off"
          defaultValue={v.email ?? ""}
          maxLength={255}
        />
      </div>

      <div className="field">
        <label htmlFor="displayName">{t("displayNameLabel")}</label>
        <input
          id="displayName"
          name="displayName"
          type="text"
          required
          autoComplete="off"
          defaultValue={v.displayName ?? ""}
          maxLength={120}
        />
      </div>

      <div className="field">
        <label htmlFor="role">{t("roleLabel")}</label>
        <select
          id="role"
          name="role"
          defaultValue={v.role ?? "TEACHER"}
          required
        >
          <option value="SCHOOL_ADMIN">{t("role.SCHOOL_ADMIN")}</option>
          <option value="TEACHER">{t("role.TEACHER")}</option>
          <option value="PARENT">{t("role.PARENT")}</option>
        </select>
      </div>

      <div className="field">
        <label htmlFor="preferredLocale">{t("preferredLocaleLabel")}</label>
        <select
          id="preferredLocale"
          name="preferredLocale"
          defaultValue={v.preferredLocale ?? locale}
        >
          <option value="uz">O‘zbekcha</option>
          <option value="ru">Русский</option>
          <option value="en">English</option>
        </select>
      </div>

      <div className="auth-actions">
        <button className="btn btn--primary" type="submit" disabled={pending}>
          {pending ? t("submitting") : t("submit")}
        </button>
        <Link href="/admin/invitations" className="btn btn--ghost">
          {t("cancel")}
        </Link>
      </div>
    </form>
  );
}
