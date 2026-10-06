package com.chessedu.platform.identity.model;

/**
 * A user's role inside a school. One role per user for the MVP — role
 * switching is intentionally not supported.
 */
public enum Role {
    SCHOOL_ADMIN,
    TEACHER,
    PARENT,
    STUDENT;

    public String authority() {
        return "ROLE_" + name();
    }
}
