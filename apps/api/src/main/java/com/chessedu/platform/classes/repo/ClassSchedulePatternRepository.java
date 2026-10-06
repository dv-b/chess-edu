package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.model.ClassSchedulePattern;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClassSchedulePatternRepository
        extends JpaRepository<ClassSchedulePattern, UUID> {

    /** All patterns on a class, ordered for a stable UI: weekday, then start time. */
    List<ClassSchedulePattern> findByClassIdOrderByDayOfWeekAscStartTimeAsc(UUID classId);
}
