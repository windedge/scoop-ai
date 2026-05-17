plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.scoopai"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation(libs.koog.agents)
    implementation(libs.kotlinx.serialization.json)
}

kotlin {
    jvmToolchain(17)
}
