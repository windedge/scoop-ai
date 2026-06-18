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
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("ai.koog:koog-agents:${libs.versions.koog.get()}")
            implementation(libs.koog.deepseek.client)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlin.logging)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.koog.agents.test)
        }
    }
}

publishing {
    publications {
        withType<MavenPublication>().matching { it.name == "kotlinMultiplatform" }.configureEach {
            groupId = project.group as String
            version = project.version as String
            artifactId = "scoop-ai-core"
        }
    }
}
