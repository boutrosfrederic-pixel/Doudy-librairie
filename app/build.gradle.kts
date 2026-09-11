import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
}

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) {
            file.inputStream().use(::load)
        }
    }

android {

    namespace = "com.doudy.librairie"

    compileSdk = 34

    defaultConfig {

        applicationId = "com.doudy.librairie"

        minSdk = 24
        targetSdk = 34

        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "GOOGLE_BOOKS_API_KEY",
            "\"${localProperties.getProperty("GOOGLE_BOOKS_API_KEY", "")}\""
        )
    }

    buildTypes {

        debug {
            isMinifyEnabled = false
        }

        release {

            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {

        sourceCompatibility =
            JavaVersion.VERSION_17

        targetCompatibility =
            JavaVersion.VERSION_17
    }

    kotlinOptions {

        jvmTarget = "17"
    }

    buildFeatures {

        compose = true
        buildConfig = true
    }

    composeOptions {

        kotlinCompilerExtensionVersion =
            "1.5.8"
    }
}

dependencies {

    // =====================================================
    // CORE
    // =====================================================

    implementation(
        "androidx.core:core-ktx:1.12.0"
    )

    implementation(
        "androidx.lifecycle:lifecycle-runtime-ktx:2.7.0"
    )

    implementation(
        "androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0"
    )

    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"
    )


    // =====================================================
    // COMPOSE
    // =====================================================

    implementation(
        platform(
            "androidx.compose:compose-bom:2024.02.00"
        )
    )

    implementation(
        "androidx.activity:activity-compose:1.8.2"
    )

    implementation(
        "androidx.compose.ui:ui"
    )

    implementation(
        "androidx.compose.material3:material3"
    )

    implementation(
        "androidx.compose.material:material-icons-extended"
    )

    implementation(
        "androidx.navigation:navigation-compose:2.7.7"
    )

    implementation(
        "androidx.compose.material3:material3-window-size-class"
    )


    // =====================================================
    // ROOM
    // =====================================================

    val roomVersion = "2.6.1"

    implementation(
        "androidx.room:room-runtime:$roomVersion"
    )

    implementation(
        "androidx.room:room-ktx:$roomVersion"
    )

    ksp(
        "androidx.room:room-compiler:$roomVersion"
    )


    // =====================================================
    // DATASTORE
    // =====================================================

    implementation(
        "androidx.datastore:datastore-preferences:1.1.1"
    )


    // =====================================================
    // CAMERAX
    // =====================================================

    implementation(
        "androidx.camera:camera-core:1.3.4"
    )

    implementation(
        "androidx.camera:camera-camera2:1.3.4"
    )

    implementation(
        "androidx.camera:camera-lifecycle:1.3.4"
    )

    implementation(
        "androidx.camera:camera-view:1.3.4"
    )


    // =====================================================
    // ML KIT
    // =====================================================

    implementation(
        "com.google.mlkit:barcode-scanning:17.2.0"
    )


    // =====================================================
    // COIL
    // =====================================================

    implementation(
        "io.coil-kt:coil-compose:2.5.0"
    )


    // =====================================================
    // RETROFIT
    // =====================================================

    implementation(
        "com.squareup.retrofit2:retrofit:2.11.0"
    )

    implementation(
        "com.squareup.retrofit2:converter-moshi:2.11.0"
    )

    implementation(
        "com.squareup.okhttp3:logging-interceptor:4.12.0"
    )
}
