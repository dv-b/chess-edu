package com.chessedu.platform.identity.repo;

import com.chessedu.platform.identity.model.TelegramLoginRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TelegramLoginRequestRepository extends JpaRepository<TelegramLoginRequest, UUID> {

    /** The bot's lookup — it only ever sees the nonce from the deeplink. */
    Optional<TelegramLoginRequest> findByNonceHash(String nonceHash);
}
