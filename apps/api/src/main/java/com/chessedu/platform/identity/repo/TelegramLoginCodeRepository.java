package com.chessedu.platform.identity.repo;

import com.chessedu.platform.identity.model.TelegramLoginCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TelegramLoginCodeRepository extends JpaRepository<TelegramLoginCode, UUID> {

    /** Newest row for the user — used for the resend cooldown. */
    Optional<TelegramLoginCode> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);
}
