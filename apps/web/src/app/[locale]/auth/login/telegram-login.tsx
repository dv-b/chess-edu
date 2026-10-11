"use client";

import { useActionState, useState } from "react";
import { useTranslations } from "next-intl";
import {
  cancelTelegramCodeAction,
  requestTelegramCodeAction,
  verifyTelegramCodeAction,
  type TelegramCodeRequestState,
  type TelegramCodeVerifyState,
} from "./actions";

const REQ_INITIAL: TelegramCodeRequestState = {};
const VER_INITIAL: TelegramCodeVerifyState = {};

/**
 * "Log in with Telegram" by one-time code — the pattern Telegram itself
 * uses. Three states on the login card:
 *
 *   idle      → a button
 *   ask       → "who are you?" (username / email / phone) → Send code
 *   code      → "we sent a code to your Telegram" → 6-digit input → Sign in
 *
 * Both steps are plain forms bound to Server Actions via useActionState;
 * the verify action installs the session and redirects, so a successful
 * code is an explicit navigation, not a page that changes under the user.
 * Works identically on desktop (code arrives on the phone, typed on the
 * desktop) and on the phone itself. No new tab, no deeplink.
 */
export function TelegramLogin({ locale }: { locale: string }) {
  const t = useTranslations("auth.login.telegram");
  const [open, setOpen] = useState(false);
  const [req, requestAction, requesting] = useActionState(requestTelegramCodeAction, REQ_INITIAL);
  const [ver, verifyAction, verifying] = useActionState(verifyTelegramCodeAction, VER_INITIAL);
  // "Back" from the code step must survive a later successful request, so
  // the step is derived from the request state but can be dismissed.
  const [dismissedRequest, setDismissedRequest] = useState<TelegramCodeRequestState | null>(null);
  const codeStep = Boolean(req.sent) && dismissedRequest !== req;

  function reset() {
    setDismissedRequest(req);
    setOpen(false);
    void cancelTelegramCodeAction();
  }

  if (!open) {
    return (
      <div className="auth-telegram">
        <button type="button" className="btn btn--ghost auth-telegram__start" onClick={() => setOpen(true)}>
          {t("button")}
        </button>
        <p className="auth-meta">{t("hint")}</p>
      </div>
    );
  }

  if (codeStep) {
    const expired = ver.error === "expired";
    return (
      <form className="auth-telegram" action={verifyAction} noValidate>
        <input type="hidden" name="locale" value={locale} />
        <p className="auth-telegram__title">{t("codeTitle")}</p>
        <p className="auth-meta">{t("codeHint", { identifier: req.identifier ?? "" })}</p>

        {ver.error ? (
          <div className="form-error" role="alert">{t(`error.${ver.error}`)}</div>
        ) : null}

        {!expired ? (
          <>
            <div className="field">
              <label htmlFor="tg-code">{t("codeLabel")}</label>
              <input
                id="tg-code"
                name="code"
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9 ]*"
                maxLength={8}
                placeholder="123456"
                autoFocus
                required
              />
            </div>
            <button className="btn btn--primary" type="submit" disabled={verifying}>
              {verifying ? t("verifying") : t("verify")}
            </button>
          </>
        ) : null}

        <button type="button" className="btn btn--ghost btn--sm" onClick={reset}>
          {expired ? t("startOver") : t("back")}
        </button>
      </form>
    );
  }

  return (
    <form className="auth-telegram" action={requestAction} noValidate>
      <p className="auth-telegram__title">{t("askTitle")}</p>
      <p className="auth-meta">{t("askHint")}</p>

      {req.error ? (
        <div className="form-error" role="alert">{t(`error.${req.error}`)}</div>
      ) : null}

      <div className="field">
        <label htmlFor="tg-identifier">{t("identifierLabel")}</label>
        <input
          id="tg-identifier"
          name="identifier"
          type="text"
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          placeholder={t("identifierPlaceholder")}
          defaultValue={req.identifier ?? ""}
          autoFocus
          required
        />
      </div>
      <button className="btn btn--primary" type="submit" disabled={requesting}>
        {requesting ? t("sending") : t("sendCode")}
      </button>
      <button type="button" className="btn btn--ghost btn--sm" onClick={reset}>
        {t("back")}
      </button>
    </form>
  );
}
