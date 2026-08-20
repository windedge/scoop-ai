plugins {
    kotlin("multiplatform") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
}

// Workaround: Koog 1.0.0 was compiled with Kotlin 2.1.20 which produces Kotlin 2.3 metadata;
// our project uses Kotlin 2.1.21. Force all subprojects to skip the Kotlin metadata version
// check so the compiler accepts newer metadata from Koog's jars. Retained for Koog 1.1.1
// to avoid metadata-version friction across the multiplatform jars.
subprojects {
    tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask::class.java).configureEach {
        compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}
