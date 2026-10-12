"use client";

import { useActionState } from "react";
import { useTranslations } from "next-intl";
import {
  setPasswordAction,
  setUsernameAction,
  type PasswordActionState,
  type UsernameActionState,
} from "./actions";

const U_INITIAL: UsernameActionState = {};
const P_INITIAL: PasswordActionState = {};

type Me = {
  email: string;
  username: string | null;
  hasPassword: boolean;
  telegramLinked: boolean;
};

/**
 * Two independent forms on the account page: username, and set/change
 * password. For a Telegram-signup user (no password yet) the password form
 * omits the "current password" field — that's the whole point of the page:
 * giving them username + password as a second way in.
 */
export function AccountForms({ locale, me }: { locale: string; me: Me }) {
  const t = useTranslations("account");
  const [u, usernameAction, uPending] = useActionState(setUsernameAction, U_INITIAL);
  const [p, passwordAction, pPending] = useActionState(setPasswordAction, P_INITIAL);

  // Jackson drops nulls → `undefined`; loose check on purpose.
  const currentUsername = u.value ?? (me.username == null ? "" : me.username);
  const hasPassword = p.ok ? true : me.hasPassword;

  return (
    <>
      <form className="auth-card" action={usernameAction} noValidate>
        <input type="hidden" name="locale" value={locale} />
        <h2>{t("username.title")}</h2>
        <p className="auth-meta">{t("username.hint")}</p>

        {u.error ? (
          <div className="form-error" role="alert">{t(`username.error.${u.error}`)}</div>
        ) : null}
        {u.ok ? (
          <div className="form-success" role="status">{t("username.saved")}</div>
        ) : null}

        <div className="field">
          <label htmlFor="username">{t("username.label")}</label>
          <input
            id="username"
            name="username"
            type="text"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            minLength={3}
            maxLength={32}
            pattern="[A-Za-z0-9][A-Za-z0-9_.]{2,31}"
            defaultValue={currentUsername}
            key={currentUsername}
          />
        </div>

        <div className="auth-actions">
          <button className="btn btn--primary" type="submit" disabled={uPending}>
            {uPending ? t("saving") : t("username.submit")}
          </button>
        </div>
      </form>

      <form className="auth-card" action={passwordAction} noValidate>
        <input type="hidden" name="locale" value={locale} />
        <h2>{hasPassword ? t("password.changeTitle") : t("password.setTitle")}</h2>
        <p className="auth-meta">
          {hasPassword ? t("password.changeHint") : t("password.setHint")}
        </p>

        {p.error ? (
          <div className="form-error" role="alert">{t(`password.error.${p.error}`)}</div>
        ) : null}
        {p.ok ? (
          <div className="form-success" role="status">{t("password.saved")}</div>
        ) : null}

        {hasPassword ? (
          <div className="field">
            <label htmlFor="currentPassword">{t("password.current")}</label>
            <input
              id="currentPassword"
              name="currentPassword"
              type="password"
              autoComplete="current-password"
              required
            />
          </div>
        ) : null}

        <div className="field">
          <label htmlFor="newPassword">{t("password.new")}</label>
          <input
            id="newPassword"
            name="newPassword"
            type="password"
            autoComplete="new-password"
            minLength={8}
            maxLength={200}
            required
          />
          <p className="auth-meta">{t("password.rule")}</p>
        </div>

        <div className="field">
          <label htmlFor="confirmPassword">{t("password.confirm")}</label>
          <input
            id="confirmPassword"
            name="confirmPassword"
            type="password"
            autoComplete="new-password"
            required
          />
        </div>

        <div className="auth-actions">
          <button className="btn btn--primary" type="submit" disabled={pPending}>
            {pPending ? t("saving") : hasPassword ? t("password.changeSubmit") : t("password.setSubmit")}
          </button>
        </div>
      </form>
    </>
  );
}
