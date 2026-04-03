plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.4.1"
    id("org.jetbrains.changelog") version "2.5.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("dev.hollowcube:polar:1.15.1")
    implementation("net.minestom:minestom:2026.03.25-1.21.11")
}

kotlin {
    jvmToolchain(25)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.github.pandier.polarconverter.MainKt"
    }
}

changelog {
    groups.empty()
    repositoryUrl.set("https://github.com/pandier/polar-converter")
}
