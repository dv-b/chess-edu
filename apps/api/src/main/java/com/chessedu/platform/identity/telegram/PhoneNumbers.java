package com.chessedu.platform.identity.telegram;

import java.util.regex.Pattern;

/**
 * E.164 normalisation shared by the bot (Telegram's contact share) and the
 * admin invitation form (a phone typed by a school admin). Strips spaces,
 * brackets and dashes, prepends '+' if missing, and returns null unless the
 * result matches the shape the DB CHECK constraints enforce.
 */
public final class PhoneNumbers {

    /** Same regex as the telegram_account / invitation CHECK constraints. */
    public static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,15}$");

    private PhoneNumbers() {}

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[\\s()\\-]", "");
        if (digits.isEmpty()) {
            return null;
        }
        if (!digits.startsWith("+")) {
            digits = "+" + digits;
        }
        return E164.matcher(digits).matches() ? digits : null;
    }
}
