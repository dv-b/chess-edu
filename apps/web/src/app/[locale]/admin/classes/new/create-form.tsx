"use client";

import { useActionState } from "react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { createClassAction, type CreateClassActionState } from "./actions";

const INITIAL: CreateClassActionState = {};

/**
 * Create-a-class form. Level is optional — not every school labels classes
 * by level. On success the action redirects to the new class's detail page.
 */
export function CreateClassForm({ locale }: { locale: string }) {
  const t = useTranslations("admin.classes.new");
  const [state, formAction, pending] = useActionState(
    createClassAction,
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
        <label htmlFor="name">{t("nameLabel")}</label>
        <input
          id="name"
          name="name"
          type="text"
          required
          autoComplete="off"
          defaultValue={v.name ?? ""}
          maxLength={160}
        />
      </div>

      <div className="field">
        <label htmlFor="level">{t("levelLabel")}</label>
        <select id="level" name="level" defaultValue={v.level ?? ""}>
          <option value="">{t("levelAny")}</option>
          <option value="BEGINNER">{t("level.BEGINNER")}</option>
          <option value="INTERMEDIATE">{t("level.INTERMEDIATE")}</option>
          <option value="ADVANCED">{t("level.ADVANCED")}</option>
        </select>
      </div>

      <div className="field">
        <label htmlFor="description">{t("descriptionLabel")}</label>
        <textarea
          id="description"
          name="description"
          rows={3}
          defaultValue={v.description ?? ""}
          maxLength={2000}
        />
      </div>

      <div className="auth-actions">
        <button className="btn btn--primary" type="submit" disabled={pending}>
          {pending ? t("submitting") : t("submit")}
        </button>
        <Link href="/admin/classes" className="btn btn--ghost">
          {t("cancel")}
        </Link>
      </div>
    </form>
  );
}
