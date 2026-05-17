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
    implementation(libs.koog.agents)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)
}

kotlin {
    jvmToolchain(17)
}
