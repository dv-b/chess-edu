package com.chessedu.platform.identity.model;

public enum UserStatus {
    /** Invitation sent, no password yet. Cannot log in. */
    INVITED,
    /** Normal active account. */
    ACTIVE,
    /** Temporarily disabled by an admin. */
    SUSPENDED,
    /** Soft-deleted. Retained for audit/history only. */
    ARCHIVED
}
