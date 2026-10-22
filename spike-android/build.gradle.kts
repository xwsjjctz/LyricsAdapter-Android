plugins {
    alias(libs.plugins.android.application)
}

/** Copies the compat test plugin and the desktop's installed official plugins into the app's assets. */
abstract class CopySpikeAssets : DefaultTask() {
    @get:InputFile
    abstract val testPlugin: RegularFileProperty

    @get:InputDirectory
    abstract val installedPlugins: DirectoryProperty

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
    }
}

val copySpikeAssets = tasks.register<CopySpikeAssets>("copySpikeAssets") {
    testPlugin.set(rootProject.layout.projectDirectory.file("plugin-host/src/test/resources/test-plugin.cjs"))
    installedPlugins.set(File(System.getProperty("user.home"), ".la/plugin"))
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copySpikeAssets, CopySpikeAssets::outputDirectory)
    }
}

dependencies {
    implementation(project(":plugin-host"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
