import { redirect } from "next/navigation";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import { Link } from "@/i18n/routing";
import { revokeInvitationAction } from "./actions";
import { SchoolPicker, type SchoolOption } from "./school-picker";

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
  deliveryChannel?: "EMAIL" | "TELEGRAM";
  /** Bot-funnel state for TELEGRAM invites; absent (Jackson drops nulls) for EMAIL. */
  botState?: "PENDING" | "STARTED" | "AWAITING_CONTACT" | "COMPLETED" | "CANCELLED";
};

type PageProps = {
  params: Promise<{ locale: string }>;
  searchParams: Promise<{ schoolId?: string | string[] }>;
};

type MeResponse = { platformRole: string | null };

/**
 * Admin list of invitations for one school. School admins see their own;
 * platform admins (no membership) pick a tenant with the school picker,
 * which drives `?schoolId=` — defaulting to the first school so the page is
 * never empty for the wrong reason.
 */
export default async function AdminInvitationsPage({ params, searchParams }: PageProps) {
  const { locale } = await params;
  setRequestLocale(locale);

  const access = await getAccessToken();
  if (!access) redirect(`/${locale}/auth/login`);

  const me = await apiFetch<MeResponse>("/api/v1/auth/me", { accessToken: access });
  const isPlatformAdmin = me.ok && me.data.platformRole === "PLATFORM_ADMIN";

  let schools: SchoolOption[] = [];
  let schoolId: string | null = null;
  if (isPlatformAdmin) {
    const sp = await searchParams;
    const requested = Array.isArray(sp.schoolId) ? sp.schoolId[0] : sp.schoolId;
    const list = await apiFetch<SchoolOption[]>("/api/v1/admin/schools", { accessToken: access });
    schools = list.ok ? list.data : [];
    schoolId = schools.some((s) => s.id === requested) ? requested! : (schools[0]?.id ?? null);
  }

  const listPath = schoolId
    ? `/api/v1/admin/invitations?schoolId=${encodeURIComponent(schoolId)}`
    : "/api/v1/admin/invitations";
  const result = await apiFetch<InvitationView[]>(listPath, {
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
        <Link
          href={schoolId ? { pathname: "/admin/invitations/new", query: { schoolId } } : "/admin/invitations/new"}
          className="btn btn--primary"
        >
          {t("newCta")}
        </Link>
      </header>

      {isPlatformAdmin ? (
        schools.length > 0 ? (
          <SchoolPicker schools={schools} selected={schoolId} />
        ) : (
          <section className="dashboard-note">
            <p>{t("noSchools")}</p>
          </section>
        )
      ) : null}

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
                <th>{t("table.channel")}</th>
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
                    <td>{t(`channel.${inv.deliveryChannel ?? "EMAIL"}`)}</td>
                    <td>{dateFmt.format(new Date(inv.issuedAt))}</td>
                    <td>{dateFmt.format(new Date(inv.expiresAt))}</td>
                    <td>
                      <span className={`pill pill--${status}`}>{t(`status.${status}`)}</span>
                      {funnelOf(inv, status) ? (
                        <span className="pill-note">{t(`botState.${funnelOf(inv, status)}`)}</span>
                      ) : null}
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

/**
 * Which bot-funnel step to show under the status pill. Only meaningful while
 * a TELEGRAM invite is still pending — once accepted/expired the pill says
 * it all, except CANCELLED (the invitee backed out mid-flow), which is worth
 * surfacing so the admin knows to nudge them.
 */
function funnelOf(
  inv: InvitationView,
  status: "accepted" | "expired" | "pending",
): InvitationView["botState"] | null {
  if (inv.deliveryChannel !== "TELEGRAM" || inv.botState == null) return null;
  if (status === "pending" || inv.botState === "CANCELLED") return inv.botState;
  return null;
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
