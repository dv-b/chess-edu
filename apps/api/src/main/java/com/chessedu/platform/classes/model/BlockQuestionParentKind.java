package com.chessedu.platform.classes.model;

/**
 * Identifies which authored-content table a {@link BlockQuestion}'s
 * {@code parentId} resolves to.
 *
 * <p>Stored as a CHECK-constrained VARCHAR rather than a Postgres enum
 * so adding a future kind (e.g. {@code SESSION} for a question pinned
 * to a class meeting, or {@code STUDENT} for direct teacher → student
 * messages) is a service-layer concern only — no migration required.
 *
 * <p>Why we keep this as one table instead of two:
 * <ul>
 *   <li>The teacher inbox query ("show me unresolved threads in this
 *       class, oldest first") is the dominant read path. A single table
 *       covers it with one index scan; a UNION across two tables would
 *       need a view or a hand-merged service query.</li>
 *   <li>The trade-off is no real FK on {@code parent_id}. Acceptable
 *       because both potential parents (homework, lesson) soft-delete
 *       via {@code archived_at} rather than hard-delete; a question
 *       pointing at an archived parent is still a valid historical
 *       record.</li>
 * </ul>
 */
public enum BlockQuestionParentKind {
    /** Question is pinned to a block on a homework. */
    HOMEWORK,

    /** Question is pinned to a block on a lesson. */
    LESSON
}
