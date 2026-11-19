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
    // Local/JitPack builds have no PGP key, so signing can be skipped.
    if (!project.hasProperty("skipSigning")) {
        signAllPublications()
    }

    coordinates(groupId = "io.github.windedge", artifactId = "scoop-ai-core", version = rootProject.version as String)

    pom {
        name = "scoop-ai-core"
        description = "Scoop manifest generation engine for the scoop-ai agent"
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
            // api so library consumers see Koog types (KoogCompat surface) transitively
            api("ai.koog:koog-agents:1.1.1", koogHttpExclusions)
            api("ai.koog:prompt-executor-deepseek-client:1.1.1-beta", koogHttpExclusions)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlin.logging)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.koog.agents.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
