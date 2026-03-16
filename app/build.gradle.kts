plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.stsprint"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.stsprint"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Use icon from app/img/icon-512x512.png when present (copyAppIcon task copies it)
        manifestPlaceholders["appIcon"] = if (file("img/icon-512x512.png").exists()) "@drawable/ic_app_icon" else "@mipmap/ic_launcher"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // In CI (e.g. GitHub Actions), allow unsigned release for artifact build.
            // For Play Store, add signingConfigs.release with your keystore.
            if (System.getenv("CI") == "true") {
                signingConfig = null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// Copy app/img/icon-512x512.png into res so the launcher uses it (single source in solution)
tasks.register("copyAppIcon") {
    doLast {
        val src = file("img/icon-512x512.png")
        if (src.exists()) {
            copy {
                from(src)
                into(file("src/main/res/drawable"))
                rename { "ic_app_icon.png" }
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn("copyAppIcon") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.nanohttpd)
}