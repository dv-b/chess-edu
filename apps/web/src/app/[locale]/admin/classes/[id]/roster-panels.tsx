"use client";

import { useActionState } from "react";
import { useTranslations } from "next-intl";
import {
  addStudentAction,
  addTeacherAction,
  removeStudentAction,
  removeTeacherAction,
  type RosterActionState,
} from "./actions";

type Member = { userId: string; email: string; displayName: string };

const INITIAL: RosterActionState = {};

/**
 * Two side-by-side panels: teachers on the left, students on the right.
 * Each has an "add" dropdown (filtered to users not already in the class)
 * and a remove-button on every row. Each form is independent so a failed
 * add doesn't disturb the others.
 */
export function RosterPanels({
  classId,
  locale,
  teachers,
  students,
  availableTeachers,
  availableStudents,
}: {
  classId: string;
  locale: string;
  teachers: Member[];
  students: Member[];
  availableTeachers: Member[];
  availableStudents: Member[];
}) {
  const t = useTranslations("admin.classes.detail");

  return (
    <section className="roster-grid">
      <RosterPanel
        title={t("teachers")}
        classId={classId}
        locale={locale}
        rows={teachers}
        available={availableTeachers}
        addAction={addTeacherAction}
        removeAction={removeTeacherAction}
        addLabel={t("addTeacher")}
        noneLabel={t("noTeachers")}
        noAvailableLabel={t("noEligibleTeachers")}
        t={t}
      />
      <RosterPanel
        title={t("students")}
        classId={classId}
        locale={locale}
        rows={students}
        available={availableStudents}
        addAction={addStudentAction}
        removeAction={removeStudentAction}
        addLabel={t("addStudent")}
        noneLabel={t("noStudents")}
        noAvailableLabel={t("noEligibleStudents")}
        t={t}
      />
    </section>
  );
}

type Action = (prev: RosterActionState, formData: FormData) => Promise<RosterActionState>;

function RosterPanel({
  title,
  classId,
  locale,
  rows,
  available,
  addAction,
  removeAction,
  addLabel,
  noneLabel,
  noAvailableLabel,
  t,
}: {
  title: string;
  classId: string;
  locale: string;
  rows: Member[];
  available: Member[];
  addAction: Action;
  removeAction: Action;
  addLabel: string;
  noneLabel: string;
  noAvailableLabel: string;
  t: (k: string) => string;
}) {
  const [addState, addFormAction, addPending] = useActionState(addAction, INITIAL);

  return (
    <div className="roster-panel">
      <h2 className="roster-panel__title">{title}</h2>

      {rows.length === 0 ? (
        <p className="field-hint">{noneLabel}</p>
      ) : (
        <ul className="roster-list">
          {rows.map((m) => (
            <li key={m.userId} className="roster-list__item">
              <div>
                <strong>{m.displayName}</strong>
                <br />
                <span className="field-hint">{m.email}</span>
              </div>
              <RemoveButton
                classId={classId}
                userId={m.userId}
                locale={locale}
                action={removeAction}
                label={t("remove")}
                pendingLabel={t("removing")}
                errorPrefix={(k) => t(`error.${k}`)}
              />
            </li>
          ))}
        </ul>
      )}

      <form className="roster-add" action={addFormAction}>
        <input type="hidden" name="classId" value={classId} />
        <input type="hidden" name="locale" value={locale} />
        {available.length === 0 ? (
          <p className="field-hint">{noAvailableLabel}</p>
        ) : (
          <>
            <label htmlFor={`${title}-add`} className="field-hint">
              {addLabel}
            </label>
            <div className="roster-add__row">
              <select id={`${title}-add`} name="userId" defaultValue="">
                <option value="" disabled>
                  {addLabel}
                </option>
                {available.map((m) => (
                  <option key={m.userId} value={m.userId}>
                    {m.displayName} — {m.email}
                  </option>
                ))}
              </select>
              <button className="btn btn--primary btn--sm" type="submit" disabled={addPending}>
                {addPending ? t("adding") : t("add")}
              </button>
            </div>
            {addState.error ? (
              <p className="form-error" role="alert">
                {t(`error.${addState.error}`)}
              </p>
            ) : null}
          </>
        )}
      </form>
    </div>
  );
}

function RemoveButton({
  classId,
  userId,
  locale,
  action,
  label,
  pendingLabel,
  errorPrefix,
}: {
  classId: string;
  userId: string;
  locale: string;
  action: Action;
  label: string;
  pendingLabel: string;
  errorPrefix: (code: string) => string;
}) {
  const [state, formAction, pending] = useActionState(action, INITIAL);
  return (
    <form action={formAction}>
      <input type="hidden" name="classId" value={classId} />
      <input type="hidden" name="userId" value={userId} />
      <input type="hidden" name="locale" value={locale} />
      <button className="btn btn--ghost btn--sm" type="submit" disabled={pending}>
        {pending ? pendingLabel : label}
      </button>
      {state.error ? (
        <span className="field-hint" role="alert">
          {errorPrefix(state.error)}
        </span>
      ) : null}
    </form>
  );
}
