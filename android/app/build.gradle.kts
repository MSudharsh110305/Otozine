plugins {
    alias(libs.plugins.android.application)
    // Kotlin is built into AGP 9; only the Compose compiler is applied here.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.otozine.player"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.otozine.player"
        // 33 (Android 13) is the floor for AGSL RuntimeShader, which the Phase 4
        // UI depends on. The S24 FE ships well above this.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    // A local self-signed key, kept out of the repository.
    //
    // It protects nothing -- the app is not distributed through a store -- but a
    // private key in a public repository is still a private key in a public
    // repository. Losing it only means the next build refuses to install over
    // the last one, which is fixed by uninstalling first.
    //
    // Generate your own with:
    //   keytool -genkeypair -v -keystore android/keystore/otozine-release.jks     //     -alias otozine -keyalg RSA -keysize 2048 -validity 10000     //     -storepass otozine-local -keypass otozine-local -dname "CN=OtoZine"
    val releaseKeystore = rootProject.file("keystore/otozine-release.jks")

    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = "otozine-local"
                keyAlias = "otozine"
                keyPassword = "otozine-local"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // Unsigned when there is no key: a fresh clone should still build,
            // and an unsigned APK that has to be signed before installing is a
            // clearer failure than a build that cannot run at all.
            signingConfig = signingConfigs.findByName("release")
            // Minification is deliberately OFF. R8 strips reflectively-reached
            // classes, and Media3's renderer/decoder selection plus Compose's
            // runtime both use reflection. With no device available to test the
            // shrunk build, a smaller APK is not worth an app that crashes on
            // first play. Turn this on once there is a device to verify against.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}
// jvmTarget is intentionally not set: AGP 9's built-in Kotlin derives it from
// compileOptions above, and setting it separately is what drifts out of sync.

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Playback. media3-session is what gives us lock screen, notification,
    // Bluetooth headset buttons and Android Auto for free.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
