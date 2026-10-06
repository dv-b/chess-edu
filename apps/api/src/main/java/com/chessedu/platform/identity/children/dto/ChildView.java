package com.chessedu.platform.identity.children.dto;

import com.chessedu.platform.identity.model.MembershipStatus;
import com.chessedu.platform.identity.model.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-model the parent UI sees for each linked student.
 *
 * <p>We deliberately don't surface the synthetic email — it's an internal
 * placeholder (see {@link com.chessedu.platform.identity.children.ChildrenService})
 * and showing it to parents would be confusing.
 */
public record ChildView(
        UUID studentUserId,
        String displayName,
        String preferredLocale,
        UserStatus userStatus,
        MembershipStatus membershipStatus,
        Instant linkedAt
) {}
