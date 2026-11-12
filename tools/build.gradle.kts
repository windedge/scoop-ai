import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    alias(libs.plugins.vanniktech.maven.publish)
}

group = "io.github.windedge"
version = rootProject.version

mavenPublishing {
    // No automatic release: upload to Central Portal, publish manually from the web UI.
    publishToMavenCentral()
    signAllPublications()

    coordinates(groupId = "io.github.windedge", artifactId = "scoop-ai-tools", version = rootProject.version as String)

    pom {
        name = "scoop-ai-tools"
        description = "Scoop tooling (manifest IO, verification, LLM clients) for the scoop-ai agent"
        url = "https://github.com/windedge/scoop-ai"
        licenses {
            license {
                name = "MIT"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                name = "windedge"
            }
        }
        scm {
            url = "https://github.com/windedge/scoop-ai"
            connection = "scm:git:git://github.com/windedge/scoop-ai.git"
            developerConnection = "scm:git:ssh://git@github.com/windedge/scoop-ai.git"
        }
    }
}

mavenPublishing {
    configure(
        KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = true)
    )
}

repositories {
    mavenCentral()
}

// Keep a single Koog HTTP client (Java): strip Koog's other HTTP/bundled clients and
// the whole Ktor stack they would pull in, on every Koog dependency edge.
val koogHttpExclusions: ExternalModuleDependency.() -> Unit = {
    exclude(group = "ai.koog", module = "prompt-executor-bedrock-client")
    exclude(group = "ai.koog", module = "agents-features-opentelemetry")
    exclude(group = "ai.koog", module = "serialization-jackson")
    exclude(group = "ai.koog", module = "http-client-ktor")
    exclude(group = "io.ktor", module = "ktor-client-apache5")
    exclude(group = "io.ktor", module = "ktor-client-cio")
    exclude(group = "io.ktor", module = "ktor-client-content-negotiation")
    exclude(group = "io.ktor", module = "ktor-client-core")
    exclude(group = "io.ktor", module = "ktor-client-logging")
    exclude(group = "io.ktor", module = "ktor-events")
    exclude(group = "io.ktor", module = "ktor-http")
    exclude(group = "io.ktor", module = "ktor-http-cio")
    exclude(group = "io.ktor", module = "ktor-io")
    exclude(group = "io.ktor", module = "ktor-network")
    exclude(group = "io.ktor", module = "ktor-network-tls")
    exclude(group = "io.ktor", module = "ktor-serialization")
    exclude(group = "io.ktor", module = "ktor-serialization-kotlinx")
    exclude(group = "io.ktor", module = "ktor-serialization-kotlinx-json")
    exclude(group = "io.ktor", module = "ktor-server-cio")
    exclude(group = "io.ktor", module = "ktor-server-core")
    exclude(group = "io.ktor", module = "ktor-server-sse")
    exclude(group = "io.ktor", module = "ktor-sse")
    exclude(group = "io.ktor", module = "ktor-utils")
    exclude(group = "io.ktor", module = "ktor-websocket-serialization")
    exclude(group = "io.ktor", module = "ktor-websockets")
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
            api(project(":scoop-ai-core"))
            implementation("ai.koog:koog-agents:1.1.1", koogHttpExclusions)
            implementation(libs.kotlinx.serialization.json)
        }

        jvmMain.dependencies {
            implementation("ai.koog:prompt-executor-deepseek-client:1.1.1-beta", koogHttpExclusions)
            implementation("ai.koog:prompt-executor-openrouter-client:1.1.1", koogHttpExclusions)
            implementation("ai.koog:prompt-executor-anthropic-client:1.1.1", koogHttpExclusions)
            implementation("ai.koog:prompt-executor-ollama-client:1.1.1", koogHttpExclusions)
            implementation("ai.koog:http-client-java:1.1.1", koogHttpExclusions)
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
