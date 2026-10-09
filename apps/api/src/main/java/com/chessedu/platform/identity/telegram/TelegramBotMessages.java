package com.chessedu.platform.identity.telegram;

/**
 * Bot-reply copy in the three product locales (uz default / ru / en), keyed off
 * the Telegram user's {@code language_code}. Kept as a small static helper
 * rather than wiring MessageSource bundles for the bot — the conversation has a
 * fixed, tiny set of strings and the staff audience is uz/ru/en, same as the
 * web app.
 *
 * <p>If bot copy grows, promote this to a proper {@code messages_*.properties}
 * bundle behind {@code MessageSource} (the convention the email templates use).
 */
final class TelegramBotMessages {

    private TelegramBotMessages() {}

    /** Telegram {@code language_code} → one of the three product locales. */
    static String resolveLocale(String languageCode) {
        if (languageCode == null || languageCode.length() < 2) {
            return "uz";
        }
        String two = languageCode.substring(0, 2).toLowerCase();
        return switch (two) {
            case "ru" -> "ru";
            case "en" -> "en";
            default -> "uz";
        };
    }

    static String startInvalidToken(String locale) {
        return switch (locale) {
            case "ru" -> "Не удалось найти приглашение. Откройте ссылку-приглашение, которую прислал администратор вашей школы.";
            case "en" -> "I couldn't find that invitation. Please open the invite link your school admin sent you.";
            default -> "Taklifnoma topilmadi. Maktab administratoringiz yuborgan taklif havolasini oching.";
        };
    }

    static String startNotTelegram(String locale) {
        return switch (locale) {
            case "ru" -> "Это приглашение отправлено по электронной почте. Завершите регистрацию по ссылке из письма.";
            case "en" -> "This invitation was sent by email. Please finish signing up via the link in your email.";
            default -> "Bu taklifnoma e-pochta orqali yuborilgan. Iltimos, e-pochtangizdagi havola orqali ro‘yxatdan o‘ting.";
        };
    }

    static String startAlreadyAccepted(String locale) {
        return switch (locale) {
            case "ru" -> "Это приглашение уже использовано. Войдите на сайте.";
            case "en" -> "This invitation has already been used. Please sign in on the website.";
            default -> "Bu taklifnoma allaqachon ishlatilgan. Iltimos, saytda tizimga kiring.";
        };
    }

    static String startExpired(String locale) {
        return switch (locale) {
            case "ru" -> "Срок действия приглашения истёк. Попросите администратора прислать новое.";
            case "en" -> "This invitation has expired. Ask your admin to send a new one.";
            default -> "Taklifnoma muddati tugagan. Administratordan yangisini so‘rang.";
        };
    }

    static String alreadyLinked(String locale, String baseUrl) {
        return switch (locale) {
            case "ru" -> "Этот аккаунт Telegram уже привязан. Отправьте /login, чтобы получить ссылку для входа, или нажмите «Войти через Telegram» на сайте: " + baseUrl;
            case "en" -> "This Telegram account is already linked. Send /login to get a sign-in link, or press \"Log in with Telegram\" on the website: " + baseUrl;
            default -> "Bu Telegram hisobi allaqachon bog‘langan. Kirish havolasini olish uchun /login yuboring yoki saytda «Telegram orqali kirish» tugmasini bosing: " + baseUrl;
        };
    }

    // ---- repeat sign-in (/start login_<nonce> and /login) -------------------

    static String loginNotLinked(String locale) {
        return switch (locale) {
            case "ru" -> "Этот аккаунт Telegram ещё не зарегистрирован. Попросите администратора школы прислать приглашение.";
            case "en" -> "This Telegram account isn't registered yet. Ask your school admin for an invitation.";
            default -> "Bu Telegram hisobi hali ro‘yxatdan o‘tmagan. Maktab administratoridan taklifnoma so‘rang.";
        };
    }

    static String loginConfirmed(String locale) {
        return switch (locale) {
            case "ru" -> "Вход подтверждён ✅ Вернитесь в браузер — вы уже в системе.";
            case "en" -> "Sign-in confirmed ✅ Go back to your browser — you're in.";
            default -> "Kirish tasdiqlandi ✅ Brauzerga qayting — siz tizimdasiz.";
        };
    }

    static String loginAlreadyConfirmed(String locale) {
        return switch (locale) {
            case "ru" -> "Этот запрос на вход уже подтверждён. Если вы ещё не в системе, нажмите «Войти через Telegram» на сайте ещё раз.";
            case "en" -> "That sign-in request was already confirmed. If you're not in yet, press \"Log in with Telegram\" on the website again.";
            default -> "Bu kirish so‘rovi allaqachon tasdiqlangan. Hali tizimga kirmagan bo‘lsangiz, saytda «Telegram orqali kirish» tugmasini qayta bosing.";
        };
    }

    static String loginRequestExpired(String locale) {
        return switch (locale) {
            case "ru" -> "Эта ссылка для входа устарела. Обновите страницу входа и нажмите «Войти через Telegram» ещё раз — или отправьте /login, чтобы получить ссылку прямо здесь.";
            case "en" -> "That sign-in link has expired. Reload the login page and press \"Log in with Telegram\" again — or send /login to get a link right here.";
            default -> "Bu kirish havolasi eskirgan. Kirish sahifasini yangilab, «Telegram orqali kirish» tugmasini qayta bosing — yoki shu yerda havola olish uchun /login yuboring.";
        };
    }

    static String loginLink(String locale, String deeplink) {
        return switch (locale) {
            case "ru" -> "Нажмите, чтобы войти (ссылка одноразовая, действует 10 минут):\n" + deeplink;
            case "en" -> "Tap to sign in (one-time link, valid for 10 minutes):\n" + deeplink;
            default -> "Kirish uchun bosing (bir martalik havola, 10 daqiqa amal qiladi):\n" + deeplink;
        };
    }

    static String askContact(String locale, String displayName) {
        return switch (locale) {
            case "ru" -> "Здравствуйте, " + displayName + "! Чтобы завершить регистрацию, нажмите кнопку ниже и поделитесь номером телефона.";
            case "en" -> "Welcome, " + displayName + "! To finish signing up, tap the button below to share your phone number.";
            default -> "Xush kelibsiz, " + displayName + "! Ro‘yxatdan o‘tishni yakunlash uchun quyidagi tugmani bosib telefon raqamingizni ulashing.";
        };
    }

    static String shareContactButton(String locale) {
        return switch (locale) {
            case "ru" -> "📱 Поделиться номером";
            case "en" -> "📱 Share my phone number";
            default -> "📱 Telefon raqamni ulashish";
        };
    }

    static String contactWithoutStart(String locale) {
        return switch (locale) {
            case "ru" -> "Сначала откройте ссылку-приглашение, чтобы начать регистрацию.";
            case "en" -> "Please start with your invite link first.";
            default -> "Avval taklif havolasini ochib, ro‘yxatdan o‘tishni boshlang.";
        };
    }

    static String contactNotOwn(String locale) {
        return switch (locale) {
            case "ru" -> "Пожалуйста, поделитесь своим номером, а не чужим.";
            case "en" -> "Please share your own phone number, not someone else's.";
            default -> "Iltimos, boshqa birovning emas, o‘zingizning raqamingizni ulashing.";
        };
    }

    static String badPhone(String locale) {
        return switch (locale) {
            case "ru" -> "Не удалось распознать номер телефона. Обратитесь к администратору.";
            case "en" -> "I couldn't read that phone number. Please contact your admin.";
            default -> "Telefon raqamini o‘qib bo‘lmadi. Administrator bilan bog‘laning.";
        };
    }

    static String emailConflict(String locale) {
        return switch (locale) {
            case "ru" -> "Учётная запись с этой почтой уже существует. Обратитесь к администратору.";
            case "en" -> "An account with this email already exists. Please contact your admin.";
            default -> "Bu e-pochta bilan hisob allaqachon mavjud. Administrator bilan bog‘laning.";
        };
    }

    static String signupComplete(String locale, String deeplink) {
        return switch (locale) {
            case "ru" -> "Готово! Нажмите, чтобы завершить вход:\n" + deeplink;
            case "en" -> "You're all set! Tap to finish signing in:\n" + deeplink;
            default -> "Tayyor! Tizimga kirishni yakunlash uchun bosing:\n" + deeplink;
        };
    }

    static String cancelled(String locale) {
        return switch (locale) {
            case "ru" -> "Регистрация отменена. Откройте ссылку-приглашение снова, чтобы начать заново.";
            case "en" -> "Sign-up cancelled. Open your invite link again to start over.";
            default -> "Ro‘yxatdan o‘tish bekor qilindi. Qaytadan boshlash uchun taklif havolasini qayta oching.";
        };
    }

    static String nothingToCancel(String locale) {
        return switch (locale) {
            case "ru" -> "Нечего отменять.";
            case "en" -> "Nothing to cancel.";
            default -> "Bekor qiladigan narsa yo‘q.";
        };
    }

    static String help(String locale) {
        return switch (locale) {
            case "ru" -> "Новый пользователь? Откройте ссылку-приглашение от администратора. Уже зарегистрированы? Отправьте /login, чтобы получить ссылку для входа. Отменить регистрацию — /cancel.";
            case "en" -> "New here? Open the invite link from your admin. Already registered? Send /login to get a sign-in link. Send /cancel to abort a sign-up.";
            default -> "Yangi foydalanuvchimisiz? Administratordan olgan taklif havolasini oching. Allaqachon ro‘yxatdan o‘tganmisiz? Kirish havolasi uchun /login yuboring. Ro‘yxatdan o‘tishni bekor qilish — /cancel.";
        };
    }
}
