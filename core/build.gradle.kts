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
    implementation(libs.koog.deepseek.client)
    implementation(libs.koog.openrouter.client)
    implementation(libs.koog.anthropic.client)
    implementation(libs.koog.ollama.client)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.koog.agents.test)
    testRuntimeOnly(libs.logback.classic)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
