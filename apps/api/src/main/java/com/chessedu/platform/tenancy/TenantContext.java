package com.chessedu.platform.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * ThreadLocal holder for the current tenant (school) id.
 *
 * <p>The {@code JwtAuthenticationFilter} populates this from the {@code sid}
 * claim of a verified access token; every JPA query runs under a Hibernate
 * {@code @Filter} that reads from here. Never set this from untrusted client
 * input — the value must come from a verified JWT.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID schoolId) {
        CURRENT.set(schoolId);
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        UUID id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException("Tenant context not set on the current thread");
        }
        return id;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
