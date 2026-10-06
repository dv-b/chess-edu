"use client";

import { useActionState } from "react";
import { useTranslations } from "next-intl";
import {
  archiveClassAction,
  reactivateClassAction,
  type RosterActionState,
} from "./actions";

const INITIAL: RosterActionState = {};

/**
 * Little two-state form: the right-hand action button either archives an
 * active class or reactivates an archived one. We keep it as a server
 * action (no client JS needed beyond the pending spinner) so archive/revive
 * survives even if JS is disabled.
 */
export function ClassStatusForm({
  classId,
  status,
  locale,
}: {
  classId: string;
  status: "ACTIVE" | "ARCHIVED";
  locale: string;
}) {
  const t = useTranslations("admin.classes.detail");
  const action = status === "ACTIVE" ? archiveClassAction : reactivateClassAction;
  const [state, formAction, pending] = useActionState(action, INITIAL);

  return (
    <form action={formAction}>
      <input type="hidden" name="classId" value={classId} />
      <input type="hidden" name="locale" value={locale} />
      <button className="btn btn--ghost btn--sm" type="submit" disabled={pending}>
        {pending
          ? t("statusPending")
          : status === "ACTIVE"
            ? t("archive")
            : t("reactivate")}
      </button>
      {state.error ? (
        <span className="field-hint" role="alert">
          {t(`error.${state.error}`)}
        </span>
      ) : null}
    </form>
  );
}
