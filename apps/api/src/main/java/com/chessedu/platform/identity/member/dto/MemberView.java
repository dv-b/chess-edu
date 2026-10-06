package com.chessedu.platform.identity.member.dto;

import com.chessedu.platform.identity.model.MembershipStatus;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.model.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-model projected directly from a {@code Membership m JOIN User u} query.
 *
 * <p>The ordering of the constructor parameters mirrors the JPQL {@code new}
 * expression in {@link com.chessedu.platform.identity.repo.MembershipRepository#findMembers};
 * keep the two in sync.
 */
public record MemberView(
        UUID userId,
        String email,
        String displayName,
        Role role,
        String preferredLocale,
        UserStatus userStatus,
        MembershipStatus membershipStatus,
        Instant joinedAt,
        Instant lastLoginAt
) {}
