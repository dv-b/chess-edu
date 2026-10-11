package com.chessedu.platform.tenancy;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * {@code GET /api/v1/admin/schools} — every tenant, for platform-admin
 * pickers (the invitations screens need a school to act on; a platform admin
 * has no membership to infer one from). Platform admins only: a school admin
 * never needs to see other tenants.
 */
@RestController
@RequestMapping("/api/v1/admin/schools")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminSchoolController {

    private final SchoolRepository schools;

    public AdminSchoolController(SchoolRepository schools) {
        this.schools = schools;
    }

    @GetMapping
    public List<SchoolView> list() {
        return schools.findAll().stream()
                .sorted(Comparator.comparing(School::getDisplayName, String.CASE_INSENSITIVE_ORDER))
                .map(SchoolView::of)
                .toList();
    }
}
