plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    `maven-publish`
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
            implementation(libs.koog.agents)
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
