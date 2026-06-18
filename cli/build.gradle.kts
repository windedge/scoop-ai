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

dependencies {
    implementation(project(":core"))
    implementation(project(":tools"))
    implementation("ai.koog:koog-agents:${libs.versions.koog.get()}")
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

tasks.named<Sync>("installDist") {
    into("lib") {
        from(tasks.shadowJar)
    }
    doLast {
        // Remove individual dependency jars — only keep the shadow fat jar
        val libDir = destinationDir.resolve("lib")
        val shadowJarName = tasks.shadowJar.get().archiveFileName.get()
        libDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".jar") && file.name != shadowJarName) {
                file.delete()
            }
        }
    }
}
