package com.chessedu.platform.tenancy;

/**
 * @deprecated Superseded in M2 by
 * {@code com.chessedu.platform.security.JwtAuthenticationFilter}, which
 * derives tenant context from the authenticated JWT's {@code sid} claim
 * and writes it to {@link TenantContext} on a per-request basis. The
 * old header-based approach was only safe while auth was stubbed.
 *
 * <p>This file is retained as a marker so the git history + package
 * layout stay navigable; it is no longer a Spring component.
 */
@Deprecated(since = "M2", forRemoval = true)
public final class TenantFilter {
    private TenantFilter() {
        throw new AssertionError("Not instantiable; see JwtAuthenticationFilter.");
    }
}
