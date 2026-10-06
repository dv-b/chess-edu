import { redirect } from "next/navigation";
import { setRequestLocale } from "next-intl/server";
import { getAccessToken } from "@/lib/session";
import { CreateClassForm } from "./create-form";

type PageProps = {
  params: Promise<{ locale: string }>;
};

export default async function NewClassPage({ params }: PageProps) {
  const { locale } = await params;
  setRequestLocale(locale);

  const access = await getAccessToken();
  if (!access) redirect(`/${locale}/auth/login`);

  return (
    <main className="auth-shell">
      <CreateClassForm locale={locale} />
    </main>
  );
}
