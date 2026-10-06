package com.chessedu.platform.identity.children;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.children.dto.ChildView;
import com.chessedu.platform.identity.children.dto.CreateChildRequest;
import com.chessedu.platform.identity.model.Membership;
import com.chessedu.platform.identity.model.MembershipStatus;
import com.chessedu.platform.identity.model.ParentLink;
import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.model.UserStatus;
import com.chessedu.platform.identity.repo.MembershipRepository;
import com.chessedu.platform.identity.repo.ParentLinkRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Parent → student flow. In M3 children are created by their parents (not
 * invited by the school) and have no login of their own — they're profile
 * rows that the admin can then enrol in classes. Credentials and real email
 * arrive later, out of scope for this slice.
 *
 * <p>Authorisation cheat-sheet:
 * <ul>
 *   <li>{@link #listMine} / {@link #addChild} / {@link #unlinkChild} are all
 *       parent-scoped. PLATFORM_ADMIN is deliberately <em>not</em> allowed
 *       to impersonate a parent here — they have the full members list via
 *       the admin endpoints and don't need this convenience shape.</li>
 *   <li>The parent must have an ACTIVE PARENT membership in a school;
 *       children are always created inside that school.</li>
 * </ul>
 */
@Service
public class ChildrenService {

    /** Domain used for synthetic emails on profile-only students. */
    static final String SYNTHETIC_EMAIL_DOMAIN = "children.kingside.internal";

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final ParentLinkRepository parentLinks;

    public ChildrenService(UserRepository users,
                           MembershipRepository memberships,
                           ParentLinkRepository parentLinks) {
        this.users = users;
        this.memberships = memberships;
        this.parentLinks = parentLinks;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ChildView> listMine(AuthenticatedUser actor) {
        Membership parent = requireActiveParent(actor);
        List<ParentLink> links = parentLinks.findByIdParentUserId(parent.getUserId());
        if (links.isEmpty()) return List.of();

        // Pull the student users + their memberships in bulk. N is small (one
        // parent rarely has more than a handful of children), so we just do the
        // straightforward map rather than building a specialised join query.
        return links.stream()
                .map(link -> toView(link, parent.getSchoolId()))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ChildView::linkedAt).reversed())
                .toList();
    }

    private ChildView toView(ParentLink link, UUID parentSchoolId) {
        UUID studentId = link.getId().getStudentUserId();
        User student = users.findById(studentId).orElse(null);
        if (student == null) return null;
        Membership m = memberships.findByUserId(studentId).orElse(null);
        // A student created by another school or re-used elsewhere should not
        // leak into this parent's list. In practice they share the parent's
        // schoolId because we write it on create, but guard anyway.
        if (m == null || !parentSchoolId.equals(m.getSchoolId())) return null;
        return new ChildView(
                student.getId(),
                student.getDisplayName(),
                student.getPreferredLocale(),
                student.getStatus(),
                m.getStatus(),
                link.getCreatedAt()
        );
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Transactional
    public ChildView addChild(AuthenticatedUser actor, CreateChildRequest req) {
        Membership parent = requireActiveParent(actor);

        String displayName = req.displayName() == null ? null : req.displayName().trim();
        if (displayName == null || displayName.isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "displayName is required");
        }
        String locale = pickLocale(req.preferredLocale());

        // --- 1. Create the student user (no credentials — profile only) ----
        UUID studentId = UUID.randomUUID();
        User student = new User();
        student.setId(studentId);
        student.setEmail(syntheticEmail(studentId));
        student.setDisplayName(displayName);
        student.setPreferredLocale(locale);
        student.setStatus(UserStatus.ACTIVE);
        users.save(student);

        // --- 2. STUDENT membership in the parent's school ------------------
        Membership membership = new Membership();
        membership.setId(UUID.randomUUID());
        membership.setUserId(studentId);
        membership.setSchoolId(parent.getSchoolId());
        membership.setRole(Role.STUDENT);
        membership.setStatus(MembershipStatus.ACTIVE);
        memberships.save(membership);

        // --- 3. Parent link ------------------------------------------------
        ParentLink link = new ParentLink();
        link.setId(new ParentLink.ParentLinkId(parent.getUserId(), studentId));
        link.setSchoolId(parent.getSchoolId());
        link.setCreatedAt(Instant.now());
        parentLinks.save(link);

        return new ChildView(
                studentId,
                displayName,
                locale,
                student.getStatus(),
                membership.getStatus(),
                link.getCreatedAt()
        );
    }

    /**
     * Removes the link between the calling parent and the given student. The
     * student user + membership are left intact; the school admin decides
     * whether to archive them. This keeps class-roster history clean when a
     * parent toggles children on and off their own view.
     */
    @Transactional
    public void unlinkChild(AuthenticatedUser actor, UUID studentUserId) {
        Membership parent = requireActiveParent(actor);
        ParentLink.ParentLinkId id = new ParentLink.ParentLinkId(parent.getUserId(), studentUserId);
        ParentLink link = parentLinks.findById(id)
                .orElseThrow(() -> AppException.notFound("Child link"));
        if (!parent.getSchoolId().equals(link.getSchoolId())) {
            // Paranoid cross-school guard — shouldn't happen under normal flow.
            throw AppException.notFound("Child link");
        }
        parentLinks.delete(link);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Membership requireActiveParent(AuthenticatedUser actor) {
        if (actor == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Not authenticated");
        }
        // PLATFORM_ADMIN acting on this endpoint would have to impersonate a
        // specific parent; deliberately not supported in M3.
        if (actor.platformRole() == PlatformRole.PLATFORM_ADMIN) {
            throw AppException.forbidden("Platform admins cannot use the parent children endpoint");
        }
        if (actor.role() != Role.PARENT) {
            throw AppException.forbidden("Only parents can manage children");
        }
        Membership m = memberships.findByUserId(actor.userId())
                .orElseThrow(() -> AppException.forbidden("Parent membership not found"));
        if (m.getStatus() != MembershipStatus.ACTIVE) {
            throw AppException.forbidden("Parent membership is not active");
        }
        if (m.getRole() != Role.PARENT) {
            // Token claims and DB disagree — reject rather than proceed.
            throw AppException.forbidden("Only parents can manage children");
        }
        return m;
    }

    /**
     * Pick a locale for the newly-created child. The parent's preferred locale
     * would be ideal but we don't have it on {@link AuthenticatedUser}; fall
     * back to what the client asked for, or 'uz' as the platform default.
     */
    private static String pickLocale(String requested) {
        if (requested != null && SUPPORTED_LOCALES.contains(requested)) return requested;
        return "uz";
    }

    private static final Set<String> SUPPORTED_LOCALES = Set.of("uz", "ru", "en");

    /**
     * Build a per-student synthetic email. Uses a non-deliverable domain so
     * it's obvious from a DB scrape that the row is a parent-created student
     * without a real inbox.
     */
    static String syntheticEmail(UUID studentId) {
        return "child-" + studentId + "@" + SYNTHETIC_EMAIL_DOMAIN;
    }
}
