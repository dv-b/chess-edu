import { redirect } from "next/navigation";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import { Link } from "@/i18n/routing";

type ClassSummary = {
  id: string;
  name: string;
  level: "BEGINNER" | "INTERMEDIATE" | "ADVANCED" | null;
  status: "ACTIVE" | "ARCHIVED";
  teacherCount: number;
  studentCount: number;
  createdAt: string;
};

type PageProps = {
  params: Promise<{ locale: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

const KNOWN_STATUSES = ["ACTIVE", "ARCHIVED"] as const;

/**
 * Admin list of classes in the caller's school. Filters (status + search)
 * round-trip through the URL like the members page, and the whole server
 * component re-runs on each submit.
 */
export default async function ClassesPage({ params, searchParams }: PageProps) {
  const { locale } = await params;
  const sp = await searchParams;
  setRequestLocale(locale);

  const access = await getAccessToken();
  if (!access) redirect(`/${locale}/auth/login`);

  const status =
    typeof sp.status === "string" &&
    (KNOWN_STATUSES as readonly string[]).includes(sp.status)
      ? sp.status
      : "";
  const search = typeof sp.search === "string" ? sp.search.trim() : "";

  const qs = new URLSearchParams();
  if (status) qs.set("status", status);
  if (search) qs.set("search", search);
  const path = qs.toString()
    ? `/api/v1/admin/classes?${qs.toString()}`
    : "/api/v1/admin/classes";

  const result = await apiFetch<ClassSummary[]>(path, { accessToken: access });

  if (!result.ok && (result.error.status === 401 || result.error.status === 403)) {
    redirect(`/api/session/logout?next=/${locale}/auth/login`);
  }

  const classes = result.ok ? result.data : [];
  const t = await getTranslations("admin.classes");
  const dateFmt = new Intl.DateTimeFormat(locale, { dateStyle: "medium" });

  return (
    <main className="dashboard-shell">
      <header className="dashboard-header">
        <div>
          <h1 className="dashboard-title">{t("title")}</h1>
          <p className="dashboard-subtitle">{t("subtitle")}</p>
        </div>
        <div className="dashboard-header__actions">
          <Link href="/admin/classes/new" className="btn btn--primary">
            {t("newCta")}
          </Link>
        </div>
      </header>

      <form className="filter-bar" method="get">
        <div className="filter-bar__field">
          <label htmlFor="search">{t("filter.search")}</label>
          <input
            id="search"
            name="search"
            type="search"
            defaultValue={search}
            placeholder={t("filter.searchPlaceholder")}
            autoComplete="off"
            maxLength={160}
          />
        </div>
        <div className="filter-bar__field">
          <label htmlFor="status">{t("filter.status")}</label>
          <select id="status" name="status" defaultValue={status}>
            <option value="">{t("filter.allStatuses")}</option>
            {KNOWN_STATUSES.map((s) => (
              <option key={s} value={s}>
                {translateStatus(t, s)}
              </option>
            ))}
          </select>
        </div>
        <div className="filter-bar__actions">
          <button className="btn btn--primary" type="submit">
            {t("filter.apply")}
          </button>
          {status || search ? (
            <Link href="/admin/classes" className="btn btn--ghost">
              {t("filter.clear")}
            </Link>
          ) : null}
        </div>
      </form>

      {!result.ok ? (
        <div className="form-error" role="alert">
          {t("loadError")}
        </div>
      ) : null}

      {classes.length === 0 ? (
        <section className="dashboard-note">
          <p>{status || search ? t("emptyFiltered") : t("empty")}</p>
        </section>
      ) : (
        <section className="table-card">
          <table className="data-table">
            <thead>
              <tr>
                <th>{t("table.name")}</th>
                <th>{t("table.level")}</th>
                <th>{t("table.teachers")}</th>
                <th>{t("table.students")}</th>
                <th>{t("table.created")}</th>
                <th>{t("table.status")}</th>
              </tr>
            </thead>
            <tbody>
              {classes.map((c) => (
                <tr key={c.id}>
                  <td>
                    <Link href={`/admin/classes/${c.id}`}>{c.name}</Link>
                  </td>
                  <td>{c.level ? translateLevel(t, c.level) : "—"}</td>
                  <td>{c.teacherCount}</td>
                  <td>{c.studentCount}</td>
                  <td>{dateFmt.format(new Date(c.createdAt))}</td>
                  <td>
                    <span className={`pill pill--${c.status === "ACTIVE" ? "accepted" : "expired"}`}>
                      {translateStatus(t, c.status)}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      <p className="admin-back">
        <Link href="/admin">{t("backToDashboard")}</Link>
      </p>
    </main>
  );
}

function translateStatus(t: (k: string) => string, status: string): string {
  try {
    return t(`status.${status}`);
  } catch {
    return status;
  }
}

function translateLevel(t: (k: string) => string, level: string): string {
  try {
    return t(`level.${level}`);
  } catch {
    return level;
  }
}
