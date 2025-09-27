plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.loveito.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.loveito.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Firebase (BASE) con BoM actualizado
    implementation(platform("com.google.firebase:firebase-bom:34.2.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-functions")

    // AndroidX actualizado
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.3")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")

    // Para corregir orientación EXIF de imágenes
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    // Glide actualizado (sin compiler ni ksp, solo si no usas @GlideModule)
    implementation("com.github.bumptech.glide:glide:5.0.4")

    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Animaciones de chips
    implementation("androidx.transition:transition:1.5.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // JSON parsing para motor de flujo
    implementation("com.google.code.gson:gson:2.11.0")

    // Test
    testImplementation("junit:junit:4.13.2")
}
