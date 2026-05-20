plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

group = "com.scoopai"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.koog.agents)
            implementation(libs.kotlinx.serialization.json)
        }

        jvmMain.dependencies {
            implementation(libs.koog.deepseek.client)
            implementation(libs.koog.openrouter.client)
            implementation(libs.koog.anthropic.client)
            implementation(libs.koog.ollama.client)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.logback.classic)
            implementation(libs.kotlin.logging)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.koog.agents.test)
        }
    }
}
