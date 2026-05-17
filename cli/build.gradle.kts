plugins {
    kotlin("jvm")
    application
}

group = "com.scoopai"
version = "0.1.0"

application {
    mainClass.set("com.scoopai.cli.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation(project(":tools"))
    implementation("ai.koog:koog-agents:0.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}

kotlin {
    jvmToolchain(17)
}
