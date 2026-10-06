package com.chessedu.platform.identity.repo;

import com.chessedu.platform.identity.model.TelegramAuthToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TelegramAuthTokenRepository extends JpaRepository<TelegramAuthToken, UUID> {

    Optional<TelegramAuthToken> findByTokenHash(String tokenHash);
}
