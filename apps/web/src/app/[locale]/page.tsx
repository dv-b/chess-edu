import { useTranslations } from "next-intl";
import { setRequestLocale } from "next-intl/server";
import { Link } from "@/i18n/routing";
import { TopBar } from "@/components/top-bar";

type PageProps = {
  params: Promise<{ locale: string }>;
};

export default async function LandingPage({ params }: PageProps) {
  const { locale } = await params;
  setRequestLocale(locale);

  return <Landing />;
}

function Landing() {
  const t = useTranslations("landing");
  const tCommon = useTranslations();

  return (
    <div className="landing">
      <TopBar />
      <main className="container landing__body">
        <section className="hero">
          <h1 className="hero__title">{t("hero.title")}</h1>
          <p className="hero__subtitle">{t("hero.subtitle")}</p>
          <div className="hero__cta">
            <Link className="btn btn--primary" href="/auth/login">
              {t("hero.ctaPrimary")}
            </Link>
            <a className="btn btn--ghost" href="mailto:hello@chess-edu.local">
              {t("hero.ctaSecondary")}
            </a>
          </div>
        </section>

        <hr className="landing__rule" />

        <section className="features" aria-labelledby="features-title">
          <h2 className="features__title" id="features-title">
            {t("features.title")}
          </h2>
          <div className="feature-grid">
            <article className="feature-card">
              <span className="feature-card__num" aria-hidden="true">01</span>
              <h3>{t("features.classes.title")}</h3>
              <p>{t("features.classes.body")}</p>
            </article>
            <article className="feature-card">
              <span className="feature-card__num" aria-hidden="true">02</span>
              <h3>{t("features.homework.title")}</h3>
              <p>{t("features.homework.body")}</p>
            </article>
            <article className="feature-card">
              <span className="feature-card__num" aria-hidden="true">03</span>
              <h3>{t("features.progress.title")}</h3>
              <p>{t("features.progress.body")}</p>
            </article>
          </div>
        </section>

        <footer className="footer">
          <span>
            {t("footer.legal", {
              year: new Date().getFullYear(),
              brand: tCommon("brand.name"),
            })}
          </span>
          <span className="footer__links">
            <a href="#">{t("footer.privacy")}</a>
            <a href="#">{t("footer.terms")}</a>
          </span>
        </footer>
      </main>
      <div className="landing__badge" aria-hidden="true">
        <span>N</span>
      </div>
    </div>
  );
}
