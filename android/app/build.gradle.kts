plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val singleAbi = providers.gradleProperty("abi").orNull

android {
    namespace = "org.c3lingo.audio"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.c3lingo.audio"
        minSdk = 24
        targetSdk = 36
        // The last release, written by ./release, which tags it as v<versionName>. F-Droid
        // reads both from here, so they must stay literals. See "Releasing" in README.md.
        versionCode = 28
        versionName = "0.9.6"
        if (singleAbi != null) {
            ndk { abiFilters += singleAbi }
            // F-Droid's scheme: the ABI in the last digit, in this order.
            val abis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            require(singleAbi in abis) { "abi must be one of $abis" }
            versionCode = versionCode!! * 10 + abis.indexOf(singleAbi) + 1
        }
    }

    // One APK per processor architecture, as WebRTC's native code makes up most of the
    // size, and one universal APK with all of them. Off for app bundles, which Play splits
    // itself, and which fail to build with it, see https://issuetracker.google.com/402800800.
    // Also off with -Pabi=<ABI>, which builds a single APK for that ABI only, as F-Droid does.
    splits {
        abi {
            isEnable = singleAbi == null && gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // Leave out the dependency list that Gradle encrypts with a key only Google can read;
    // F-Droid doesn't accept it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Release signing comes from the environment; without it, release APKs are unsigned.
    val keystore = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

/** Copies the bridge script shared with the Apple app into the assets. */
abstract class CopyFrontendBridge : DefaultTask() {
    @get:InputFile
    abstract val script: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        script.get().asFile.copyTo(target.resolve("FrontendBridge.js"))
    }
}

val copyFrontendBridge = tasks.register<CopyFrontendBridge>("copyFrontendBridge") {
    script.set(layout.projectDirectory.file("../../shared/FrontendBridge.js"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyFrontendBridge, CopyFrontendBridge::outputDir)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.mediarouter)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.webrtc)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
