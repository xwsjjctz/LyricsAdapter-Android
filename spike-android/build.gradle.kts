plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** ABIs the app ships; the Rust library is built for each, other libraries' extra ABIs are dropped. */
val appAbis = listOf("arm64-v8a", "x86_64")

/** Copies the test plugins, the desktop's installed official plugins and the tag fixtures into the app's assets. */
abstract class CopySpikeAssets : DefaultTask() {
    @get:InputFile
    abstract val testPlugin: RegularFileProperty

    @get:InputDirectory
    abstract val installedPlugins: DirectoryProperty

    @get:InputDirectory
    abstract val tagFixtures: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        testPlugin.get().asFile.copyTo(output.resolve("test-plugin.cjs"))
        for (id in listOf("qq", "netease")) {
            installedPlugins.get().asFile.resolve("$id/index.cjs").copyTo(output.resolve("plugins/$id/index.cjs"))
        }
        val fixtures = tagFixtures.get().asFile
        fixtures.resolve("expected.json").copyTo(output.resolve("audio-tags/expected.json"))
        fixtures.resolve("files").copyRecursively(output.resolve("audio-tags/files"))
    }
}

/** Builds the audio-tags Rust library for Android with cargo-ndk. */
abstract class BuildRustLibraries @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val crateDirectory: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val platform: Property<Int>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val targets = abis.get().flatMap { listOf("-t", it) }
        exec.exec {
            workingDir(crateDirectory)
            commandLine(
                listOf("cargo", "ndk") + targets +
                    listOf("-P", platform.get().toString(), "-o", outputDirectory.get().asFile.absolutePath, "build", "--release", "--lib"),
            )
        }
    }
}

val copySpikeAssets = tasks.register<CopySpikeAssets>("copySpikeAssets") {
    testPlugin.set(rootProject.layout.projectDirectory.file("plugin-host/src/test/resources/test-plugin.cjs"))
    installedPlugins.set(File(System.getProperty("user.home"), ".la/plugin"))
    tagFixtures.set(rootProject.layout.projectDirectory.dir("audio-tags/fixtures"))
}

val buildRustLibraries = tasks.register<BuildRustLibraries>("buildRustLibraries") {
    val crate = rootProject.layout.projectDirectory.dir("audio-tags/rust")
    crateDirectory.set(crate)
    sources.from(crate.dir("src"), crate.file("Cargo.toml"), crate.file("Cargo.lock"))
    abis.set(appAbis)
    platform.set(30)
    outputDirectory.set(layout.buildDirectory.dir("rustJniLibs"))
}

android {
    namespace = "com.lyricsadapter.spike"
    // OkHttp 5.5 needs Android 17 APIs at compile time; runtime behaviour still follows targetSdk.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lyricsadapter.spike"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(appAbis)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        // Frame timings are only meaningful in a non-debuggable build; this one
        // installs over the debug build with the debug key.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copySpikeAssets, CopySpikeAssets::outputDirectory)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRustLibraries, BuildRustLibraries::outputDirectory)
    }
}

dependencies {
    implementation(project(":plugin-host"))
    implementation(project(":audio-tags"))
    implementation(libs.jna) {
        artifact { type = "aar" }
    }

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.metrics.performance)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
