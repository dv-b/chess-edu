package com.chessedu.platform.identity.repo;

import com.chessedu.platform.identity.model.Credentials;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CredentialsRepository extends JpaRepository<Credentials, UUID> {

    Optional<Credentials> findByUserId(UUID userId);
}
