"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";

type Theme = "light" | "dark";

/**
 * Theme toggle. Persists user choice in `localStorage` and mirrors it onto
 * the `data-theme` attribute on `<html>` so the design tokens in
 * `tokens.css` can reskin the whole tree.
 *
 * <p>The pre-hydration script in the root layout already sets the attribute
 * before React mounts, so this component just has to read it back and stay
 * in sync. Without that script we'd get a flash of the wrong theme.
 */
export function ThemeToggle() {
  const t = useTranslations("theme");
  // Render a neutral state on the server; we hydrate to the real value in
  // useEffect so the server- and client-rendered markup match.
  const [theme, setTheme] = useState<Theme | null>(null);

  useEffect(() => {
    const attr = document.documentElement.getAttribute("data-theme");
    if (attr === "light" || attr === "dark") {
      setTheme(attr);
      return;
    }
    // No stored preference — fall back to the system preference.
    const prefersDark =
      typeof window.matchMedia === "function" &&
      window.matchMedia("(prefers-color-scheme: dark)").matches;
    setTheme(prefersDark ? "dark" : "light");
  }, []);

  function apply(next: Theme) {
    setTheme(next);
    document.documentElement.setAttribute("data-theme", next);
    try {
      window.localStorage.setItem("ks-theme", next);
    } catch {
      // storage can be disabled (private mode, etc.) — the toggle still
      // works for the current tab, we just won't remember across reloads.
    }
  }

  return (
    <div
      className="theme-switch"
      role="group"
      aria-label={t("label")}
      data-hydrated={theme !== null ? "true" : undefined}
    >
      <button
        type="button"
        aria-pressed={theme === "light"}
        aria-label={t("light")}
        onClick={() => apply("light")}
        className="theme-switch__btn"
      >
        <SunIcon />
        <span className="theme-switch__label">{t("light")}</span>
      </button>
      <button
        type="button"
        aria-pressed={theme === "dark"}
        aria-label={t("dark")}
        onClick={() => apply("dark")}
        className="theme-switch__btn"
      >
        <MoonIcon />
        <span className="theme-switch__label">{t("dark")}</span>
      </button>
    </div>
  );
}

function SunIcon() {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
    </svg>
  );
}

function MoonIcon() {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79Z" />
    </svg>
  );
}
