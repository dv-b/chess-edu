"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";

export type SchoolOption = { id: string; displayName: string; slug: string };

/**
 * Platform admins have no school of their own, so the invitations screens
 * ask which tenant to act on. Changing the select rewrites `?schoolId=` on
 * the current URL (replace, not push — paging through schools shouldn't
 * fill the history). School admins never see this: their school is implied.
 */
export function SchoolPicker({ schools, selected }: { schools: SchoolOption[]; selected: string | null }) {
  const t = useTranslations("admin.invitations");
  const router = useRouter();
  const params = useSearchParams();

  function onChange(id: string) {
    const next = new URLSearchParams(params.toString());
    if (id) next.set("schoolId", id);
    else next.delete("schoolId");
    router.replace(`?${next.toString()}`, { scroll: false });
  }

  return (
    <div className="field school-picker">
      <label htmlFor="schoolId">{t("schoolLabel")}</label>
      <select id="schoolId" value={selected ?? ""} onChange={(e) => onChange(e.target.value)}>
        {schools.map((s) => (
          <option key={s.id} value={s.id}>
            {s.displayName}
          </option>
        ))}
      </select>
    </div>
  );
}
