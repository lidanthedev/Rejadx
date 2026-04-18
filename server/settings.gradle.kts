rootProject.name = "rejadx-server"

// Composite build: wire jadx-core from the local submodule instead of Maven Central.
// We explicitly map each required module used by this server.
includeBuild("../third_party/jadx") {
    dependencySubstitution {
        substitute(module("io.github.skylot:jadx-core")).using(project(":jadx-core"))
        substitute(module("io.github.skylot:jadx-cli")).using(project(":jadx-cli"))
        substitute(module("io.github.skylot:jadx-plugins-tools")).using(project(":jadx-plugins-tools"))
        substitute(module("io.github.skylot:jadx-dex-input")).using(project(":jadx-plugins:jadx-dex-input"))
        substitute(module("io.github.skylot:jadx-java-input")).using(project(":jadx-plugins:jadx-java-input"))
        substitute(module("io.github.skylot:jadx-java-convert")).using(project(":jadx-plugins:jadx-java-convert"))
        substitute(module("io.github.skylot:jadx-smali-input")).using(project(":jadx-plugins:jadx-smali-input"))
        substitute(module("io.github.skylot:jadx-rename-mappings")).using(project(":jadx-plugins:jadx-rename-mappings"))
        substitute(module("io.github.skylot:jadx-kotlin-metadata")).using(project(":jadx-plugins:jadx-kotlin-metadata"))
        substitute(module("io.github.skylot:jadx-kotlin-source-debug-extension")).using(project(":jadx-plugins:jadx-kotlin-source-debug-extension"))
        substitute(module("io.github.skylot:jadx-xapk-input")).using(project(":jadx-plugins:jadx-xapk-input"))
        substitute(module("io.github.skylot:jadx-aab-input")).using(project(":jadx-plugins:jadx-aab-input"))
        substitute(module("io.github.skylot:jadx-apkm-input")).using(project(":jadx-plugins:jadx-apkm-input"))
        substitute(module("io.github.skylot:jadx-apks-input")).using(project(":jadx-plugins:jadx-apks-input"))
    }
}
