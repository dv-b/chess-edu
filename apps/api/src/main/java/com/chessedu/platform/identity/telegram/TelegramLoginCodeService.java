package com.chessedu.platform.identity.telegram;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.auth.AuthService;
import com.chessedu.platform.identity.auth.dto.TokenResponse;
import com.chessedu.platform.identity.model.TelegramAccount;
import com.chessedu.platform.identity.model.TelegramLoginCode;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.repo.TelegramAccountRepository;
import com.chessedu.platform.identity.repo.TelegramLoginCodeRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.identity.telegram.dto.TelegramLoginCodeView;
import com.chessedu.platform.security.TokenHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * "Log in with Telegram" by one-time code (V015): the user says who they
 * are, the bot DMs a 6-digit code to their linked chat, they type it in.
 *
 * <ul>
 *   <li>{@link #request} — resolve the identifier (username / email / E.164
 *       phone) to a Telegram-linked user, mint + send a code. Answers with
 *       an id <em>whether or not</em> anything matched, so the endpoint
 *       can't be used to enumerate accounts.</li>
 *   <li>{@link #verify} — check the code against the hash, count wrong
 *       attempts, issue a session once.</li>
 * </ul>
 *
 * <p>Unconditional service, but sending needs the bot's HTTP client, which
 * only exists when {@code chessedu.telegram.enabled=true}. Without it the
 * request fails fast with a 400 the web layer shows as "unavailable".
 */
@Service
public class TelegramLoginCodeService {

    private static final Logger log = LoggerFactory.getLogger(TelegramLoginCodeService.class);

    /** A fresh request inside this window re-sends nothing and returns the pending row. */
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(45);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TelegramLoginCodeRepository codes;
    private final TelegramAccountRepository accounts;
    private final UserRepository users;
    private final AuthService auth;
    private final TokenHasher hasher;
    private final TelegramProperties props;
    private final ObjectProvider<TelegramApiClient> apiProvider;

    public TelegramLoginCodeService(TelegramLoginCodeRepository codes,
                                    TelegramAccountRepository accounts,
                                    UserRepository users,
                                    AuthService auth,
                                    TokenHasher hasher,
                                    TelegramProperties props,
                                    ObjectProvider<TelegramApiClient> apiProvider) {
        this.codes = codes;
        this.accounts = accounts;
        this.users = users;
        this.auth = auth;
        this.hasher = hasher;
        this.props = props;
        this.apiProvider = apiProvider;
    }

    @Transactional
    public TelegramLoginCodeView request(String identifier) {
        TelegramApiClient api = apiProvider.getIfAvailable();
        if (api == null) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Telegram sign-in is not available (bot disabled on this instance)");
        }
        Instant now = Instant.now();
        Instant expires = now.plus(props.loginCodeTtl());

        Optional<TelegramAccount> account = resolveAccount(identifier);
        if (account.isEmpty() || account.get().getChatId() == null) {
            // Decoy: a real row with no user, so request AND verify behave
            // exactly like a hit (TTL, attempts, 401 / 410) — it just never
            // verifies. Nothing is sent anywhere.
            TelegramLoginCode decoy = new TelegramLoginCode();
            decoy.setId(UUID.randomUUID());
            decoy.setUserId(null);
            decoy.setCodeHash(hasher.hash(hasher.mint()));
            decoy.setAttempts(0);
            decoy.setCreatedAt(now);
            decoy.setExpiresAt(expires);
            codes.save(decoy);
            log.info("Telegram login code requested for unmatched identifier (decoy {})", decoy.getId());
            return new TelegramLoginCodeView(decoy.getId(), expires);
        }
        TelegramAccount acc = account.get();

        // Cooldown: a pending, usable code issued moments ago is returned as-is
        // (same id) instead of DMing the user again.
        Optional<TelegramLoginCode> pending = codes.findFirstByUserIdOrderByCreatedAtDesc(acc.getUserId())
                .filter(c -> c.isUsable(now) && c.getCreatedAt().plus(RESEND_COOLDOWN).isAfter(now));
        if (pending.isPresent()) {
            return new TelegramLoginCodeView(pending.get().getId(), pending.get().getExpiresAt());
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        TelegramLoginCode row = new TelegramLoginCode();
        row.setId(UUID.randomUUID());
        row.setUserId(acc.getUserId());
        row.setCodeHash(hasher.hash(code));
        row.setAttempts(0);
        row.setCreatedAt(now);
        row.setExpiresAt(expires);
        codes.save(row);

        String locale = TelegramBotMessages.resolveLocale(acc.getLanguageCode());
        api.sendMessage(acc.getChatId(), TelegramBotMessages.loginCode(locale, code));
        log.info("Telegram login code sent: user={} request={}", acc.getUserId(), row.getId());
        return new TelegramLoginCodeView(row.getId(), expires);
    }

    /**
     * 404 unknown id, 410 expired / consumed / too many wrong attempts,
     * 401 wrong code (attempt counted). Session issuance + consumption in
     * one transaction.
     */
    @Transactional(noRollbackFor = AppException.class)
    public TokenResponse verify(UUID id, String rawCode, String ip, String userAgent) {
        TelegramLoginCode row = codes.findById(id)
                .orElseThrow(() -> AppException.notFound("Login code"));
        Instant now = Instant.now();
        if (!row.isUsable(now)) {
            throw new AppException(ErrorCode.GONE, "Login code is no longer valid");
        }

        String code = rawCode == null ? "" : rawCode.replaceAll("\\s+", "");
        // A decoy row (no user) is always "wrong code" — see V016.
        if (row.getUserId() == null || !hasher.hash(code).equals(row.getCodeHash())) {
            // Counted even though we throw — see noRollbackFor.
            row.setAttempts(row.getAttempts() + 1);
            codes.save(row);
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Wrong code");
        }

        User user = users.findById(row.getUserId())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED, "Invalid login code"));
        accounts.findByUserId(user.getId()).ifPresent(a -> {
            a.setLastSeenAt(now);
            accounts.save(a);
        });

        TokenResponse tokens = auth.issueSessionFor(user, ip, userAgent);
        row.setConsumedAt(now);
        codes.save(row);
        log.info("Telegram login code verified: user={} request={}", user.getId(), row.getId());
        return tokens;
    }

    /** Username, email (has '@') or phone (starts with '+' or is all digits) → linked account. */
    private Optional<TelegramAccount> resolveAccount(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        String s = identifier.trim();
        // Phone: optional '+', then digits with any spaces / dashes / brackets
        // the user may have typed ("998 90 111-22-33"). Stored as E.164.
        String compact = s.replaceAll("[\\s()\\-]", "");
        if (compact.matches("\\+?\\d{7,16}")) {
            return accounts.findByPhone(compact.startsWith("+") ? compact : "+" + compact);
        }
        Optional<User> user = s.contains("@")
                ? users.findByEmail(AuthService.normalizeEmail(s))
                : users.findByUsername(AuthService.normalizeUsername(s));
        return user.flatMap(u -> accounts.findByUserId(u.getId()));
    }
}
