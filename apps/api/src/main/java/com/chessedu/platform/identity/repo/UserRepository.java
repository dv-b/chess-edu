package com.chessedu.platform.identity.repo;

import com.chessedu.platform.identity.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Usernames are stored lowercase; callers normalise before lookup. */
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
}
