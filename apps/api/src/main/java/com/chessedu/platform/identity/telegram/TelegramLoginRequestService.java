package com.chessedu.platform.identity.telegram;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.auth.AuthService;
import com.chessedu.platform.identity.auth.dto.TokenResponse;
import com.chessedu.platform.identity.model.TelegramLoginRequest;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.repo.TelegramAccountRepository;
import com.chessedu.platform.identity.repo.TelegramLoginRequestRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.identity.telegram.dto.TelegramLoginPollResponse;
import com.chessedu.platform.identity.telegram.dto.TelegramLoginRequestView;
import com.chessedu.platform.security.TokenHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The "Log in with Telegram" handshake for already-linked accounts (V013).
 * Replaces the Login Widget until a production domain exists, and will keep
 * working alongside it afterwards (desktop sign-in approved from the phone).
 *
 * <ul>
 *   <li>{@link #create} — browser side: mint a request (nonce for the bot,
 *       client secret for the browser), return the {@code t.me} deeplink.</li>
 *   <li>{@link #complete} — bot side: {@code /start login_<nonce>} from a
 *       linked Telegram account attaches that user to the request.</li>
 *   <li>{@link #poll} — browser side: once completed, and only with the
 *       matching client secret, issue the session and consume the request.</li>
 * </ul>
 *
 * <p>Unconditional (unlike the bot worker): {@code create} and {@code poll}
 * are web endpoints that must exist on every instance.
 */
@Service
public class TelegramLoginRequestService {

    private static final Logger log = LoggerFactory.getLogger(TelegramLoginRequestService.class);

    /** What the bot handler reports back so it can pick the right reply. */
    public enum CompleteOutcome { COMPLETED, NOT_FOUND, EXPIRED, ALREADY_COMPLETED }

    private final TelegramLoginRequestRepository requests;
    private final TelegramAccountRepository accounts;
    private final UserRepository users;
    private final AuthService auth;
    private final TokenHasher hasher;
    private final TelegramProperties props;

    public TelegramLoginRequestService(TelegramLoginRequestRepository requests,
                                       TelegramAccountRepository accounts,
                                       UserRepository users,
                                       AuthService auth,
                                       TokenHasher hasher,
                                       TelegramProperties props) {
        this.requests = requests;
        this.accounts = accounts;
        this.users = users;
        this.auth = auth;
        this.hasher = hasher;
        this.props = props;
    }

    @Transactional
    public TelegramLoginRequestView create() {
        if (props.botUsername() == null || props.botUsername().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Telegram bot is not configured (chessedu.telegram.bot-username)");
        }
        Instant now = Instant.now();
        String nonce = hasher.mint();
        String clientSecret = hasher.mint();

        TelegramLoginRequest req = new TelegramLoginRequest();
        req.setId(UUID.randomUUID());
        req.setNonceHash(hasher.hash(nonce));
        req.setClientSecretHash(hasher.hash(clientSecret));
        req.setCreatedAt(now);
        req.setExpiresAt(now.plus(props.loginRequestTtl()));
        requests.save(req);

        String deeplink = "https://t.me/" + props.botUsername() + "?start=login_" + nonce;
        return new TelegramLoginRequestView(req.getId(), clientSecret, deeplink, req.getExpiresAt());
    }

    /**
     * Bot side. The caller has already resolved the Telegram account to a
     * {@code userId} (and replied "not registered" if it couldn't).
     */
    @Transactional
    public CompleteOutcome complete(String nonce, long telegramId, UUID userId) {
        TelegramLoginRequest req = requests.findByNonceHash(hasher.hash(nonce)).orElse(null);
        if (req == null) {
            return CompleteOutcome.NOT_FOUND;
        }
        Instant now = Instant.now();
        if (req.isExpired(now)) {
            return CompleteOutcome.EXPIRED;
        }
        if (req.isCompleted()) {
            return CompleteOutcome.ALREADY_COMPLETED;
        }
        req.setUserId(userId);
        req.setTelegramId(telegramId);
        req.setCompletedAt(now);
        requests.save(req);
        log.info("Telegram login request {} completed by user {}", req.getId(), userId);
        return CompleteOutcome.COMPLETED;
    }

    /**
     * Browser side. Errors are HTTP statuses the web layer maps to "try
     * again": 404 unknown id, 401 wrong secret, 410 expired or already used.
     * Issuing the session and stamping {@code consumedAt} happen in one
     * transaction, so a failed issuance (user suspended meanwhile) leaves the
     * request unconsumed but — being completed — harmless.
     */
    @Transactional
    public TelegramLoginPollResponse poll(UUID id, String clientSecret, String ip, String userAgent) {
        TelegramLoginRequest req = requests.findById(id)
                .orElseThrow(() -> AppException.notFound("Login request"));
        if (!hasher.hash(clientSecret).equals(req.getClientSecretHash())) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Invalid login request");
        }
        Instant now = Instant.now();
        if (req.isConsumed()) {
            throw new AppException(ErrorCode.GONE, "Login request already used");
        }
        if (req.isExpired(now)) {
            throw new AppException(ErrorCode.GONE, "Login request expired");
        }
        if (!req.isCompleted()) {
            return TelegramLoginPollResponse.pending();
        }

        User user = users.findById(req.getUserId())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED, "Invalid login request"));

        accounts.findByUserId(user.getId()).ifPresent(account -> {
            account.setLastSeenAt(now);
            accounts.save(account);
        });

        TokenResponse tokens = auth.issueSessionFor(user, ip, userAgent);
        req.setConsumedAt(now);
        requests.save(req);
        log.info("Telegram login request {} consumed: user={}", req.getId(), user.getId());
        return TelegramLoginPollResponse.completed(tokens);
    }
}
