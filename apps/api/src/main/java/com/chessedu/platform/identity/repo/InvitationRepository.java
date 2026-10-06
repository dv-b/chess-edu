package com.chessedu.platform.identity.repo;

import com.chessedu.platform.identity.model.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    List<Invitation> findBySchoolIdAndAcceptedAtIsNull(UUID schoolId);

    /**
     * Pending invitations (never accepted) for a school — ordered newest-first
     * so the admin UI can render a quick audit list.
     */
    List<Invitation> findBySchoolIdOrderByIssuedAtDesc(UUID schoolId);

    /**
     * Case-insensitive search for a pending (unaccepted) invitation within a
     * school. Used to prevent duplicates when the same email is invited twice.
     */
    @Query("select i from Invitation i "
            + "where lower(i.email) = lower(:email) "
            + "and i.schoolId = :schoolId "
            + "and i.acceptedAt is null")
    Optional<Invitation> findPendingByEmailAndSchoolId(@Param("email") String email,
                                                      @Param("schoolId") UUID schoolId);

    /**
     * Pending PLATFORM_ADMIN invitation by email (no school).
     */
    @Query("select i from Invitation i "
            + "where lower(i.email) = lower(:email) "
            + "and i.schoolId is null "
            + "and i.acceptedAt is null")
    Optional<Invitation> findPendingPlatformByEmail(@Param("email") String email);
}
