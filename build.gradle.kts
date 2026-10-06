// Root Gradle file — configuration is per-subproject. See apps/api/build.gradle.kts.

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
