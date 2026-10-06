package com.chessedu.platform.identity.repo;

import com.chessedu.platform.identity.member.dto.MemberView;
import com.chessedu.platform.identity.model.Membership;
import com.chessedu.platform.identity.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByUserId(UUID userId);

    List<Membership> findBySchoolIdAndRole(UUID schoolId, Role role);

    long countBySchoolId(UUID schoolId);

    /**
     * List all memberships for a school, projected together with the joined
     * {@code User} into a {@link MemberView}. Supports optional role filter
     * and a case-insensitive pre-built LIKE pattern over email / displayName.
     *
     * <p>We use a theta join ({@code Membership m, User u WHERE u.id = m.userId})
     * because there's no JPA relationship wired between the two — memberships
     * carry raw foreign keys by design so the aggregate stays small.
     *
     * <p>Ordering: by displayName, ascending, to give the UI a stable list.
     *
     * <p><b>Why the pattern is pre-built:</b> a null parameter wrapped by
     * {@code concat('%', :search, '%')} inside JPQL trips Postgres up —
     * without a type anchor, the bind defaults to bytea and
     * {@code lower(bytea)} throws. Binding a text parameter directly to
     * {@code like} keeps the type pinned even when the value is null.
     */
    @Query("""
        select new com.chessedu.platform.identity.member.dto.MemberView(
            u.id, u.email, u.displayName, m.role, u.preferredLocale,
            u.status, m.status, u.createdAt, u.lastLoginAt
        )
        from Membership m, com.chessedu.platform.identity.model.User u
        where u.id = m.userId
          and m.schoolId = :schoolId
          and (:role is null or m.role = :role)
          and (:pattern is null
               or lower(u.email)       like :pattern
               or lower(u.displayName) like :pattern)
        order by lower(u.displayName) asc
    """)
    List<MemberView> findMembers(@Param("schoolId") UUID schoolId,
                                 @Param("role") Role role,
                                 @Param("pattern") String pattern);
}
