plugins {
    id("com.android.application") version "8.11.2" apply false
    // Bumped from 2.0.21 alongside the Billing Library 8.3.0 upgrade: that AAR's Kotlin metadata
    // is version 2.2.0, which the 2.0.x compiler can't read (kspDebugKotlin failed with
    // "compiled with an incompatible version of Kotlin" until this bump).
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}
