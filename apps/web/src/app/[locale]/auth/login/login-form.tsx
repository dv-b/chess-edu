"use client";

import { useActionState, useState } from "react";
import { useTranslations } from "next-intl";
import { TopBar } from "@/components/top-bar";
import { loginAction, type LoginActionState } from "./actions";
import { TelegramLogin } from "./telegram-login";

const INITIAL: LoginActionState = {};

/**
 * The actual interactive login form. Lives in a Client Component because
 * `useActionState` (React 19) and `useTranslations` (next-intl) both require
 * a client context. The Server Action runs on the server, talks to the API,
 * sets session cookies, and redirects — the client only renders the result
 * state (error + preserved email) it gets back.
 *
 * <p>Layout follows the Kingside Login reference: centered card on a warm
 * page, brand nav on top, a "role strip" tag below the card reminding users
 * that the same sign-in works for every persona, and a mono meta line.
 */
export function LoginForm({ locale }: { locale: string }) {
  const t = useTranslations("auth.login");
  const tBrand = useTranslations("brand");
  const tLanding = useTranslations("landing.footer");
  const [state, formAction, pending] = useActionState(loginAction, INITIAL);
  const [showPw, setShowPw] = useState(false);

  return (
    <div className="landing auth-page">
      <TopBar />
      <main className="container landing__body auth-stage">
        <div className="auth-stack">
          {/* The card is a <div>, not the <form>: the Telegram block below
              carries its own forms, and HTML forbids nesting forms (the browser
              silently drops the inner one, so its submit button does nothing). */}
          <div className="auth-card">
            <form className="auth-card__form" action={formAction} noValidate>
            <input type="hidden" name="locale" value={locale} />
            <h1 className="auth-card__title">{t("title")}</h1>

            {state.error ? (
              <div className="form-error" role="alert">
                {t(`error.${state.error}`)}
              </div>
            ) : null}

            <div className="field">
              <label htmlFor="login">{t("emailLabel")}</label>
              <input
                id="login"
                name="login"
                type="text"
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                placeholder="you@school.uz"
                required
                defaultValue={state.email ?? ""}
              />
            </div>

            <div className="field">
              <label htmlFor="password">{t("passwordLabel")}</label>
              <div className="field__input-wrap">
                <input
                  id="password"
                  name="password"
                  type={showPw ? "text" : "password"}
                  autoComplete="current-password"
                  placeholder="••••••••"
                  required
                />
                <button
                  type="button"
                  className="field__reveal"
                  onClick={() => setShowPw((v) => !v)}
                  aria-pressed={showPw}
                >
                  {showPw ? t("hide") : t("show")}
                </button>
              </div>
            </div>

            <button
              className="btn btn--primary auth-card__submit"
              type="submit"
              disabled={pending}
            >
              {pending ? t("submitting") : t("submit")}
            </button>
            </form>

            <div className="auth-divider" aria-hidden="true">
              <span>{t("or")}</span>
            </div>

            <TelegramLogin locale={locale} />

            <div className="auth-footer">
              <a href="#">{t("forgotPassword")}</a>
              <span className="auth-footer__noaccount">
                <span className="auth-footer__noaccount-copy">{t("noAccount")}</span>{" "}
                <a href="mailto:hello@chess-edu.local">{t("contactSales")}</a>
              </span>
            </div>
          </div>

          <div className="auth-role-strip" aria-hidden="true">
            <span className="auth-role-strip__dot" />
            <span>
              <strong>{t("roleStrip.strong")}</strong>{" "}
              <span>{t("roleStrip.rest")}</span>
            </span>
          </div>

          <div className="auth-meta" aria-hidden="true">
            {tBrand("name")} · {t("edition")}
          </div>
        </div>
      </main>

      <footer className="container footer auth-page__footer">
        <span>
          {tLanding("legal", {
            year: new Date().getFullYear(),
            brand: tBrand("name"),
          })}
        </span>
        <span className="footer__links">
          <a href="#">{tLanding("privacy")}</a>
          <a href="#">{tLanding("terms")}</a>
        </span>
      </footer>

      <div className="landing__badge" aria-hidden="true">
        <span>N</span>
      </div>
    </div>
  );
}
