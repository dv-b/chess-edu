rootProject.name = "chess-edu"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // chesslib (used for server-side chess content validation) is
        // distributed via JitPack rather than Maven Central. Scoped to the
        // bhlangonijr group so other dependencies still resolve exclusively
        // from Maven Central.
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.bhlangonijr")
            }
        }
    }
}

// Map Gradle project `:api` to the filesystem path apps/api so we avoid an
// intermediate `:apps` umbrella project. Tasks run as `./gradlew :api:bootRun`.
include(":api")
project(":api").projectDir = file("apps/api")
