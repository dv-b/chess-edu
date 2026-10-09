"use client";

import { Link, usePathname } from "@/i18n/routing";
import { useTranslations } from "next-intl";
import { LogoutButton } from "./logout-button";

type Me = {
  userId: string;
  email: string;
  displayName: string;
  preferredLocale: string;
  role: string | null;
  platformRole: string | null;
};

/**
 * Derive a two-letter monogram from the student's display name. Matches
 * the kit's avatar tile ("AK" for "Asliddin K."). Falls back to the first
 * two characters of the email when no display name is set.
 */
function initialsFor(me: Me): string {
  const source = me.displayName.trim() || me.email;
  const pieces = source.split(/\s+/).filter(Boolean);
  if (pieces.length === 0) return "??";
  if (pieces.length === 1) {
    return pieces[0]!.slice(0, 2).toUpperCase();
  }
  return (pieces[0]![0]! + pieces[pieces.length - 1]![0]!).toUpperCase();
}

/**
 * Shortened display name for the user pill — tries to keep it on one line
 * in the narrow sidebar column. "Asliddin Karimov" → "Asliddin K.".
 */
function shortNameFor(me: Me): string {
  const full = me.displayName.trim();
  if (!full) return me.email;
  const pieces = full.split(/\s+/).filter(Boolean);
  if (pieces.length <= 1) return pieces[0] ?? full;
  return `${pieces[0]} ${pieces[pieces.length - 1]![0]!}.`;
}

/**
 * Map the current pathname to the nav section it belongs to. Detail
 * routes under a section should keep that section highlighted — so a
 * student on `/student/homework/<id>` still sees **Homework** as the
 * active item, and `/student/lessons/<id>` (once the viewer ships)
 * keeps **Lessons** on. The path lookup is locale-stripped by
 * next-intl's `usePathname` — we match on the trailing portion.
 */
type Section = "homework" | "lessons" | "questions" | null;

function sectionFromPath(pathname: string): Section {
  // next-intl's usePathname strips the locale prefix, so pathname looks
  // like "/student" or "/student/lessons/xyz". Guard against a trailing
  // slash or query noise.
  const clean = pathname.split("?")[0]!.replace(/\/+$/, "") || "/";
  if (clean === "/student" || clean.startsWith("/student/homework")) {
    return "homework";
  }
  if (clean.startsWith("/student/lessons")) {
    return "lessons";
  }
  if (clean.startsWith("/student/questions")) {
    return "questions";
  }
  return null;
}

type Props = {
  me: Me;
  locale: string;
  /** Number of active (non-finished) homework rows — renders as a badge. */
  homeworkCount: number;
  /** Number of lessons the student hasn't marked READ — renders as a badge. */
  lessonCount: number;
  /**
   * Number of unresolved Q&A threads the student has opened across all
   * classes. Resolved threads (teacher-resolve or self-resolve) drop
   * off the count. Renders as a badge on the Questions link.
   */
  questionsCount: number;
};

/**
 * Left navigation rail for every `/student/*` route. Mirrors the
 * `homework-v2.html` mock: Kingside brand tile + "Learn" and "Play"
 * groupings, then a user pill pinned to the bottom. Homework + Lessons
 * are live links; the other slots render as disabled placeholders so
 * the chrome matches the design brief while the underlying pages
 * catch up.
 */
export function StudentSidebar({
  me,
  locale,
  homeworkCount,
  lessonCount,
  questionsCount,
}: Props) {
  const t = useTranslations("student.nav");
  const pathname = usePathname();
  const section = sectionFromPath(pathname);
  const initials = initialsFor(me);
  const shortName = shortNameFor(me);

  return (
    <aside className="student-sidebar" aria-label={t("label")}>
      <Link href="/student" className="student-sidebar__brand">
        <span className="student-sidebar__brand-tile" aria-hidden="true">
          <svg
            viewBox="0 0 45 45"
            width="14"
            height="14"
            fill="currentColor"
          >
            <path d="M22 10c10.5 1 16.5 8 16 29H15c0-9 10-6.5 8-21M24 18c.38 2.91-5.55 7.37-8 9-3 2-2.82 4.34-5 4-1.042-.94 1.41-3.04 0-3-1 0 .19 1.23-1 2-1 0-4.003 1-4-4 0-2 6-12 6-12s1.89-1.9 2-3.5c-.73-.994-.5-2-.5-3 1-1 3 2.5 3 2.5h2s.78-1.992 2.5-3c1 0 1 3 1 3" />
          </svg>
        </span>
        <span className="student-sidebar__brand-name">Kingside</span>
      </Link>

      <div className="student-sidebar__group-head">{t("learn")}</div>
      <ul className="student-sidebar__list">
        <li>
          <span
            className="student-sidebar__item student-sidebar__item--disabled"
            aria-disabled="true"
          >
            {t("dashboard")}
          </span>
        </li>
        <li>
          <Link
            href="/student"
            className={
              "student-sidebar__item" +
              (section === "homework" ? " student-sidebar__item--on" : "")
            }
            aria-current={section === "homework" ? "page" : undefined}
          >
            <span>{t("homework")}</span>
            {homeworkCount > 0 ? (
              <span className="student-sidebar__count">{homeworkCount}</span>
            ) : null}
          </Link>
        </li>
        <li>
          <Link
            href="/student/lessons"
            className={
              "student-sidebar__item" +
              (section === "lessons" ? " student-sidebar__item--on" : "")
            }
            aria-current={section === "lessons" ? "page" : undefined}
          >
            <span>{t("lessons")}</span>
            {lessonCount > 0 ? (
              <span className="student-sidebar__count">{lessonCount}</span>
            ) : null}
          </Link>
        </li>
        <li>
          <Link
            href="/student/questions"
            className={
              "student-sidebar__item" +
              (section === "questions" ? " student-sidebar__item--on" : "")
            }
            aria-current={section === "questions" ? "page" : undefined}
          >
            <span>{t("questions")}</span>
            {questionsCount > 0 ? (
              <span className="student-sidebar__count">{questionsCount}</span>
            ) : null}
          </Link>
        </li>
        <li>
          <span
            className="student-sidebar__item student-sidebar__item--disabled"
            aria-disabled="true"
          >
            {t("puzzles")}
          </span>
        </li>
      </ul>

      <div className="student-sidebar__group-head">{t("play")}</div>
      <ul className="student-sidebar__list">
        <li>
          <span
            className="student-sidebar__item student-sidebar__item--disabled"
            aria-disabled="true"
          >
            {t("classmates")}
          </span>
        </li>
        <li>
          <span
            className="student-sidebar__item student-sidebar__item--disabled"
            aria-disabled="true"
          >
            {t("tournaments")}
          </span>
        </li>
      </ul>

      <div className="student-sidebar__user">
        <span className="student-sidebar__avatar" aria-hidden="true">
          {initials}
        </span>
        <div className="student-sidebar__who">
          <span className="student-sidebar__who-name">{shortName}</span>
          <span className="student-sidebar__who-meta">{me.email}</span>
        </div>
        <a href={`/${locale}/account`} className="btn btn--ghost btn--sm">{t("account")}</a>
        <LogoutButton locale={locale} />
      </div>
    </aside>
  );
}
