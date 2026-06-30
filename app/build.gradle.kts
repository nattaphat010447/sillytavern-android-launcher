import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ── Signing ───────────────────────────────────────────────────────────────────
// Reads from keystore.properties (local, gitignored) or CI environment variables.
// See keystore.properties.template for the expected format.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

fun prop(envKey: String, fileKey: String): String? =
    System.getenv(envKey) ?: keystoreProps.getProperty(fileKey)

android {
    namespace   = "com.standroid.launcher"
    compileSdk  = 35

    defaultConfig {
        applicationId  = "com.standroid.launcher"
        minSdk         = 33
        targetSdk      = 35
        versionCode    = 7
        versionName    = "0.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Extract .so files so libnode.so is executable at runtime
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // ── ABI splits — separate APK per architecture ────────────────────────────
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true  // also produce a fat APK for sideload convenience
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding  = true
    }

    signingConfigs {
        getByName("debug") {
            // Uses Android Studio default debug keystore — fine for development
        }
        create("release") {
            storeFile     = prop("RELEASE_KEYSTORE_PATH", "storeFile")?.let { file(it) }
            storePassword = prop("RELEASE_KEYSTORE_PASSWORD", "storePassword")
            keyAlias      = prop("RELEASE_KEY_ALIAS", "keyAlias")
            keyPassword   = prop("RELEASE_KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            signingConfig     = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable         = true
        }
    }

    packaging {
        // extractNativeLibs MUST be true so libnode.so is an actual file
        // on disk and can be exec'd at runtime.
        jniLibs {
            useLegacyPackaging = true   // equivalent to extractNativeLibs=true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // ── APK naming: standroid-{abi}-{buildType}.apk ────────────────────────────
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val abiName = output.getFilter(com.android.build.OutputFile.ABI) ?: "universal"
            output.outputFileName = "app-${abiName}-${buildType.name}.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.jgit)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.splashscreen)
}
