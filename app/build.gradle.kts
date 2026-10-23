plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

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
        versionCode = 1
        versionName = "0.1.0"
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
        // Lets development builds sit beside an installed release, including the older Android app that used this id.
        getByName("debug") {
            applicationIdSuffix = ".debug"
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
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
