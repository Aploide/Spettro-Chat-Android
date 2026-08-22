import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// The Clerk publishable key is instance-specific and deliberately kept out of
// the repository. Provide it via local.properties (spettro.clerk.publishableKey),
// a Gradle property, or the SPETTRO_CLERK_PUBLISHABLE_KEY environment variable.
val clerkPublishableKey: String = run {
    val fromLocal = rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.let { file ->
            Properties()
                .apply { file.inputStream().use { load(it) } }
                .getProperty("spettro.clerk.publishableKey")
        }
    (fromLocal
        ?: providers.gradleProperty("SPETTRO_CLERK_PUBLISHABLE_KEY").orNull
        ?: System.getenv("SPETTRO_CLERK_PUBLISHABLE_KEY")
        ?: "").trim()
}

android {
    namespace = "to.eyed.spettro.chat"
    compileSdk = 37

    defaultConfig {
        applicationId = "to.eyed.spettro.chat"
        minSdk = 29
        targetSdk = 37
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "CLERK_PUBLISHABLE_KEY", "\"$clerkPublishableKey\"")
    }

    buildTypes {
        release {
            // R8 code + resource shrinking. Room and kotlinx.serialization ship
            // their own consumer keep-rules; project extras live in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // The Clerk SDK requires Java 17.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    constraints {
        // pdfbox-android pins BouncyCastle 1.72, which has open CVEs (GOST CTR
        // keystream reuse, LDAP injection, timing channel). Force the patched line.
        implementation("org.bouncycastle:bcprov-jdk15to18:1.85.2")
        implementation("org.bouncycastle:bcpkix-jdk15to18:1.85")
        implementation("org.bouncycastle:bcutil-jdk15to18:1.85.1")
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.clerk.android.api)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.pdfbox.android)
    implementation(libs.icons.lucide)
    // Sandboxed JS engine behind the run-javascript tool: pure JVM, no native
    // libs, interpreted mode only on Android (no runtime bytecode generation).
    implementation(libs.rhino)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}