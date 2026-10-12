package com.chessedu.platform.identity.repo;

import com.chessedu.platform.identity.model.TelegramAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TelegramAccountRepository extends JpaRepository<TelegramAccount, UUID> {

    /** Primary lookup — the Login Widget + bot both key on the stable Telegram id. */
    Optional<TelegramAccount> findByTelegramId(long telegramId);

    Optional<TelegramAccount> findByUserId(UUID userId);

    boolean existsByTelegramId(long telegramId);

    /** E.164 as stored (Telegram-verified at signup). */
    Optional<TelegramAccount> findByPhone(String phone);
}
