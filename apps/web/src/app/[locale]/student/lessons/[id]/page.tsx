import { redirect } from "next/navigation";
import { Link } from "@/i18n/routing";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { apiFetch } from "@/lib/api";
import { getAccessToken } from "@/lib/session";
import {
  StudentLessonDetailPanels,
  type StudentLessonDetail,
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

/**
 * Format an ISO timestamp into the meta-strip "20 Apr 18:00" form we use on
 * lesson detail headers. Asia/Tashkent wall-clock so the label reads the way
 * a student in Tashkent would expect regardless of their browser's local tz.
 * Mirrors the homework detail's {@code formatInstant}.
 */
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
 * Student-facing detail page for a single lesson. Mirrors the shape of the
 * homework twin ({@code /student/homework/[id]/page.tsx}) — same auth gate,
 * same sidebar chrome inherited from {@code layout.tsx}, same header rhythm
 * — but with lesson-specific meta (no due date, an optional session pill
 * instead) and a handoff to {@link StudentLessonDetailPanels} that renders
 * the four v1 block kinds.
 *
 * <p>404 hides existence: a lesson in a class the student isn't enrolled in,
 * or an archived one, returns the same "Lesson not found" copy as a genuinely
 * missing row. The backend is the source of truth here (Task #52), so we
 * don't attempt to disambiguate on the client.
 */
export default async function StudentLessonDetailPage({ params }: PageProps) {
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

  const detailRes = await apiFetch<StudentLessonDetail>(
    `/api/v1/me/lessons/${id}`,
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
            <Link
              href="/student/lessons"
              className="student-main__crumb-link"
            >
              {t("lessonDetail.backToList")}
            </Link>
          </p>
          <h1 className="student-main__title">
            {t("lessonDetail.notFoundTitle")}
          </h1>
          <p className="student-main__sub">
            {t("lessonDetail.notFoundBody")}
          </p>
        </div>
      </header>
    );
  }

  const detail = detailRes.data;
  const sessionLabel = detail.sessionStartsAt
    ? formatInstant(detail.sessionStartsAt, locale)
    : null;
  const createdLabel = formatInstant(detail.createdAt, locale);
  const markedLabel = detail.markedAt
    ? formatInstant(detail.markedAt, locale)
    : null;

  return (
    <>
      <header className="student-main__head student-main__head--detail">
        <div className="student-main__head-copy">
          <p className="student-main__crumb">
            <Link
              href="/student/lessons"
              className="student-main__crumb-link"
            >
              {t("lessonDetail.backToList")}
            </Link>
          </p>
          <h1 className="student-main__title">{detail.title}</h1>
          <p className="student-main__meta">
            {detail.className ? (
              <span className="student-main__meta-item">
                {detail.className}
              </span>
            ) : null}
            {sessionLabel ? (
              <span className="student-main__meta-item student-main__meta-item--due">
                {t("lessonDetail.sessionOn", { when: sessionLabel })}
              </span>
            ) : null}
            <span className="student-main__meta-item">
              {t("lessonDetail.assigned", { when: createdLabel })}
            </span>
            {markedLabel ? (
              <span className="student-main__meta-item">
                {t("lessonDetail.markedAt", { when: markedLabel })}
              </span>
            ) : null}
          </p>
        </div>
      </header>

      <StudentLessonDetailPanels detail={detail} locale={locale} />
    </>
  );
}
