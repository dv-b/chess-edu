"use client";

import { useActionState, useEffect, useRef } from "react";
import { useTranslations } from "next-intl";
import { TopBar } from "@/components/top-bar";
import { acceptTelegramAction, type TelegramAcceptState } from "./actions";

const INITIAL: TelegramAcceptState = {};

/**
 * Landing for the bot's `/auth/accept?ott=…` deeplink. There is nothing for
 * the user to fill in — the token is the whole credential — so the form
 * submits itself once on mount and shows a spinner-ish "signing you in"
 * card. The Server Action installs the session cookies and redirects; only
 * failures ever render anything else.
 *
 * It's still a real <form> (not a bare effect calling the action) so a
 * blocked auto-submit (JS error, strict-mode double mount, etc.) leaves the
 * user with a working button instead of a dead page.
 */
export function TelegramAcceptForm({
  locale,
  ott,
}: {
  locale: string;
  ott: string;
}) {
  const t = useTranslations("auth.telegramAccept");
  const [state, formAction, pending] = useActionState(
    acceptTelegramAction,
    INITIAL,
  );
  const formRef = useRef<HTMLFormElement>(null);
  const submitted = useRef(false);

  // CAREFUL: fire exactly once. The OTT is single-use, so a second submit
  // (React strict-mode re-running effects in dev) would turn a successful
  // redemption into an "expired" error. The ref guards against that.
  useEffect(() => {
    if (submitted.current) return;
    submitted.current = true;
    formRef.current?.requestSubmit();
  }, []);

  const failed = Boolean(state.error);

  return (
    <>
      <TopBar />
      <main className="auth-shell">
        <form ref={formRef} className="auth-card" action={formAction} noValidate>
          <input type="hidden" name="locale" value={locale} />
          <input type="hidden" name="ott" value={ott} />

          <h1>{failed ? t("errorTitle") : t("title")}</h1>
          <p className="auth-meta">
            {failed ? t(`error.${state.error}`) : t("subtitle")}
          </p>

          {failed ? (
            <div className="auth-actions">
              {state.error === "network" || state.error === "unknown" ? (
                <button className="btn btn--primary" type="submit" disabled={pending}>
                  {pending ? t("retrying") : t("retry")}
                </button>
              ) : null}
              <a href={`/${locale}/auth/login`} className="btn btn--ghost">
                {t("backToLogin")}
              </a>
            </div>
          ) : (
            <p className="auth-meta" aria-live="polite">
              {t("working")}
            </p>
          )}
        </form>
      </main>
    </>
  );
}
