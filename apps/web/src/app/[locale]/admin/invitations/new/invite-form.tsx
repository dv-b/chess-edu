"use client";

import { useActionState, useState } from "react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { issueInvitationAction, type IssueActionState } from "./actions";
import type { SchoolOption } from "../school-picker";

const INITIAL: IssueActionState = {};

/**
 * Shown instead of the form once a TELEGRAM invite is issued. The deeplink
 * is the invitee's only way in (no email is sent for this channel), so it
 * gets a copy button and stays on screen until the admin navigates away.
 */
function IssuedTelegramCard({
  email,
  deeplink,
  t,
}: {
  email: string;
  deeplink: string;
  t: ReturnType<typeof useTranslations<"admin.invitations.new">>;
}) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(deeplink);
      setCopied(true);
    } catch {
      // Clipboard can be unavailable (http origin, permissions) — the
      // read-only input below is still selectable by hand.
    }
  }

  return (
    <div className="auth-card">
      <h1>{t("issuedTitle")}</h1>
      <p className="auth-meta">{t("issuedTelegram", { email })}</p>

      <div className="field">
        <label htmlFor="deeplink">{t("deeplinkLabel")}</label>
        <input id="deeplink" type="text" readOnly value={deeplink} onFocus={(e) => e.currentTarget.select()} />
      </div>

      <div className="auth-actions">
        <button className="btn btn--primary" type="button" onClick={copy}>
          {copied ? t("copied") : t("copy")}
        </button>
        <Link href="/admin/invitations" className="btn btn--ghost">
          {t("backToList")}
        </Link>
      </div>
    </div>
  );
}

/**
 * "Invite someone new" form. The role dropdown only offers the three roles
 * a school admin is actually allowed to issue (SCHOOL_ADMIN, TEACHER,
 * PARENT) — STUDENT onboarding is deliberately routed through parent
 * invitations in M3, and PLATFORM_ADMIN is out of scope for this screen.
 */
export function InviteForm({
  locale,
  schools = [],
  initialSchoolId = null,
}: {
  locale: string;
  /** Non-empty only for platform admins, who must pick the tenant. */
  schools?: SchoolOption[];
  initialSchoolId?: string | null;
}) {
  const t = useTranslations("admin.invitations.new");
  const [state, formAction, pending] = useActionState(
    issueInvitationAction,
    INITIAL,
  );
  const v = state.values ?? {};

  if (state.issued) {
    return (
      <IssuedTelegramCard
        email={state.issued.email}
        deeplink={state.issued.deeplink}
        t={t}
      />
    );
  }

  return (
    <form className="auth-card" action={formAction} noValidate>
      <input type="hidden" name="locale" value={locale} />
      <h1>{t("title")}</h1>
      <p className="auth-meta">{t("subtitle")}</p>

      {state.error ? (
        <div className="form-error" role="alert">
          {t(`error.${state.error}`)}
          {state.detail ? <p className="form-error__detail">{state.detail}</p> : null}
        </div>
      ) : null}

      {schools.length > 0 ? (
        <div className="field">
          <label htmlFor="schoolId">{t("schoolLabel")}</label>
          <select id="schoolId" name="schoolId" defaultValue={v.schoolId ?? initialSchoolId ?? ""} required>
            {schools.map((s) => (
              <option key={s.id} value={s.id}>
                {s.displayName}
              </option>
            ))}
          </select>
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

      <div className="field">
        <label htmlFor="deliveryChannel">{t("channelLabel")}</label>
        <select
          id="deliveryChannel"
          name="deliveryChannel"
          defaultValue={v.deliveryChannel ?? "EMAIL"}
        >
          <option value="EMAIL">{t("channel.EMAIL")}</option>
          <option value="TELEGRAM">{t("channel.TELEGRAM")}</option>
        </select>
        <p className="auth-meta">{t("channelHint")}</p>
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
