// Top-level build file where you can add configuration options common to all sub-projects/modules.

// AGP 9's built-in Kotlin bundles KGP 2.2.x; the Clerk SDK ships Kotlin 2.4
// metadata, so force a newer KGP onto the build classpath (the supported way
// to override the built-in Kotlin version).
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}