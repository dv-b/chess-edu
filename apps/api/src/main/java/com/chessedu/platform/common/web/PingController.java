package com.chessedu.platform.common.web;

import com.chessedu.platform.tenancy.SchoolRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Smoke-test endpoints — proves the wiring works end-to-end: Flyway
 * migrated, JPA can see the school row, Jackson is configured for the
 * right timezone.
 */
@RestController
@RequestMapping("/api/v1")
public class PingController {

    private final SchoolRepository schools;

    public PingController(SchoolRepository schools) {
        this.schools = schools;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "service", "chess-edu-api",
                "now", Instant.now(),
                "schools", schools.count()
        );
    }
}
