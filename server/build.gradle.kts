import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    application
    id("com.gradleup.shadow") version "9.4.1"
}

group = "dev.rejadx"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
    // Required for jadx's own transitive deps (aapt-proto, r8, smali)
    google()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // LSP4J — JSON-RPC / LSP 3.17 protocol implementation
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")

    // jadx-core is supplied via the composite build substitution in settings.gradle.kts.
    // The Maven coordinate is transparently replaced with the :jadx-core source project.
    implementation("io.github.skylot:jadx-core")

    // SLF4J backend — routes jadx log output to stderr (never stdout, which is JSON-RPC)
    implementation("org.slf4j:slf4j-simple:2.0.12")
}

application {
    mainClass.set("dev.rejadx.server.ReJadxServer")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("rejadx-server")
    archiveClassifier.set("all")
    archiveVersion.set("")
    // Merge ServiceLoader descriptors so all JadxPlugin registrations survive bundling.
    // Without this, Shadow picks one arbitrarily and breaks jadx's plugin loading.
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn("shadowJar")
}
