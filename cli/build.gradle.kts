import java.io.ByteArrayOutputStream

plugins {
    kotlin("jvm")
    application
    alias(libs.plugins.shadow)
}

group = "com.scoopai"
version = rootProject.version

application {
    mainClass.set("com.scoopai.cli.MainKt")
    applicationName = "scoop-ai"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
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

dependencies {
    implementation(project(":scoop-ai-core"))
    implementation(project(":scoop-ai-tools"))
    implementation("ai.koog:koog-agents:1.1.1", koogHttpExclusions)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)
    implementation(libs.clikt)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

val generateVersionProperties by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated-resources/scoopai-version")
    outputs.dir(outputDir)
    doLast {
        outputDir.get().asFile.mkdirs()
        val version = project.version.toString()
        val commit = try {
            val out = ByteArrayOutputStream()
            project.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
                standardOutput = out
            }
            out.toString(Charsets.UTF_8.name()).trim()
        } catch (_: Exception) {
            "unknown"
        }
        File(outputDir.get().asFile, "scoopai-version.properties")
            .writeText("version=$version\ncommit=$commit\n")
    }
}

tasks.processResources {
    from(generateVersionProperties)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.named<CreateStartScripts>("startScripts") {
    classpath = files(tasks.shadowJar)
}

tasks.register<Copy>("installManPage") {
    from(rootProject.layout.projectDirectory.file("docs/man/scoop-ai.1"))
    into(layout.buildDirectory.dir("dist/man/man1"))
}

tasks.named("installDist") {
    dependsOn("installManPage")
}

tasks.named<Sync>("installDist") {
    into("lib") {
        from(tasks.shadowJar)
    }
    doLast {
        // Remove individual dependency jars, only keep the shadow fat jar
        val libDir = destinationDir.resolve("lib")
        val shadowJarName = tasks.shadowJar.get().archiveFileName.get()
        libDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".jar") && file.name != shadowJarName) {
                file.delete()
            }
        }
    }
}

// Real-gateway smoke for the replyLanguage chain: run manually with SCOOP_AI_*
// env vars set; the main() itself skips (exit 0) when they are missing.
// The runner lives in tools jvmTest so it stays out of the published jar.
tasks.register<JavaExec>("runReplyLanguageSmoke") {
    group = "verification"
    description = "Runs ReplyLanguageSmoke.kt main() against the real gateway (SCOOP_AI_* env vars required)."
    mainClass.set("com.scoopai.tools.ReplyLanguageSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath +
        project(":scoop-ai-tools").configurations.getByName("jvmTestRuntimeClasspath") +
        files(project(":scoop-ai-tools").layout.buildDirectory.dir("classes/kotlin/jvm/test"))
    dependsOn(":scoop-ai-tools:compileTestKotlinJvm")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
    environment(System.getenv())
}

// Same runner pattern as runReplyLanguageSmoke, but the instruction forces the
// verify failure path so the repair prompt's language anchors are exercised.
// The runner stays in tools jvmTest and is never part of the published jar.
tasks.register<JavaExec>("runReplyLanguageRepairSmoke") {
    group = "verification"
    description = "Runs ReplyLanguageRepairSmoke.kt main() against the real gateway (SCOOP_AI_* env vars required)."
    mainClass.set("com.scoopai.tools.ReplyLanguageRepairSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath +
        project(":scoop-ai-tools").configurations.getByName("jvmTestRuntimeClasspath") +
        files(project(":scoop-ai-tools").layout.buildDirectory.dir("classes/kotlin/jvm/test"))
    dependsOn(":scoop-ai-tools:compileTestKotlinJvm")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
    environment(System.getenv())
}
