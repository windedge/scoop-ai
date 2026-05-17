plugins {
    kotlin("jvm")
    application
    alias(libs.plugins.shadow)
}

group = "com.scoopai"
version = "0.1.0"

application {
    mainClass.set("com.scoopai.cli.MainKt")
    applicationName = "scoop-ai"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation(project(":tools"))
    implementation(libs.koog.agents)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)
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
