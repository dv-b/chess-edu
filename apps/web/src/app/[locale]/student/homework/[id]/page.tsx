import { redirect } from "next/navigation";
import { Link } from "@/i18n/routing";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import {
  StudentHomeworkDetailPanels,
  type StudentHomeworkDetail,
} from "./detail-panels";

type MeResponse = {
  userId: string;
  email: string;
  displayName: string;
  preferredLocale: string;
  role: string | null;
  platformRole: string | null;
};

type PageProps = {
  params: Promise<{ locale: string; id: string }>;
};

const DISPLAY_TZ = "Asia/Tashkent";

function formatInstant(iso: string, locale: string): string {
  const d = new Date(iso);
  const fmt = new Intl.DateTimeFormat(locale, {
    timeZone: DISPLAY_TZ,
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
  return fmt.format(d);
}

/**
 * Student-facing detail page for a single homework. The Kingside sidebar
 * chrome is provided by `layout.tsx` one level up, so this page only
 * emits main-column content: a crumb, the title + meta row, and the
 * interactive panels client island.
 */
export default async function StudentHomeworkDetailPage({ params }: PageProps) {
  const { locale, id } = await params;
  setRequestLocale(locale);

  const access = await getAccessToken();
  if (!access) redirect(`/${locale}/auth/login`);

  const meRes = await apiFetch<MeResponse>("/api/v1/auth/me", {
    accessToken: access,
  });
  if (!meRes.ok) {
    redirect(`/api/session/logout?next=/${locale}/auth/login`);
  }
  const me = meRes.data;
  if (me.role !== "STUDENT") {
    redirect(`/${locale}/admin`);
  }

  const detailRes = await apiFetch<StudentHomeworkDetail>(
    `/api/v1/me/homework/${id}`,
    { accessToken: access },
  );

  const t = await getTranslations("student");

  if (!detailRes.ok) {
    if (detailRes.error.status === 401 || detailRes.error.status === 403) {
      redirect(`/api/session/logout?next=/${locale}/auth/login`);
    }
    return (
      <header className="student-main__head student-main__head--detail">
        <div className="student-main__head-copy">
          <p className="student-main__crumb">
            <Link href="/student" className="student-main__crumb-link">
              {t("detail.backToList")}
            </Link>
          </p>
          <h1 className="student-main__title">{t("detail.notFoundTitle")}</h1>
          <p className="student-main__sub">{t("detail.notFoundBody")}</p>
        </div>
      </header>
    );
  }

  const detail = detailRes.data;
  const dueLabel = detail.dueAt ? formatInstant(detail.dueAt, locale) : null;
  const createdLabel = formatInstant(detail.createdAt, locale);

  return (
    <>
      <header className="student-main__head student-main__head--detail">
        <div className="student-main__head-copy">
          <p className="student-main__crumb">
            <Link href="/student" className="student-main__crumb-link">
              {t("detail.backToList")}
            </Link>
          </p>
          <h1 className="student-main__title">{detail.title}</h1>
          <p className="student-main__meta">
            {detail.className ? (
              <span className="student-main__meta-item">
                {detail.className}
              </span>
            ) : null}
            {dueLabel ? (
              <span className="student-main__meta-item student-main__meta-item--due">
                {t("row.dueOn", { when: dueLabel })}
              </span>
            ) : (
              <span className="student-main__meta-item">
                {t("row.noDue")}
              </span>
            )}
            <span className="student-main__meta-item">
              {t("row.assigned", { when: createdLabel })}
            </span>
          </p>
        </div>
      </header>

      <StudentHomeworkDetailPanels detail={detail} locale={locale} />
    </>
  );
}
