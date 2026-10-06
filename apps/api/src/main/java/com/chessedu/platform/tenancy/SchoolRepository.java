package com.chessedu.platform.tenancy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SchoolRepository extends JpaRepository<School, UUID> {

    Optional<School> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
