package com.chessedu.platform.identity.model;

/**
 * Platform-level role held by Anthropic/us staff — orthogonal to {@link Role}.
 * Users with a PlatformRole have no school membership.
 */
public enum PlatformRole {
    PLATFORM_ADMIN,
    PLATFORM_SUPPORT;

    public String authority() {
        return "ROLE_" + name();
    }
}
