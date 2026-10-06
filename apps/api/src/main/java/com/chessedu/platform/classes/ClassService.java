package com.chessedu.platform.classes;

import com.chessedu.platform.classes.dto.ClassGroupDetail;
import com.chessedu.platform.classes.dto.ClassGroupSummary;
import com.chessedu.platform.classes.dto.ClassRosterMember;
import com.chessedu.platform.classes.dto.CreateClassRequest;
import com.chessedu.platform.classes.dto.UpdateClassRequest;
import com.chessedu.platform.classes.model.ClassGroup;
import com.chessedu.platform.classes.model.ClassGroupStatus;
import com.chessedu.platform.classes.model.ClassStudent;
import com.chessedu.platform.classes.model.ClassTeacher;
import com.chessedu.platform.classes.repo.ClassGroupRepository;
import com.chessedu.platform.classes.repo.ClassStudentRepository;
import com.chessedu.platform.classes.repo.ClassTeacherRepository;
import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.identity.model.Membership;
import com.chessedu.platform.identity.model.MembershipStatus;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.repo.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates classes + their rosters. Authorisation (who can touch which
 * school's classes) is handled one layer up in the controller; this service
 * only enforces <em>domain</em> invariants — e.g. a teacher must actually
 * hold a TEACHER membership in the same school before they can lead a class.
 *
 * <p>All write methods are transactional so a failed FK check or a membership
 * mismatch rolls back cleanly.
 */
@Service
public class ClassService {

    private final ClassGroupRepository classes;
    private final ClassTeacherRepository teachers;
    private final ClassStudentRepository students;
    private final MembershipRepository memberships;

    public ClassService(ClassGroupRepository classes,
                        ClassTeacherRepository teachers,
                        ClassStudentRepository students,
                        MembershipRepository memberships) {
        this.classes = classes;
        this.teachers = teachers;
        this.students = students;
        this.memberships = memberships;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ClassGroupSummary> listForSchool(UUID schoolId,
                                                 ClassGroupStatus status,
                                                 String search) {
        return classes.findSummaries(schoolId, status, buildSearchPattern(search));
    }

    /**
     * Turn a user-supplied search term into a lowercased LIKE pattern
     * (or null if the term is blank). Keeping this in Java means the SQL
     * binding only ever sees a text parameter — no risk of type inference
     * surprises when the value is null.
     */
    private static String buildSearchPattern(String search) {
        String normalised = normalize(search);
        return normalised == null ? null : "%" + normalised.toLowerCase() + "%";
    }

    @Transactional(readOnly = true)
    public ClassGroupDetail detail(UUID schoolId, UUID classId) {
        ClassGroup c = loadInSchool(classId, schoolId);
        List<ClassRosterMember> classTeachers = teachers.findTeachers(classId);
        List<ClassRosterMember> classStudents = students.findStudents(classId);
        return new ClassGroupDetail(
                c.getId(), c.getSchoolId(), c.getName(), c.getDescription(),
                c.getLevel(), c.getStatus(), classTeachers, classStudents,
                c.getCreatedAt(), c.getUpdatedAt()
        );
    }

    // ------------------------------------------------------------------
    // Class CRUD
    // ------------------------------------------------------------------

    @Transactional
    public ClassGroupDetail create(UUID schoolId, CreateClassRequest req) {
        ClassGroup c = new ClassGroup();
        c.setId(UUID.randomUUID());
        c.setSchoolId(schoolId);
        c.setName(req.name().trim());
        c.setDescription(emptyToNull(req.description()));
        c.setLevel(req.level());
        c.setStatus(ClassGroupStatus.ACTIVE);
        classes.save(c);
        // Empty rosters — no need to query.
        return new ClassGroupDetail(
                c.getId(), c.getSchoolId(), c.getName(), c.getDescription(),
                c.getLevel(), c.getStatus(), List.of(), List.of(),
                c.getCreatedAt(), c.getUpdatedAt()
        );
    }

    @Transactional
    public ClassGroupDetail update(UUID schoolId, UUID classId, UpdateClassRequest req) {
        ClassGroup c = loadInSchool(classId, schoolId);
        if (req.name() != null && !req.name().isBlank()) {
            c.setName(req.name().trim());
        }
        // Description is nullable on the domain; an explicit empty string from
        // the client means "clear it".
        if (req.description() != null) {
            c.setDescription(emptyToNull(req.description()));
        }
        if (req.level() != null) {
            c.setLevel(req.level());
        }
        if (req.status() != null) {
            c.setStatus(req.status());
        }
        classes.save(c);
        return detail(schoolId, classId);
    }

    // ------------------------------------------------------------------
    // Roster
    // ------------------------------------------------------------------

    @Transactional
    public void addTeacher(UUID schoolId, UUID classId, UUID userId) {
        loadInSchool(classId, schoolId);
        requireActiveMembershipWithRole(schoolId, userId, Role.TEACHER,
                "Only active teachers in this school can be added");
        if (teachers.existsByIdClassIdAndIdTeacherUserId(classId, userId)) {
            // Idempotent: re-adding an existing teacher is a no-op rather than 409.
            return;
        }
        ClassTeacher ct = new ClassTeacher();
        ct.setId(new ClassTeacher.ClassTeacherId(classId, userId));
        teachers.save(ct);
    }

    @Transactional
    public void removeTeacher(UUID schoolId, UUID classId, UUID userId) {
        loadInSchool(classId, schoolId);
        teachers.delete(classId, userId);
    }

    @Transactional
    public void addStudent(UUID schoolId, UUID classId, UUID userId) {
        loadInSchool(classId, schoolId);
        requireActiveMembershipWithRole(schoolId, userId, Role.STUDENT,
                "Only active students in this school can be added");
        if (students.existsByIdClassIdAndIdStudentUserId(classId, userId)) {
            return;
        }
        ClassStudent cs = new ClassStudent();
        cs.setId(new ClassStudent.ClassStudentId(classId, userId));
        students.save(cs);
    }

    @Transactional
    public void removeStudent(UUID schoolId, UUID classId, UUID userId) {
        loadInSchool(classId, schoolId);
        students.delete(classId, userId);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ClassGroup loadInSchool(UUID classId, UUID schoolId) {
        ClassGroup c = classes.findById(classId)
                .orElseThrow(() -> AppException.notFound("Class"));
        if (!c.getSchoolId().equals(schoolId)) {
            // Don't leak the existence of a class in another school — 404 is the
            // safer shape than 403 here.
            throw AppException.notFound("Class");
        }
        return c;
    }

    /**
     * Guard rail for roster edits: the user being added must have an ACTIVE
     * membership with the expected role in the same school. We deliberately
     * re-query rather than trust caller-supplied role strings.
     */
    private void requireActiveMembershipWithRole(UUID schoolId, UUID userId,
                                                 Role expected, String message) {
        Membership m = memberships.findByUserId(userId)
                .orElseThrow(() -> AppException.forbidden(message));
        if (!m.getSchoolId().equals(schoolId)) throw AppException.forbidden(message);
        if (m.getStatus() != MembershipStatus.ACTIVE) throw AppException.forbidden(message);
        if (m.getRole() != expected) throw AppException.forbidden(message);
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
