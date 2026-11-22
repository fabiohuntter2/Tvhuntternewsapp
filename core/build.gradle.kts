import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Encoder MP3 (porta Java do LAME) - LGPL
    api("de.sciss:jump3r:1.0.5")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
