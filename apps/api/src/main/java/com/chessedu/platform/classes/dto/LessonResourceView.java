package com.chessedu.platform.classes.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Read shape for one content block attached to a lesson. The
 * {@code payload} is shipped raw — the frontend (admin editor + student
 * viewer) knows how to interpret each {@code kind}.
 *
 * <p>In v1 the emitted kinds are {@code "PROSE"},
 * {@code "BOARD_WALKTHROUGH"}, {@code "MCQ"}, {@code "VIDEO"}; the
 * payload shapes are documented on
 * {@link com.chessedu.platform.classes.model.LessonResource}.
 *
 * <p>Unlike homework, lessons have no teacher-only fields to strip on
 * the student side — MCQ's {@code correctIndex} is intentionally sent
 * (the student viewer reveals the correct answer after selection rather
 * than hiding it on the wire; this keeps the client stateless and
 * avoids a second roundtrip on "check my answer"). If a future
 * author-side toggle ever wants a "hide correct answer until
 * submission" mode, that's the call site to add a strip helper —
 * wiring it in would mirror
 * {@code HomeworkService#stripSolution(JsonNode)}.
 */
public record LessonResourceView(
        UUID id,
        int position,
        String kind,
        String title,
        JsonNode payload
) {}
