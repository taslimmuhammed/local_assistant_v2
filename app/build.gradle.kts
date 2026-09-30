plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * `-Pdemo`: the build checked into the repository for people to try (apk/README.md). Phones
 * only, native code compressed to keep the file small, and signed with this machine's debug key
 * so it installs without a keystore. Without it the release build is unsigned, as Play wants.
 * Build and copy it with `./gradlew :app:demoApk -Pdemo`.
 */
val demo = providers.gradleProperty("demo").isPresent

android {
    namespace = "com.local.assistant"
    compileSdk = 36
    // r28 links 16 KB-aligned by default; sqlite-vec is the app's only own native code.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.local.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // LiteRT-LM ships native code for these two ABIs only; x86_64 is for emulators, which the
        // demo build leaves out (26 MB).
        ndk { abiFilters += if (demo) listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // sqlite-vec, loaded into the bundled SQLite as an extension (see VecExtension).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // MigrationTestHelper reads the exported schemas from the test APK's assets.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    buildTypes {
        release {
            if (demo) signingConfig = signingConfigs.getByName("debug")
            // Not minified: tool results are read with Gson by reflection, and a shrunk build
            // would need keep rules checked on a device before anyone downloads it.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    // EmbeddingGemma, fetched by Play right after install (see embedder_pack/README.md).
    assetPacks += listOf(":embedder_pack")

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        // Compressed in the demo APK (22 MB of LiteRT-LM smaller in the repository); extracted
        // at install. Otherwise stored as usual, uncompressed and loaded in place.
        jniLibs.useLegacyPackaging = demo
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The demo build, where the README links to it: apk/LocalAssistant.apk.
tasks.register<Copy>("demoApk") {
    val isDemo = demo // a plain value: the configuration cache can't keep the script itself
    dependsOn("assembleRelease")
    doFirst { check(isDemo) { "Build it with -Pdemo: ./gradlew :app:demoApk -Pdemo" } }
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("apk"))
    rename { "LocalAssistant.apk" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Room's driver API: our own SQLite build, which can load extensions (sqlite-vec, Phase 3).
    implementation(libs.androidx.sqlite.bundled)

    implementation(libs.okhttp)

    // Session-end summaries and the nightly consolidation run as scheduled background work.
    implementation(libs.androidx.work.runtime.ktx)

    // Where Play put the embedder pack, and asking for it when it hasn't arrived.
    implementation(libs.play.asset.delivery.ktx)

    // On-device LLM runtime.
    implementation(libs.litertlm.android)
    // Already a transitive dependency of LiteRT-LM; declared because tool arguments and results
    // are parsed with it. org.json would be stubbed out in JVM unit tests.
    implementation(libs.gson)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)

    constraints {
        // Lifecycle pulls in 1.7.3, and the test APK is pinned to the app's versions; Room's
        // schema reader (used by MigrationTestHelper) is built against 1.8 and fails on 1.7.
        implementation(libs.kotlinx.serialization.core)
    }
}

// Installs the debug app bundle the way Play would, embedder pack included, on the connected
// phone: bundletool's local testing mode copies fast-follow packs to the device, and the Play
// Asset Delivery library hands them over as if Play had. An APK from assembleDebug has no packs.
//     ./gradlew :app:installBundleDebug
val bundletool: Configuration by configurations.creating
dependencies { bundletool(libs.bundletool) }

val adb = android.sdkDirectory.resolve("platform-tools/adb").path
val debugBundle = layout.buildDirectory.file("outputs/bundle/debug/app-debug.aab")
val debugApks = layout.buildDirectory.file("outputs/bundle/debug/app-debug.apks")

val buildApksDebug by tasks.registering(JavaExec::class) {
    dependsOn("bundleDebug")
    classpath = bundletool
    mainClass.set("com.android.tools.build.bundletool.BundleToolMain")
    args(
        "build-apks", "--local-testing", "--connected-device", "--overwrite", "--adb=$adb",
        "--bundle=${debugBundle.get().asFile}", "--output=${debugApks.get().asFile}",
    )
}

tasks.register<JavaExec>("installBundleDebug") {
    dependsOn(buildApksDebug)
    classpath = bundletool
    mainClass.set("com.android.tools.build.bundletool.BundleToolMain")
    args("install-apks", "--adb=$adb", "--apks=${debugApks.get().asFile}")
}
