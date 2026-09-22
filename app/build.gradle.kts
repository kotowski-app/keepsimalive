import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("dagger.hilt.android.plugin")
}

android {
    namespace = "app.kotowski.keepsimalive"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.kotowski.keepsimalive"
        minSdk = 24
        targetSdk = 36
        // Version encoding: Each version component is padded to 3 digits.
        // Formula: (Major * 1,000,000) + (Minor * 1,000) + Patch
        // Examples:
        //   - 0.7.22  -> (0 * 1,000,000) + (7 * 1,000) + 22 = 7022
        //   - 0.9     -> (0 * 1,000,000) + (9 * 1,000)      = 9000
        //   - 2.0.13  -> (2 * 1,000,000) + (0 * 1,000) + 13 = 2000013
        versionCode = 1000000
        versionName = "1.0.0"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("vanilla") {
            dimension = "distribution"
            applicationIdSuffix = ".vanilla"
        }
    }

    // Release signing (reproducible F-Droid builds):
    // - local/ is gitignored: the keystore and its passwords must never reach the public repo
    // - when the keystore is absent (F-Droid's build server) the release builds unsigned
    //   and F-Droid signs it with the developer key they hold for reproducible builds
    val releaseKeystore = file("$rootDir/local/release.keystore")
    val releaseKeystoreProps = file("$rootDir/local/release-keystore.properties")
    val hasReleaseKeystore = releaseKeystore.exists() && releaseKeystoreProps.exists()

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                val props = Properties()
                releaseKeystoreProps.inputStream().use(props::load)
                storeFile = releaseKeystore
                storePassword = props.getProperty("storePassword").orEmpty()
                keyAlias = props.getProperty("keyAlias").orEmpty()
                keyPassword = props.getProperty("keyPassword").orEmpty()
            }
        }
    }

    buildTypes {
        // Same key as release so debug and release installs can be swapped on a device
        // without uninstalling (Android matches update signatures; data survives the switch).
        debug {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    applicationVariants.all {
        outputs.forEach { output ->
            (output as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "${rootProject.name.lowercase()}-v$versionName.apk"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// The Compose UI tests need the ui-test-manifest activity declaration, which is merged only
// into the debug variant (debugImplementation dependency). In the release unit test variant
// Robolectric cannot resolve the test activity, so the UI and navigation packages are skipped
// there — the same tests run in full in the debug variant.
tasks.withType<Test>().configureEach {
    if (name.contains("ReleaseUnitTest")) {
        filter {
            excludeTestsMatching("app.kotowski.keepsimalive.ui.*")
            excludeTestsMatching("app.kotowski.keepsimalive.navigation.*")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    testImplementation("androidx.work:work-testing:2.9.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
