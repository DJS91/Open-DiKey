import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.sphy.airconcontroller"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.sphy.airconcontroller"
        minSdk = 30
        targetSdk = 36
        // Overridden by CI (-PversionName / -PversionCode) for real releases. Local/dev builds
        // (no -P flags passed) get a timestamp-based name instead of a static placeholder, so
        // Settings -> "Installed: ..." and the home screen's version label can tell apart
        // multiple local debug APKs installed in a row, the same way CI's own "vYYYY.MM.DD-HHMM"
        // release tags do (see release.yml).
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionName") as String?) ?: run {
            val stamp = SimpleDateFormat("yyyy.MM.dd-HHmm", Locale.US).format(Date())
            "$stamp-local"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val storeFilePath = System.getenv("SIGNING_STORE_FILE")
        val storePassword = System.getenv("SIGNING_STORE_PASSWORD")
        val keyAlias = System.getenv("SIGNING_KEY_ALIAS")
        val keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        if (!storeFilePath.isNullOrBlank() &&
            !storePassword.isNullOrBlank() &&
            !keyAlias.isNullOrBlank() &&
            !keyPassword.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(storeFilePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Required in CI via SIGNING_* env vars (see scripts/create-release-keystore.*).
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.dadb)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
