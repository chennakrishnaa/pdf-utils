plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.offlinepdf.utils"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.offlinepdf.utils"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    sourceSets {
        getByName("test") {
            java.directories += "src/sharedTest/java"
            kotlin.directories += "src/sharedTest/java"
        }
        getByName("androidTest") {
            java.directories += "src/sharedTest/java"
            kotlin.directories += "src/sharedTest/java"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            val muPdfLibDir = System.getenv("MUPDF_HOST_LIB_DIR")
                ?: "${System.getProperty("user.home")}/mupdf-host"
            test.maxParallelForks = 1
            test.maxHeapSize = "1536m"
            test.systemProperty("java.library.path", muPdfLibDir)
        }
    }

    lint {
        abortOnError = true
        // targetSdk 36 is a deliberate project decision (compileSdk is 37.1); not an oversight.
        disable += "OldTargetApi"
        // Tool versions are pinned on purpose in gradle/libs.versions.toml and the wrapper;
        // this network-dependent "newer version available" check makes lint non-deterministic.
        disable += "AndroidGradlePluginVersion"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.mupdf.fitz)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
