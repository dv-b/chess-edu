package com.chessedu.platform.classes.model;

/**
 * Lifecycle state of a class. We deliberately don't model SUSPENDED —
 * pausing a class is better expressed as ARCHIVED + a fresh class later.
 */
public enum ClassGroupStatus {
    ACTIVE,
    ARCHIVED
}
