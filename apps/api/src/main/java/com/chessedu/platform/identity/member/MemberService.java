package com.chessedu.platform.identity.member;

import com.chessedu.platform.identity.member.dto.MemberView;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.repo.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-only view of people ("members") attached to a school. Everything else
 * about user lifecycle (invite, accept, suspend, archive) lives in
 * {@link com.chessedu.platform.identity.invitation.InvitationService} and
 * the auth package — this service only answers "who's currently in the
 * school?" for the admin UI.
 */
@Service
public class MemberService {

    private final MembershipRepository memberships;

    public MemberService(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public List<MemberView> listForSchool(UUID schoolId, Role roleFilter, String search) {
        return memberships.findMembers(schoolId, roleFilter, buildSearchPattern(search));
    }

    /**
     * Turn a user-supplied search term into a lowercased LIKE pattern
     * (or null if the term is blank). Keeping this in Java means the SQL
     * binding only ever sees a text parameter — no null-type gymnastics
     * needed to keep Postgres happy.
     */
    private static String buildSearchPattern(String search) {
        if (search == null) return null;
        String trimmed = search.trim();
        return trimmed.isEmpty() ? null : "%" + trimmed.toLowerCase() + "%";
    }
}
