plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    `maven-publish`
}

group = "com.scoopai"
version = rootProject.version

repositories {
    mavenCentral()
}

// Keep a single Koog HTTP client (Java). Multiple SPI factories break DeepSeek/OpenAI client construction.
configurations.all {
    exclude(group = "ai.koog", module = "prompt-executor-bedrock-client")
    exclude(group = "ai.koog", module = "prompt-executor-bedrock-client-jvm")
    exclude(group = "ai.koog", module = "agents-features-opentelemetry")
    exclude(group = "ai.koog", module = "agents-features-opentelemetry-jvm")
    exclude(group = "ai.koog", module = "serialization-jackson")
    exclude(group = "ai.koog", module = "serialization-jackson-jvm")
    exclude(group = "ai.koog", module = "http-client-ktor")
    exclude(group = "ai.koog", module = "http-client-ktor-jvm")
    exclude(group = "io.ktor", module = "ktor-client-apache5")
    exclude(group = "io.ktor", module = "ktor-client-apache5-jvm")
    exclude(group = "io.ktor")
}

kotlin {
    jvm {
        // Align with :cli jvmToolchain(17) so cli tests can load tools/core classes.
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            // api so library consumers of scoop-ai-tools get core transitively
            api(project(":core"))
            implementation(libs.koog.agents)
            implementation(libs.kotlinx.serialization.json)
        }

        jvmMain.dependencies {
            implementation(libs.koog.deepseek.client)
            implementation(libs.koog.openrouter.client)
            implementation(libs.koog.anthropic.client)
            implementation(libs.koog.ollama.client)
            implementation("ai.koog:http-client-java:${libs.versions.koog.get()}")
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.logback.classic)
            implementation(libs.kotlin.logging)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.koog.agents.test)
            implementation(libs.junit.jupiter)
        }
    }
}

publishing {
    publications {
        withType<MavenPublication>().matching { it.name == "kotlinMultiplatform" }.configureEach {
            groupId = project.group as String
            version = project.version as String
            artifactId = "scoop-ai-tools"
        }
    }
}
