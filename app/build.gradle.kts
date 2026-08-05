plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.testtube.swinglines"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.testtube.swinglines"
        minSdk = 29
        targetSdk = 34
        versionCode = 22
        versionName = "0.13.3"
    }

    // Shared test-signing key so every CI build signs identically and installs
    // as an update over the previous one. The keystore is NOT in this repo: CI
    // writes it to signing/swinglines.keystore from repo secrets before
    // building. Local builds without the key fall back to the default debug
    // signature, which still builds and runs but will not update a CI install.
    // This is a TEST key only - a private release key replaces it for any
    // store launch.
    val sharedStore = rootProject.file("signing/swinglines.keystore")
    val sharedStorePassword = System.getenv("SWINGLINES_STORE_PASSWORD")
    val sharedKeyAlias = System.getenv("SWINGLINES_KEY_ALIAS")
    val sharedKeyPassword = System.getenv("SWINGLINES_KEY_PASSWORD")
    val sharedSigningAvailable = sharedStore.exists() &&
        !sharedStorePassword.isNullOrBlank() &&
        !sharedKeyAlias.isNullOrBlank() &&
        !sharedKeyPassword.isNullOrBlank()

    if (sharedSigningAvailable) {
        signingConfigs {
            create("shared") {
                storeFile = sharedStore
                storePassword = sharedStorePassword
                keyAlias = sharedKeyAlias
                keyPassword = sharedKeyPassword
            }
        }
    } else {
        logger.lifecycle("SeePath: shared signing key unavailable, using default debug signature")
    }

    buildTypes {
        debug {
            if (sharedSigningAvailable) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            if (sharedSigningAvailable) signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    // ExoPlayer: frame-exact seeking for the replay. The stock MediaPlayer
    // snaps seeks to keyframes (about one per second), which made -1f/+1f
    // appear to do nothing.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
