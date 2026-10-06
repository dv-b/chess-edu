import { redirect } from "next/navigation";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import { Link } from "@/i18n/routing";

type MemberView = {
  userId: string;
  email: string;
  displayName: string;
  role: "SCHOOL_ADMIN" | "TEACHER" | "PARENT" | "STUDENT" | string;
  preferredLocale: string;
  userStatus: "INVITED" | "ACTIVE" | "SUSPENDED" | "ARCHIVED" | string;
  membershipStatus: "ACTIVE" | "SUSPENDED" | "ARCHIVED" | string;
  joinedAt: string;
  lastLoginAt: string | null;
};

type PageProps = {
  params: Promise<{ locale: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

const KNOWN_ROLES = ["SCHOOL_ADMIN", "TEACHER", "PARENT"] as const;

/**
 * Read-only list of everyone currently attached to the caller's school.
 * Filters (role + free-text search) round-trip through the URL so the list
 * is shareable and refresh-safe, and all filtering happens on the backend.
 */
export default async function MembersPage({ params, searchParams }: PageProps) {
  const { locale } = await params;
  const sp = await searchParams;
  setRequestLocale(locale);

  const access = await getAccessToken();
  if (!access) redirect(`/${locale}/auth/login`);

  const role = typeof sp.role === "string" && (KNOWN_ROLES as readonly string[]).includes(sp.role)
    ? sp.role
    : "";
  const search = typeof sp.search === "string" ? sp.search.trim() : "";

  const qs = new URLSearchParams();
  if (role) qs.set("role", role);
  if (search) qs.set("search", search);
  const path = qs.toString()
    ? `/api/v1/admin/members?${qs.toString()}`
    : "/api/v1/admin/members";

  const result = await apiFetch<MemberView[]>(path, { accessToken: access });

  if (!result.ok && (result.error.status === 401 || result.error.status === 403)) {
    redirect(`/api/session/logout?next=/${locale}/auth/login`);
  }

  const members = result.ok ? result.data : [];
  const t = await getTranslations("admin.members");
  const dateFmt = new Intl.DateTimeFormat(locale, { dateStyle: "medium" });

  return (
    <main className="dashboard-shell">
      <header className="dashboard-header">
        <div>
          <h1 className="dashboard-title">{t("title")}</h1>
          <p className="dashboard-subtitle">{t("subtitle")}</p>
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
            maxLength={120}
          />
        </div>
        <div className="filter-bar__field">
          <label htmlFor="role">{t("filter.role")}</label>
          <select id="role" name="role" defaultValue={role}>
            <option value="">{t("filter.allRoles")}</option>
            {KNOWN_ROLES.map((r) => (
              <option key={r} value={r}>
                {translateRole(t, r)}
              </option>
            ))}
          </select>
        </div>
        <div className="filter-bar__actions">
          <button className="btn btn--primary" type="submit">{t("filter.apply")}</button>
          {(role || search) ? (
            <Link href="/admin/members" className="btn btn--ghost">{t("filter.clear")}</Link>
          ) : null}
        </div>
      </form>

      {!result.ok ? (
        <div className="form-error" role="alert">{t("loadError")}</div>
      ) : null}

      {members.length === 0 ? (
        <section className="dashboard-note">
          <p>{role || search ? t("emptyFiltered") : t("empty")}</p>
        </section>
      ) : (
        <section className="table-card">
          <table className="data-table">
            <thead>
              <tr>
                <th>{t("table.name")}</th>
                <th>{t("table.email")}</th>
                <th>{t("table.role")}</th>
                <th>{t("table.joined")}</th>
                <th>{t("table.lastLogin")}</th>
                <th>{t("table.status")}</th>
              </tr>
            </thead>
            <tbody>
              {members.map((m) => (
                <tr key={m.userId}>
                  <td>{m.displayName}</td>
                  <td>{m.email}</td>
                  <td>{translateRole(t, m.role)}</td>
                  <td>{dateFmt.format(new Date(m.joinedAt))}</td>
                  <td>{m.lastLoginAt ? dateFmt.format(new Date(m.lastLoginAt)) : "—"}</td>
                  <td>
                    <span className={`pill pill--${pillTone(m.userStatus)}`}>
                      {translateStatus(t, m.userStatus)}
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

function translateRole(t: (k: string) => string, role: string): string {
  try {
    return t(`role.${role}`);
  } catch {
    return role;
  }
}

function translateStatus(t: (k: string) => string, status: string): string {
  try {
    return t(`status.${status}`);
  } catch {
    return status;
  }
}

/**
 * Map user status → pill colour key. We reuse the three buckets from the
 * invitations UI: ACTIVE → accepted (green), SUSPENDED → pending (amber),
 * anything else → expired (muted grey).
 */
function pillTone(status: string): "accepted" | "pending" | "expired" {
  if (status === "ACTIVE") return "accepted";
  if (status === "SUSPENDED") return "pending";
  return "expired";
}
