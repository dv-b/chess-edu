package com.chessedu.platform.identity.telegram;

import com.chessedu.platform.identity.telegram.dto.TgMessage;
import com.chessedu.platform.identity.telegram.dto.TgUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Long-poll worker that drains the Telegram update queue and dispatches each
 * message to {@link TelegramSignupService}. Only created when the bot is
 * enabled.
 *
 * <p>Each {@link #poll()} invocation does one {@code getUpdates} call (blocking
 * up to the configured long-poll timeout on the Telegram side) and processes the
 * returned batch. {@code @Scheduled(fixedDelay)} guarantees no overlap — the
 * next cycle starts only after the previous one returns — so a single in-memory
 * {@link #offset} (last confirmed update id + 1) is race-free.
 *
 * <p>Resilience: a failure processing one update logs and advances past it (so a
 * poison update can't wedge the queue forever); a failure on the whole cycle
 * (network blip) logs and backs off briefly before the scheduler retries.
 *
 * <p>Single-instance only for v1: the offset lives in memory, so running two
 * instances would double-process. Multi-instance would move the offset (and the
 * signup correlation map) into Redis, or switch to webhooks. Documented
 * scope-out.
 */
@Component
@ConditionalOnProperty(prefix = "chessedu.telegram", name = "enabled", havingValue = "true")
public class TelegramBotWorker {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotWorker.class);

    private final TelegramApiClient api;
    private final TelegramSignupService signup;
    private final AtomicLong offset = new AtomicLong(0);

    public TelegramBotWorker(TelegramApiClient api, TelegramSignupService signup) {
        this.api = api;
        this.signup = signup;
    }

    @Scheduled(fixedDelayString = "${chessedu.telegram.poll-delay-ms:1000}")
    public void poll() {
        try {
            List<TgUpdate> updates = api.getUpdates(offset.get());
            for (TgUpdate update : updates) {
                try {
                    dispatch(update);
                } catch (Exception e) {
                    log.error("Failed to process Telegram update {}: {}",
                            update.updateId(), e.getMessage(), e);
                } finally {
                    // Always advance — never reprocess a poison update.
                    offset.updateAndGet(cur -> Math.max(cur, update.updateId() + 1));
                }
            }
        } catch (Exception e) {
            log.warn("Telegram poll cycle failed: {}", e.getMessage());
            backoff();
        }
    }

    private void dispatch(TgUpdate update) {
        TgMessage msg = update.message();
        if (msg == null || msg.from() == null || msg.from().isBot() || msg.chat() == null) {
            return;
        }
        if (msg.contact() != null) {
            signup.handleContact(msg);
            return;
        }
        String text = msg.text();
        if (text == null) {
            return;
        }
        text = text.trim();
        if (text.startsWith("/start")) {
            String payload = text.length() > "/start".length()
                    ? text.substring("/start".length()).trim()
                    : null;
            signup.handleStart(msg, (payload == null || payload.isBlank()) ? null : payload);
        } else if (text.equals("/cancel")) {
            signup.handleCancel(msg);
        } else {
            signup.handleHelp(msg);
        }
    }

    private void backoff() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
