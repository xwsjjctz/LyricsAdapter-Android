import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    // The consumer supplies JNA: the jar on the JVM, the @aar on Android.
    compileOnly(libs.jna)

    testImplementation(libs.jna)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val rustDirectory = layout.projectDirectory.dir("rust")
val hostLibrary = System.mapLibraryName("audio_tags")

val cargoBuildHost = tasks.register<Exec>("cargoBuildHost") {
    description = "Builds the Rust library for this machine, for JVM tests and binding generation."
    workingDir(rustDirectory)
    commandLine("cargo", "build", "--lib")
    inputs.files(fileTree(rustDirectory.dir("src")), rustDirectory.file("Cargo.toml"), rustDirectory.file("Cargo.lock"))
    outputs.file(rustDirectory.file("target/debug/$hostLibrary"))
}

tasks.register<Exec>("generateBindings") {
    description = "Regenerates the Kotlin bindings in src/main/kotlin from the Rust library."
    dependsOn(cargoBuildHost)
    workingDir(rustDirectory)
    commandLine(
        "cargo", "run", "-q", "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", "target/debug/$hostLibrary", "--language", "kotlin",
        "--out-dir", "../src/main/kotlin", "--no-format",
    )
}

tasks.test {
    useJUnitPlatform()
    dependsOn(cargoBuildHost)
    systemProperty("jna.library.path", rustDirectory.dir("target/debug").asFile.absolutePath)
    systemProperty("audioTags.fixtures", layout.projectDirectory.dir("fixtures").asFile.absolutePath)
    systemProperty("audioTags.written", layout.buildDirectory.dir("written").get().asFile.absolutePath)
}
