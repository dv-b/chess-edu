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
 * Bot-side repeat sign-in for already-linked accounts: {@code /login} typed
 * in the bot replies with a fresh {@code /auth/accept?ott=…} link (same
 * single-use OTT as signup) — the same-device phone shortcut. The web
 * "Log in with Telegram" button uses one-time codes instead
 * ({@link TelegramLoginCodeService}); the bot doesn't take part in that
 * beyond delivering the message.
 *
 * <p>Bot-facing, so {@code @ConditionalOnProperty} like the rest of the worker.
 */
@Service
@ConditionalOnProperty(prefix = "chessedu.telegram", name = "enabled", havingValue = "true")
public class TelegramLoginBotHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramLoginBotHandler.class);

    private final TelegramAccountRepository accounts;
    private final TelegramAuthTokenService ottService;
    private final TelegramApiClient api;
    private final WebProperties webProps;

    public TelegramLoginBotHandler(TelegramAccountRepository accounts,
                                   TelegramAuthTokenService ottService,
                                   TelegramApiClient api,
                                   WebProperties webProps) {
        this.accounts = accounts;
        this.ottService = ottService;
        this.api = api;
        this.webProps = webProps;
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
