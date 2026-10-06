package com.chessedu.platform.identity.telegram;

import com.chessedu.platform.identity.telegram.dto.TgResponse;
import com.chessedu.platform.identity.telegram.dto.TgUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin Spring {@link RestClient} wrapper over the handful of Telegram Bot API
 * methods the signup flow needs: {@code getUpdates} (long-poll) and
 * {@code sendMessage} (replies, optionally with a custom keyboard).
 *
 * <p>Only created when {@code chessedu.telegram.enabled=true} — it needs a bot
 * token to build its base URL, and there's nothing to talk to without one.
 *
 * <p>The read timeout is set to comfortably exceed the long-poll timeout so a
 * full-duration {@code getUpdates} hold doesn't trip a socket-read timeout.
 */
@Component
@ConditionalOnProperty(prefix = "chessedu.telegram", name = "enabled", havingValue = "true")
public class TelegramApiClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramApiClient.class);

    private final RestClient http;
    private final int pollTimeoutSeconds;

    public TelegramApiClient(TelegramProperties props) {
        this.pollTimeoutSeconds = props.pollTimeoutSeconds();
        this.http = RestClient.builder()
                .baseUrl(props.botApiPrefix())
                .requestFactory(requestFactory(props.pollTimeoutSeconds()))
                .build();
    }

    private static ClientHttpRequestFactory requestFactory(int pollTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        // Read timeout must outlast the long-poll hold, plus a buffer for the
        // round-trip after Telegram releases the connection.
        factory.setReadTimeout((int) Duration.ofSeconds(pollTimeoutSeconds + 15L).toMillis());
        return factory;
    }

    /**
     * Long-poll for new updates. Blocks up to {@code pollTimeoutSeconds} on the
     * Telegram side until an update arrives (or the timeout elapses, returning
     * an empty list). Passing {@code offset} confirms all updates below it, so
     * Telegram won't redeliver them.
     */
    public List<TgUpdate> getUpdates(long offset) {
        TgResponse<List<TgUpdate>> resp = http.get()
                .uri(uri -> uri.path("/getUpdates")
                        .queryParam("offset", offset)
                        .queryParam("timeout", pollTimeoutSeconds)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<TgResponse<List<TgUpdate>>>() {});
        if (resp == null || !resp.ok() || resp.result() == null) {
            log.warn("Telegram getUpdates returned not-ok: {}", resp == null ? "null" : resp.description());
            return List.of();
        }
        return resp.result();
    }

    /** Send a plain text message, removing any custom keyboard the user has up. */
    public void sendMessage(long chatId, String text) {
        send(chatId, text, Map.of("remove_keyboard", true));
    }

    /**
     * Send a message with a one-time "Share Contact" reply keyboard — the only
     * way to capture a Telegram-verified phone number (the button text is
     * cosmetic; {@code request_contact:true} is what matters).
     */
    public void sendContactRequest(long chatId, String text, String buttonLabel) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("text", buttonLabel);
        button.put("request_contact", true);
        Map<String, Object> keyboard = new LinkedHashMap<>();
        keyboard.put("keyboard", List.of(List.of(button)));
        keyboard.put("resize_keyboard", true);
        keyboard.put("one_time_keyboard", true);
        send(chatId, text, keyboard);
    }

    private void send(long chatId, String text, Object replyMarkup) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (replyMarkup != null) {
            body.put("reply_markup", replyMarkup);
        }
        try {
            http.post()
                    .uri("/sendMessage")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // A failed reply must not abort the update loop — log and move on.
            log.warn("Telegram sendMessage to chat {} failed: {}", chatId, e.getMessage());
        }
    }
}
