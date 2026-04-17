rootProject.name = "rejadx-server"

// Composite build: wire jadx-core from the local submodule instead of Maven Central.
// Gradle automatically substitutes ALL io.github.skylot:* coordinates found in the
// included build, so transitive jadx plugins are also resolved from source.
includeBuild("../third_party/jadx") {
    dependencySubstitution {
        substitute(module("io.github.skylot:jadx-core")).using(project(":jadx-core"))
    }
}
