plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("org.flywaydb.flyway") version "10.20.0"
}

group = "com.chessedu"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

configurations {
    compileOnly { extendsFrom(configurations.annotationProcessor.get()) }
}

// Repositories are declared centrally in settings.gradle.kts via
// dependencyResolutionManagement (RepositoriesMode.FAIL_ON_PROJECT_REPOS).
// Do not re-declare a `repositories {}` block here.

dependencies {
    // --- Spring Boot starters ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // --- Database ---
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- Auth: JWT ---
    implementation("com.nimbusds:nimbus-jose-jwt:9.41.1")

    // --- Auth: Argon2id (Spring Security's Argon2PasswordEncoder needs BouncyCastle at runtime) ---
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // --- OpenAPI / Swagger ---
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // --- Misc ---
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.apache.commons:commons-lang3:3.17.0")

    // --- Chess: FEN + PGN parse and legal-move validation.
    // Used to re-validate authored chess content on the server side (teachers
    // can paste a position / move list; we verify it's legal before we store).
    implementation("com.github.bhlangonijr:chesslib:1.3.6")

    // --- Lombok (optional but ergonomic) ---
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    // --- Test ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("spring.profiles.active", "test")
}

springBoot {
    buildInfo()
}
