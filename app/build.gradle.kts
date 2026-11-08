import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing material comes from the environment (CI) or from
 * `keystore.properties` at the repo root (local, git-ignored).
 * When neither is present the release APK is signed with the debug key, so
 * `assembleRelease` always yields an installable file instead of failing.
 */
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(envName: String, propName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(propName)

val releaseKeyStorePath = signingValue("HELUX_KEYSTORE_FILE", "storeFile")
val releaseKeyStorePassword = signingValue("HELUX_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("HELUX_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("HELUX_KEY_PASSWORD", "keyPassword")
val releaseKeyStore = releaseKeyStorePath?.let { rootProject.file(it) }
val hasReleaseKey = releaseKeyStore != null && releaseKeyStore.isFile &&
    releaseKeyStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "id.helux.dsh"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.helux.dsh"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeyStore
                storePassword = releaseKeyStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // A signed release APK is always produced: real key when available,
            // debug key as a labelled fallback.
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        viewBinding = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // CameraX for the QR preview + analysis.
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // ZXing core only (no Google Play Services, fully offline decoding).
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    // Runs real Activities on the JVM so launch-time crashes are caught here
    // instead of on a phone.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
