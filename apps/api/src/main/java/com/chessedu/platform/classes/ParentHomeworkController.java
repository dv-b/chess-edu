package com.chessedu.platform.classes;

import com.chessedu.platform.classes.dto.StudentHomeworkView;
import com.chessedu.platform.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Parent read-only view of a specific child's homework.
 *
 * <p>Lives under the existing {@code /api/v1/parent/children} prefix to
 * match {@link com.chessedu.platform.identity.children.ParentChildrenController}.
 * The service layer verifies the caller is actually linked to this child
 * before returning anything.
 */
@RestController
@RequestMapping("/api/v1/parent/children/{studentId}/homework")
@PreAuthorize("hasRole('PARENT')")
public class ParentHomeworkController {

    private final HomeworkService service;

    public ParentHomeworkController(HomeworkService service) {
        this.service = service;
    }

    @GetMapping
    public List<StudentHomeworkView> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("studentId") UUID studentId
    ) {
        return service.listForChild(principal, studentId);
    }
}
