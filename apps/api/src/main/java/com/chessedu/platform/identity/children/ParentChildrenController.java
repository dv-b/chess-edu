package com.chessedu.platform.identity.children;

import com.chessedu.platform.identity.children.dto.ChildView;
import com.chessedu.platform.identity.children.dto.CreateChildRequest;
import com.chessedu.platform.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Parent-facing API for managing the students they're guardians of.
 *
 * <p>Contrast with the admin/children shape that lives under
 * {@code /api/v1/admin/members?role=STUDENT}: this controller is scoped to
 * "children of the caller" only, which is what the parent dashboard needs.
 */
@RestController
@RequestMapping("/api/v1/parent/children")
@PreAuthorize("hasRole('PARENT')")
public class ParentChildrenController {

    private final ChildrenService children;

    public ParentChildrenController(ChildrenService children) {
        this.children = children;
    }

    @GetMapping
    public List<ChildView> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return children.listMine(principal);
    }

    @PostMapping
    public ChildView create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateChildRequest body
    ) {
        return children.addChild(principal, body);
    }

    @DeleteMapping("/{studentId}")
    public ResponseEntity<Void> unlink(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("studentId") UUID studentId
    ) {
        children.unlinkChild(principal, studentId);
        return ResponseEntity.noContent().build();
    }
}
