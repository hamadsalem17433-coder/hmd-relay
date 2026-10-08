plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
