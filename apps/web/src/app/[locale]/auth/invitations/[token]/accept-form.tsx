"use client";

import { useActionState } from "react";
import { useTranslations } from "next-intl";
import { TopBar } from "@/components/top-bar";
import { acceptInvitationAction, type AcceptActionState } from "./actions";

const INITIAL: AcceptActionState = {};

type Preview = {
  email: string;
  displayName: string;
  role: string;
  schoolName: string | null;
  preferredLocale: string | null;
  expiresAt: string;
};

/**
 * Accept-invitation form. The recipient sees a read-only summary of what
 * they're being invited to, then sets a password. On success the Server
 * Action installs session cookies and redirects to `/admin`.
 */
export function AcceptInvitationForm({
  locale,
  token,
  preview,
  expiresAtLabel,
}: {
  locale: string;
  token: string;
  preview: Preview;
  expiresAtLabel: string;
}) {
  const t = useTranslations("auth.invitation.accept");
  const tRoles = useTranslations("admin.invitations.new.role");
  const [state, formAction, pending] = useActionState(
    acceptInvitationAction,
    INITIAL,
  );

  const roleLabel = tRoleSafe(tRoles, preview.role);

  const title = preview.schoolName
    ? t("title", { schoolName: preview.schoolName })
    : t("titleNoSchool");
  const meta = preview.schoolName
    ? t("meta", { role: roleLabel, schoolName: preview.schoolName })
    : t("metaNoSchool", { role: roleLabel });

  return (
    <>
      <TopBar />
      <main className="auth-shell">
        <form className="auth-card" action={formAction} noValidate>
          <input type="hidden" name="locale" value={locale} />
          <input type="hidden" name="token" value={token} />

          <h1>{title}</h1>
          <p className="auth-meta">{meta}</p>

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
              value={preview.email}
              readOnly
              disabled
            />
          </div>

          <div className="field">
            <label htmlFor="displayName">{t("displayNameLabel")}</label>
            <input
              id="displayName"
              name="displayName"
              type="text"
              autoComplete="name"
              defaultValue={state.displayName ?? preview.displayName}
              required
              maxLength={120}
            />
          </div>

          <div className="field">
            <label htmlFor="password">{t("passwordLabel")}</label>
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="new-password"
              required
              minLength={8}
              maxLength={200}
              aria-describedby="password-hint"
            />
            <small id="password-hint" className="field-hint">
              {t("passwordHint")}
            </small>
          </div>

          <div className="field">
            <label htmlFor="preferredLocale">{t("preferredLocaleLabel")}</label>
            <select
              id="preferredLocale"
              name="preferredLocale"
              defaultValue={preview.preferredLocale ?? locale}
            >
              <option value="uz">O‘zbekcha</option>
              <option value="ru">Русский</option>
              <option value="en">English</option>
            </select>
          </div>

          <button
            className="btn btn--primary"
            type="submit"
            style={{ width: "100%" }}
            disabled={pending}
          >
            {pending ? t("submitting") : t("submit")}
          </button>

          <p className="auth-expires">{t("expiresAt", { date: expiresAtLabel })}</p>
        </form>
      </main>
    </>
  );
}

/**
 * `tRoles(key)` would throw if `key` isn't one of our three known values.
 * Backend could still introduce a new role before we translate it; fall
 * back to the raw string rather than crashing the accept flow.
 */
function tRoleSafe(tRoles: (k: string) => string, role: string): string {
  try {
    return tRoles(role);
  } catch {
    return role;
  }
}
