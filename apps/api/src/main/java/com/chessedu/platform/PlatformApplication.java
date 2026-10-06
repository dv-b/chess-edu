package com.chessedu.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the chess-edu backend.
 *
 * <p>The application is organised into modules under {@code com.chessedu.platform.*}:
 * <ul>
 *   <li>{@code common}   — cross-cutting primitives (error envelope, request id, base entity)</li>
 *   <li>{@code tenancy}  — school row as the tenant + ThreadLocal context + filter</li>
 *   <li>{@code identity} — users, roles, auth</li>
 *   <li>{@code security} — JWT, password encoding, security filter chain</li>
 * </ul>
 */
@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling
@ConfigurationPropertiesScan("com.chessedu.platform")
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
