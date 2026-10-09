package com.chessedu.platform.identity.telegram;

import com.chessedu.platform.common.config.WebProperties;
import com.chessedu.platform.identity.model.TelegramAccount;
import com.chessedu.platform.identity.repo.TelegramAccountRepository;
import com.chessedu.platform.identity.telegram.dto.TgMessage;
import com.chessedu.platform.identity.telegram.dto.TgUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Bot-side half of repeat sign-in for already-linked accounts. Two entry
 * points, both only for users who completed signup earlier:
 *
 * <ul>
 *   <li>{@code /start login_<nonce>} — the browser's "Log in with Telegram"
 *       button opened this deeplink; confirm the pending login request so the
 *       browser's poll can issue the session. Works cross-device (desktop
 *       browser, phone Telegram).</li>
 *   <li>{@code /login} — typed directly in the bot: reply with a fresh
 *       {@code /auth/accept?ott=…} link, for the same-device phone case or when
 *       the user simply wants a link. Same single-use OTT as signup.</li>
 * </ul>
 *
 * <p>Bot-facing, so {@code @ConditionalOnProperty} like the rest of the worker.
 */
@Service
@ConditionalOnProperty(prefix = "chessedu.telegram", name = "enabled", havingValue = "true")
public class TelegramLoginBotHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramLoginBotHandler.class);

    static final String LOGIN_PREFIX = "login_";

    private final TelegramLoginRequestService loginRequests;
    private final TelegramAccountRepository accounts;
    private final TelegramAuthTokenService ottService;
    private final TelegramApiClient api;
    private final WebProperties webProps;

    public TelegramLoginBotHandler(TelegramLoginRequestService loginRequests,
                                   TelegramAccountRepository accounts,
                                   TelegramAuthTokenService ottService,
                                   TelegramApiClient api,
                                   WebProperties webProps) {
        this.loginRequests = loginRequests;
        this.accounts = accounts;
        this.ottService = ottService;
        this.api = api;
        this.webProps = webProps;
    }

    /** {@code /start login_<nonce>} — confirm a browser's pending login request. */
    @Transactional
    public void handleLoginStart(TgMessage msg, String nonce) {
        TgUser from = msg.from();
        long chatId = msg.chat().id();
        String locale = TelegramBotMessages.resolveLocale(from.languageCode());

        TelegramAccount account = accounts.findByTelegramId(from.id()).orElse(null);
        if (account == null) {
            api.sendMessage(chatId, TelegramBotMessages.loginNotLinked(locale));
            return;
        }
        touch(account, from);

        var outcome = loginRequests.complete(nonce, from.id(), account.getUserId());
        switch (outcome) {
            case COMPLETED -> api.sendMessage(chatId, TelegramBotMessages.loginConfirmed(locale));
            case ALREADY_COMPLETED -> api.sendMessage(chatId, TelegramBotMessages.loginAlreadyConfirmed(locale));
            case NOT_FOUND, EXPIRED -> api.sendMessage(chatId, TelegramBotMessages.loginRequestExpired(locale));
        }
        log.info("Telegram login start: telegramId={} outcome={}", from.id(), outcome);
    }

    /** {@code /login} — hand a linked user a fresh one-time accept link. */
    @Transactional
    public void handleLoginCommand(TgMessage msg) {
        TgUser from = msg.from();
        long chatId = msg.chat().id();
        String locale = TelegramBotMessages.resolveLocale(from.languageCode());

        TelegramAccount account = accounts.findByTelegramId(from.id()).orElse(null);
        if (account == null) {
            api.sendMessage(chatId, TelegramBotMessages.loginNotLinked(locale));
            return;
        }
        touch(account, from);

        String ott = ottService.mint(account.getUserId());
        String deeplink = webProps.baseUrl() + "/" + locale + "/auth/accept?ott=" + ott;
        api.sendMessage(chatId, TelegramBotMessages.loginLink(locale, deeplink));
        log.info("Telegram /login: minted OTT for user {}", account.getUserId());
    }

    /** Refresh the mutable profile snapshot + last-seen on every interaction. */
    private void touch(TelegramAccount account, TgUser from) {
        account.setUsername(from.username());
        account.setFirstName(from.firstName());
        account.setLastName(from.lastName());
        account.setLanguageCode(from.languageCode());
        account.setLastSeenAt(Instant.now());
        accounts.save(account);
    }
}
