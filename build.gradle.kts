plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "com.hmd"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    val ktorVersion = "2.3.8"
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")

    implementation("com.google.crypto.tink:tink:1.12.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.23.0")
    implementation("ch.qos.logback:logback-classic:1.4.14")
}

application {
    mainClass.set("com.hmd.server.ApplicationKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}
