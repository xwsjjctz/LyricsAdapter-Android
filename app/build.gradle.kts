import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * The release key, from `keystore.properties` at the repository root
 * (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), which is never
 * committed. Without it release builds come out unsigned.
 */
val releaseKey: Properties? = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

/** The app's version, in the root `gradle.properties`; a release is cut from whatever it says (see .github/workflows/release.yml). */
val appVersionName: String = providers.gradleProperty("appVersionName").get()

/**
 * 1.2.3 becomes 1002003, so a later version always installs over an earlier
 * one. A pre-release suffix (1.2.3-beta.1) does not count.
 */
val appVersionCode: Int = appVersionName.substringBefore('-').split('.').map(String::toInt)
    .let { (major, minor, patch) -> major * 1_000_000 + minor * 1_000 + patch }

/**
 * Where the app looks for newer versions of itself: a GitHub "list releases"
 * address. Release builds read this repository's unless `-PupdateFeedUrl=…`
 * names another (a fork's, or a test feed); other builds have none and do not
 * update, as a release's APK is another package to them.
 */
val updateFeedUrl: String? = providers.gradleProperty("updateFeedUrl").orNull
val releaseFeedUrl = "https://api.github.com/repos/xwsjjctz/LyricsAdapter-Android/releases?per_page=30"

/** ABIs the app ships; the Rust library is built for each, other libraries' extra ABIs are dropped. */
val appAbis = listOf("arm64-v8a", "x86_64")

val buildRustLibraries = tasks.register<BuildRustLibraries>("buildRustLibraries") {
    val crate = rootProject.layout.projectDirectory.dir("audio-tags/rust")
    crateDirectory.set(crate)
    sources.from(crate.dir("src"), crate.file("Cargo.toml"), crate.file("Cargo.lock"))
    abis.set(appAbis)
    platform.set(30)
    outputDirectory.set(layout.buildDirectory.dir("rustJniLibs"))
}

android {
    namespace = "com.lyricsadapter.app"
    // OkHttp 5.5 needs Android 17 APIs at compile time; runtime behaviour still follows targetSdk.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lyricsadapter.app"
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_FEED_URL", "\"${updateFeedUrl.orEmpty()}\"")
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
        buildConfig = true
    }

    signingConfigs {
        releaseKey?.let { key ->
            create("release") {
                storeFile = rootProject.file(key.getProperty("storeFile"))
                storePassword = key.getProperty("storePassword")
                keyAlias = key.getProperty("keyAlias")
                keyPassword = key.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Lets development builds sit beside an installed release, including the older Android app that used this id.
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "UPDATE_FEED_URL", "\"${updateFeedUrl ?: releaseFeedUrl}\"")
        }
        // Release code under the debug id and key: installs over a development build, keeping its library, to measure speed.
        create("profile") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "UPDATE_FEED_URL", "\"${updateFeedUrl.orEmpty()}\"")
            matchingFallbacks += "release"
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRustLibraries, BuildRustLibraries::outputDirectory)
    }
}

dependencies {
    implementation(project(":library"))
    implementation(project(":webdav"))
    implementation(project(":plugin-host"))
    implementation(project(":update"))
    implementation(libs.jna) {
        artifact { type = "aar" }
    }

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
