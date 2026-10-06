"use client";

import { useActionState, useState } from "react";
import { useTranslations } from "next-intl";
import {
  addChildAction,
  unlinkChildAction,
  type ChildActionState,
} from "./actions";

/**
 * Mirrors the ChildView record on the API. Only fields the UI actually uses
 * are typed strictly; anything the backend tacks on later is ignored here.
 */
export type Child = {
  studentUserId: string;
  displayName: string;
  preferredLocale: string;
  userStatus: "INVITED" | "ACTIVE" | "SUSPENDED" | "ARCHIVED" | string;
  membershipStatus: "ACTIVE" | "SUSPENDED" | "ARCHIVED" | string;
  linkedAt: string;
};

const INITIAL: ChildActionState = {};

/**
 * Client island for the parent dashboard. Left column is the list of
 * children (one card each, with a remove button); right column is a small
 * add-a-child form. On wide screens they sit side by side; below 900px
 * they stack so the form stays fully usable on mobile.
 */
export function ChildrenPanels({
  children,
  locale,
}: {
  children: Child[];
  locale: string;
}) {
  const t = useTranslations("parent.children");

  return (
    <section className="children-section">
      <ChildListPanel t={t} children={children} locale={locale} />
      <AddChildPanel t={t} locale={locale} />
    </section>
  );
}

// ----------------------------------------------------------------------------
// List
// ----------------------------------------------------------------------------

type TFn = {
  (key: string): string;
  (key: string, values: Record<string, string | number | Date>): string;
};

function ChildListPanel({
  t,
  children,
  locale,
}: {
  t: TFn;
  children: Child[];
  locale: string;
}) {
  const dateFmt = new Intl.DateTimeFormat(locale, { dateStyle: "medium" });

  return (
    <article className="children-card">
      <header className="children-card__header">
        <h2 className="children-card__title">{t("listTitle")}</h2>
        <span className="children-count" aria-label={t("countLabel")}>
          {children.length}
        </span>
      </header>

      {children.length === 0 ? (
        <p className="children-empty">{t("empty")}</p>
      ) : (
        <ul className="children-list">
          {children.map((child) => (
            <li key={child.studentUserId} className="child-row">
              <div className="child-row__body">
                <div className="child-row__name">{child.displayName}</div>
                <div className="child-row__meta">
                  {t("joinedOn", { date: dateFmt.format(new Date(child.linkedAt)) })}
                  {" · "}
                  <span className={`child-status child-status--${statusTone(child.membershipStatus)}`}>
                    {translateStatus(t, child.membershipStatus)}
                  </span>
                </div>
              </div>
              <UnlinkButton
                studentId={child.studentUserId}
                locale={locale}
                t={t}
                displayName={child.displayName}
              />
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}

function UnlinkButton({
  studentId,
  locale,
  t,
  displayName,
}: {
  studentId: string;
  locale: string;
  t: TFn;
  displayName: string;
}) {
  const [state, formAction, pending] = useActionState(unlinkChildAction, INITIAL);

  return (
    <form
      action={formAction}
      onSubmit={(e) => {
        if (!window.confirm(t("unlinkConfirm", { name: displayName }))) {
          e.preventDefault();
        }
      }}
    >
      <input type="hidden" name="studentId" value={studentId} />
      <input type="hidden" name="locale" value={locale} />
      <button
        type="submit"
        className="btn btn--ghost btn--sm"
        disabled={pending}
        aria-busy={pending}
      >
        {pending ? t("unlinking") : t("unlink")}
      </button>
      {state.error ? (
        <span className="child-error" role="alert">
          {t(`error.${state.error}`)}
        </span>
      ) : null}
    </form>
  );
}

// ----------------------------------------------------------------------------
// Add form
// ----------------------------------------------------------------------------

function AddChildPanel({ t, locale }: { t: TFn; locale: string }) {
  const [state, formAction, pending] = useActionState(addChildAction, INITIAL);
  // Clear the name between submits by keying the input off attempt count.
  // We don't reset on error so the parent can fix a typo without re-typing.
  const [preferredLocale, setPreferredLocale] = useState(locale);

  return (
    <article className="children-card children-card--form">
      <header className="children-card__header">
        <h2 className="children-card__title">{t("addTitle")}</h2>
      </header>
      <p className="children-subtitle">{t("addSubtitle")}</p>
      <form action={formAction} className="child-form">
        <input type="hidden" name="locale" value={locale} />
        <label className="child-form__field">
          <span className="child-form__label">{t("displayNameLabel")}</span>
          <input
            type="text"
            name="displayName"
            required
            maxLength={160}
            autoComplete="off"
            placeholder={t("displayNamePlaceholder")}
          />
        </label>
        <label className="child-form__field">
          <span className="child-form__label">{t("preferredLocaleLabel")}</span>
          <select
            name="preferredLocale"
            value={preferredLocale}
            onChange={(e) => setPreferredLocale(e.target.value)}
          >
            <option value="uz">O‘zbekcha</option>
            <option value="ru">Русский</option>
            <option value="en">English</option>
          </select>
        </label>
        <div className="child-form__footer">
          <button
            type="submit"
            className="btn btn--primary"
            disabled={pending}
            aria-busy={pending}
          >
            {pending ? t("submitting") : t("submit")}
          </button>
          {state.error ? (
            <p className="child-form__error" role="alert">
              {t(`error.${state.error}`)}
            </p>
          ) : null}
        </div>
      </form>
    </article>
  );
}

// ----------------------------------------------------------------------------
// Helpers
// ----------------------------------------------------------------------------

function translateStatus(t: TFn, status: string): string {
  try {
    return t(`status.${status}`);
  } catch {
    return status;
  }
}

function statusTone(status: string): "active" | "muted" {
  return status === "ACTIVE" ? "active" : "muted";
}
