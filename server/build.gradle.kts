import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy

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

    // Jadx APIs are needed only for compilation. Runtime classes are supplied by
    // the user-selected JADX fat jar (jadx-*-all.jar) via VS Code settings.
    compileOnly("io.github.skylot:jadx-core")
    compileOnly("io.github.skylot:jadx-cli")
    compileOnly("io.github.skylot:jadx-plugins-tools")

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
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    // Keep extension size small: JADX classes are provided by user-selected jar at runtime.
    exclude("jadx/**")
    // Merge ServiceLoader descriptors so all JadxPlugin registrations survive bundling.
    // Without this, Shadow picks one arbitrarily and breaks jadx's plugin loading.
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn("shadowJar")
}
