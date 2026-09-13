import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing credentials: a local, gitignored keystore.properties file for developer
// machines, falling back to environment variables (RELEASE_STORE_FILE/RELEASE_STORE_PASSWORD/
// RELEASE_KEY_ALIAS/RELEASE_KEY_PASSWORD) for CI, which supplies them as secrets instead of a
// committed file — see .github/workflows/release.yml and keystore.properties.example. Never
// hardcode real credentials here; this file IS committed.
val keystoreProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.exists()) {
        FileInputStream(propertiesFile).use { load(it) }
    }
}

fun releaseSigningProperty(propertiesKey: String, envVar: String): String? =
    keystoreProperties.getProperty(propertiesKey) ?: System.getenv(envVar)

android {
    namespace = "com.futurepath.actionbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.futurepath.actionbox"
        minSdk = 26
        targetSdk = 35
        // First Play Store submission.
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Resolved against the PROJECT ROOT (ActionBox/), not this app/ module directory —
            // matches keystore.properties.example's documented convention and where
            // keystore.properties itself is read from above.
            releaseSigningProperty("storeFile", "RELEASE_STORE_FILE")?.let { storeFile = rootProject.file(it) }
            storePassword = releaseSigningProperty("storePassword", "RELEASE_STORE_PASSWORD")
            keyAlias = releaseSigningProperty("keyAlias", "RELEASE_KEY_ALIAS")
            keyPassword = releaseSigningProperty("keyPassword", "RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // Google's published TEST AdMob App ID/ad unit ID — a debug build must never
            // request real ads (AdMob policy treats a developer's own test-device ad requests
            // as invalid traffic). See BannerAdView.kt for where ADMOB_BANNER_AD_UNIT_ID is
            // consumed.
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "ADMOB_BANNER_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["admobAppId"] = "ca-app-pub-9078149015707411~5589055329"
            buildConfigField("String", "ADMOB_BANNER_AD_UNIT_ID", "\"ca-app-pub-9078149015707411/5471198929\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // .tflite must stay uncompressed in the APK so Interpreter can mmap() it directly
        // from assets rather than needing to copy/inflate it to a temp file at runtime.
        noCompress += "tflite"
    }

    testOptions {
        unitTests {
            // Needed for Robolectric (see RecoveryScreenSwipeBackgroundTest) to resolve theme/
            // resource references while rendering Compose content on the JVM.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    // Per-app language preference (Settings -> Language, see data/AppLanguage.kt) via
    // AppCompatDelegate.setApplicationLocales() — the officially recommended API for this even
    // in a Compose-only app with no AppCompatActivity in sight: on API 33+ it delegates to the
    // system LocaleManager, and on API 26-32 (this app's minSdk is 26) it persists the choice
    // itself in a private SharedPreferences file (via a ContentProvider/manifest entry this
    // dependency merges in automatically) and re-applies it on next launch — no custom
    // DataStore plumbing needed the way ThemeMode required.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // Snooze icon for the swipe-left gesture background (see
    // ui/components/SwipeableNotificationCard.kt) isn't in material-icons-core's smaller
    // curated set.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Settings persistence (retention plan, correction-learning toggle) — see
    // data/SettingsRepository.kt.
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Scheduled background work for the daily digest and WAITING follow-up nudges — see
    // reminders/ReminderScheduler.kt.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Home screen widget (Pro-exclusive) — see widget/ActionBoxWidget.kt. Chosen over classic
    // AppWidgetProvider/RemoteViews since it lets the widget be written as Compose-style
    // composables, matching the rest of this app's UI layer.
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // Free-tier banner ads — see ui/ads/BannerAdView.kt.
    implementation("com.google.android.gms:play-services-ads:23.3.0")

    // Pro subscription purchase flow — see billing/BillingRepository.kt.
    implementation("com.android.billingclient:billing-ktx:7.1.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // On-device ML classification (see ml/TfliteNotificationClassifier.kt). Raw Interpreter
    // API only, not the Task/Support Library — the model takes a plain fixed-size float
    // vector rather than raw text needing built-in tokenization, so those extra helpers would
    // just be unused footprint.
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Robolectric: lets RecoveryScreenSwipeBackgroundTest render real Compose UI (including
    // pixel-level screenshot assertions) as a fast JVM unit test instead of needing a connected
    // device/emulator — this repo has no instrumented-test infra otherwise.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
