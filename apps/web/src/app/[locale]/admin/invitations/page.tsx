import { redirect } from "next/navigation";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import { Link } from "@/i18n/routing";
import { revokeInvitationAction } from "./actions";

type InvitationView = {
  id: string;
  schoolId: string | null;
  schoolName: string | null;
  role: string;
  email: string;
  displayName: string;
  invitedBy: string;
  issuedAt: string;
  expiresAt: string;
  acceptedAt: string | null;
  expired: boolean;
};

type PageProps = {
  params: Promise<{ locale: string }>;
};

/**
 * Admin list of invitations for the school the caller belongs to.
 * Platform admins hitting this page without a `schoolId` query param will
 * see an empty list (the backend returns [] by design — see
 * {@code AdminInvitationController.resolveSchool}); eventually we'll expose
 * a school picker for them.
 */
export default async function AdminInvitationsPage({ params }: PageProps) {
  const { locale } = await params;
  setRequestLocale(locale);

  const access = await getAccessToken();
  if (!access) redirect(`/${locale}/auth/login`);

  const result = await apiFetch<InvitationView[]>("/api/v1/admin/invitations", {
    accessToken: access,
  });

  if (!result.ok) {
    if (result.error.status === 401 || result.error.status === 403) {
      redirect(`/api/session/logout?next=/${locale}/auth/login`);
    }
    // Degrade gracefully — show the empty-state with an inline error banner.
  }

  const invitations = result.ok ? result.data : [];
  const t = await getTranslations("admin.invitations");
  const dateFmt = new Intl.DateTimeFormat(locale, { dateStyle: "medium" });

  return (
    <main className="dashboard-shell">
      <header className="dashboard-header">
        <div>
          <h1 className="dashboard-title">{t("title")}</h1>
          <p className="dashboard-subtitle">{t("subtitle")}</p>
        </div>
        <Link href="/admin/invitations/new" className="btn btn--primary">
          {t("newCta")}
        </Link>
      </header>

      {!result.ok ? (
        <div className="form-error" role="alert">
          {t("loadError")}
        </div>
      ) : null}

      {invitations.length === 0 ? (
        <section className="dashboard-note">
          <p>{t("empty")}</p>
        </section>
      ) : (
        <section className="table-card">
          <table className="data-table">
            <thead>
              <tr>
                <th>{t("table.email")}</th>
                <th>{t("table.name")}</th>
                <th>{t("table.role")}</th>
                <th>{t("table.issued")}</th>
                <th>{t("table.expires")}</th>
                <th>{t("table.status")}</th>
                <th aria-label={t("table.actions")}></th>
              </tr>
            </thead>
            <tbody>
              {invitations.map((inv) => {
                const status = statusOf(inv);
                return (
                  <tr key={inv.id}>
                    <td>{inv.email}</td>
                    <td>{inv.displayName}</td>
                    <td>{translateRole(t, inv.role)}</td>
                    <td>{dateFmt.format(new Date(inv.issuedAt))}</td>
                    <td>{dateFmt.format(new Date(inv.expiresAt))}</td>
                    <td>
                      <span className={`pill pill--${status}`}>{t(`status.${status}`)}</span>
                    </td>
                    <td className="data-table__actions">
                      {status === "pending" ? (
                        <form action={revokeInvitationAction}>
                          <input type="hidden" name="id" value={inv.id} />
                          <input type="hidden" name="locale" value={locale} />
                          <button className="btn btn--ghost btn--sm" type="submit">
                            {t("revoke")}
                          </button>
                        </form>
                      ) : null}
                    </td>
                  </tr>
                );
              })}
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

function statusOf(inv: InvitationView): "accepted" | "expired" | "pending" {
  if (inv.acceptedAt) return "accepted";
  if (inv.expired) return "expired";
  return "pending";
}

/**
 * Translate a role key, falling back to the raw string if the backend ever
 * returns a role we don't yet have a translation for (e.g. STUDENT or a
 * future role). Avoids crashing the list.
 */
function translateRole(t: (k: string) => string, role: string): string {
  try {
    return t(`role.${role}`);
  } catch {
    return role;
  }
}
