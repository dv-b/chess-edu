package com.chessedu.platform.tenancy;

import java.util.UUID;

/** Minimal school row for pickers (platform-admin surfaces). */
public record SchoolView(
        UUID id,
        String slug,
        String displayName,
        String status
) {
    static SchoolView of(School s) {
        return new SchoolView(s.getId(), s.getSlug(), s.getDisplayName(),
                s.getStatus() != null ? s.getStatus().name() : null);
    }
}
