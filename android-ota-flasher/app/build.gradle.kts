import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localOrEnv(name: String): String {
    val value = localProperties.getProperty(name) ?: System.getenv(name) ?: ""
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
}

val movesenseJniDir = layout.buildDirectory.dir("generated/movesenseJni")

android {
    namespace = "cz.adamkruzela.core2ota"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.adamkruzela.core2ota"
        minSdk = 31
        targetSdk = 35
        versionCode = 46
        versionName = "0.4.4"
        // Set in local.properties or the environment. Never commit the values.
        buildConfigField("String", "SUUNTO_APPKEY", "\"${localOrEnv("SUUNTO_APPKEY")}\"")
        buildConfigField(
            "String",
            "CATALOG_BASE",
            "\"https://devices.suunto-operations.com\"",
        )
        buildConfigField(
            "String",
            "MODIFIED_MIRROR_FOLDER_ID",
            "\"${localOrEnv("MODIFIED_MIRROR_FOLDER_ID")}\"",
        )
        buildConfigField(
            "String",
            "MODIFIED_INDEX_FILE_ID",
            "\"${localOrEnv("MODIFIED_INDEX_FILE_ID")}\"",
        )
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            useLegacyPackaging = false
            // The AAR's libmds.so is repackaged as libmds_movesense.so (see
            // extractMovesenseMds) so MdsEngine decides which engine gets loaded.
            excludes += "**/libmds.so"
        }
    }
    sourceSets["main"].jniLibs.srcDir(movesenseJniDir)
}

val extractMovesenseMds by tasks.registering(Copy::class) {
    from(zipTree("libs/mdslib-3.33.7-release.aar")) {
        include("jni/arm64-v8a/libmds.so", "jni/armeabi-v7a/libmds.so")
        eachFile {
            relativePath = org.gradle.api.file.RelativePath(true, relativePath.segments[1], "libmds_movesense.so")
        }
    }
    includeEmptyDirs = false
    into(movesenseJniDir)
}

tasks.named("preBuild") { dependsOn(extractMovesenseMds) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Movesense MDS Java layer; the native engine is chosen at runtime by MdsEngine.
    implementation(files("libs/mdslib-3.33.7-release.aar"))
    // Same mcumgr DFU flow the Suunto app runs for the Bluetooth (type 3) image.
    // 2.2.2 is the newest release built against Kotlin 2.0.21 / coroutines 1.9.
    implementation("no.nordicsemi.android:mcumgr-ble:2.2.2")
    implementation("com.polidea.rxandroidble2:rxandroidble:1.10.2")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")
    implementation("io.reactivex.rxjava2:rxjava:2.2.8")

    testImplementation("junit:junit:4.13.2")
    // android.jar only ships org.json stubs; package checks parse JSON in JVM tests.
    testImplementation("org.json:json:20240303")
}

plugins.withId("org.jetbrains.kotlin.android") {
    // kotlinx.serialization plugin optional — use org.json for simplicity
}
