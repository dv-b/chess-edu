"use client";

import { useEffect, useRef, useState, useTransition } from "react";
import { useTranslations } from "next-intl";
import {
  cancelTelegramLoginAction,
  pollTelegramLoginAction,
  startTelegramLoginAction,
} from "./actions";

const POLL_MS = 2000;

type Phase =
  | { kind: "idle" }
  | { kind: "starting" }
  | { kind: "waiting"; deeplink: string }
  | { kind: "error"; code: "unavailable" | "network" | "unknown" | "expired" };

/**
 * "Log in with Telegram" for already-linked accounts, without the Login
 * Widget (which needs a production domain registered with @BotFather).
 *
 * Click → Server Action creates a login request (keeps the client secret in
 * an HttpOnly cookie) and returns a t.me deeplink → we show an "Open
 * Telegram" link and poll every 2 s → the bot confirms → the poll action
 * installs the session and redirects. Works on one device (phone) and
 * across two (desktop browser + phone Telegram).
 *
 * CAREFUL: polling is driven by a single interval that is torn down on
 * phase change / unmount, and the poll action itself is the one that
 * redirects — don't add a second redirect on the client or you'll race it.
 */
export function TelegramLogin({ locale }: { locale: string }) {
  const t = useTranslations("auth.login.telegram");
  const [phase, setPhase] = useState<Phase>({ kind: "idle" });
  const [pending, startTransition] = useTransition();
  const polling = useRef(false);

  function start() {
    setPhase({ kind: "starting" });
    startTransition(async () => {
      const res = await startTelegramLoginAction();
      if (res.deeplink) {
        setPhase({ kind: "waiting", deeplink: res.deeplink });
      } else {
        setPhase({ kind: "error", code: res.error ?? "unknown" });
      }
    });
  }

  function cancel() {
    setPhase({ kind: "idle" });
    void cancelTelegramLoginAction();
  }

  useEffect(() => {
    if (phase.kind !== "waiting") return;
    let cancelled = false;
    const timer = setInterval(async () => {
      if (polling.current) return; // never overlap two polls
      polling.current = true;
      try {
        const res = await pollTelegramLoginAction(locale);
        if (cancelled) return;
        if (res.status === "expired") {
          setPhase({ kind: "error", code: "expired" });
        }
        // "pending" and transient "network" just keep waiting.
      } catch {
        // A successful poll redirects by throwing NEXT_REDIRECT, which the
        // framework handles; anything else is transient — keep polling.
      } finally {
        polling.current = false;
      }
    }, POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [phase.kind, locale]);

  if (phase.kind === "waiting") {
    return (
      <div className="auth-telegram" role="status" aria-live="polite">
        <p className="auth-telegram__title">{t("waitingTitle")}</p>
        <p className="auth-meta">{t("waitingHint")}</p>
        <a
          className="btn btn--primary auth-telegram__open"
          href={phase.deeplink}
          target="_blank"
          rel="noopener noreferrer"
        >
          {t("openTelegram")}
        </a>
        <button type="button" className="btn btn--ghost btn--sm" onClick={cancel}>
          {t("cancel")}
        </button>
      </div>
    );
  }

  return (
    <div className="auth-telegram">
      {phase.kind === "error" ? (
        <div className="form-error" role="alert">
          {t(`error.${phase.code}`)}
        </div>
      ) : null}
      <button
        type="button"
        className="btn btn--ghost auth-telegram__start"
        onClick={start}
        disabled={pending || phase.kind === "starting"}
      >
        {phase.kind === "starting" ? t("starting") : t("button")}
      </button>
      <p className="auth-meta">{t("hint")}</p>
    </div>
  );
}
