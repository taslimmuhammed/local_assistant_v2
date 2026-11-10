import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * `-Pdemo`: the build checked into the repository for people to try (apk/README.md). Phones
 * only, native code compressed to keep the file small, and signed with this machine's debug key
 * so it installs without a keystore. Without it the release build is signed with the upload key
 * below. Build and copy it with `./gradlew :app:demoApk -Pdemo`.
 */
val demo = providers.gradleProperty("demo").isPresent

/**
 * The upload key Play checks each bundle against, read from `keystore.properties` at the project
 * root: storeFile, storePassword, keyAlias, keyPassword (see keystore.properties.example). The
 * file and the key stay out of git. Without them the release build is unsigned, which Play
 * refuses, so `bundleRelease` still builds for checking but can't be uploaded.
 */
val uploadKey = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

/** Downloads one file once, and fails the build unless it has the expected SHA-256. */
abstract class FetchVerifiedFile : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun fetch() {
        val file = output.get().asFile
        if (file.isFile && sha256Of(file) == sha256.get()) return
        val part = File(file.path + ".part")
        URI(url.get()).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val actual = sha256Of(part)
        if (actual != sha256.get()) {
            part.delete()
            throw GradleException("${url.get()} has SHA-256 $actual, expected ${sha256.get()}")
        }
        file.delete()
        check(part.renameTo(file)) { "Could not move ${part.name} into place" }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

// sherpa-onnx publishes its Android library only as a release asset, so the build fetches it
// (once, into build/) rather than keeping 39 MB of binary in git. Digest from the release page.
val sherpaOnnxVersion = "1.13.8"
val sherpaOnnxAar = layout.buildDirectory.file("sherpa-onnx/sherpa-onnx-static-link-onnxruntime-$sherpaOnnxVersion.aar")
val fetchSherpaOnnx = tasks.register<FetchVerifiedFile>("fetchSherpaOnnx") {
    url.set(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/" +
            "sherpa-onnx-static-link-onnxruntime-$sherpaOnnxVersion.aar",
    )
    sha256.set("b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471")
    output.set(sherpaOnnxAar)
}

android {
    namespace = "com.local.assistant"
    compileSdk = 36
    // r28 links 16 KB-aligned by default; sqlite-vec and the wake word spectrogram are the app's
    // own native code.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // The Play listing's name, fixed for good once uploaded. The code keeps its package
        // (namespace above); only the installed app's name changed, from com.local.assistant.
        applicationId = "com.taslim.localassistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.0"

        // LiteRT-LM ships native code for these two ABIs only; x86_64 is for emulators, which the
        // demo build leaves out (26 MB).
        ndk { abiFilters += if (demo) listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // sqlite-vec, loaded into the bundled SQLite as an extension (see VecExtension), and the wake
    // word model's spectrogram (see wake/MicroFrontend).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // MigrationTestHelper reads the exported schemas from the test APK's assets.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    signingConfigs {
        if (uploadKey != null) {
            create("upload") {
                storeFile = rootProject.file(uploadKey.getProperty("storeFile"))
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // A separate app beside the one from Play: Android won't install a debug-signed build
        // over a Play-signed one without uninstalling it, which would take its model and chats.
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            // The demo APK is signed with a debug key, so it gets its own name too: under Play's
            // name it would block installing the app from Play on the same phone.
            if (demo) applicationIdSuffix = ".demo"
            when {
                demo -> signingConfig = signingConfigs.getByName("debug")
                uploadKey != null -> signingConfig = signingConfigs.getByName("upload")
            }
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The face models are memory-mapped straight from the APK, which needs them stored as is.
    androidResources {
        noCompress += "tflite"
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

/**
 * Writes res/xml/shortcuts.xml from src/main/shortcuts.xml with the variant's applicationId in
 * it: a shortcut's intent has to name its package literally. Resources can't take the manifest's
 * ${applicationId}, and Android doesn't resolve a @string there (the shortcut pointed at
 * "@2131623967").
 */
abstract class ShortcutsXml : DefaultTask() {
    @get:InputFile abstract val template: RegularFileProperty
    @get:Input abstract val applicationId: Property<String>
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun write() {
        val xml = output.get().dir("xml").asFile.apply { mkdirs() }
        File(xml, "shortcuts.xml").writeText(template.get().asFile.readText().replace("\${applicationId}", applicationId.get()))
    }
}

androidComponents {
    onVariants { variant ->
        val shortcuts = tasks.register<ShortcutsXml>("${variant.name}ShortcutsXml") {
            template.set(layout.projectDirectory.file("src/main/shortcuts.xml"))
            applicationId.set(variant.applicationId)
        }
        variant.sources.res?.addGeneratedSourceDirectory(shortcuts, ShortcutsXml::output)
    }
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
    // Fingerprint, face or the phone's PIN before sensitive details are shown.
    implementation(libs.androidx.biometric)
    // MainActivity is a FragmentActivity (for the prompt): see the catalog's note on the version.
    implementation(libs.androidx.fragment)
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

    // A place reminder's map: its pin and circle, shown and moved in the Reminders editor.
    implementation(libs.maplibre.android)

    // Session-end summaries and the nightly consolidation run as scheduled background work.
    implementation(libs.androidx.work.runtime.ktx)

    // On-device LLM runtime.
    implementation(libs.litertlm.android)
    // TensorFlow Lite's interpreter, for the two small face models (gallery/faces): 5.6 MB of
    // native code, against about 34 MB for ONNX Runtime or OpenCV.
    implementation(libs.litert)
    // Already a transitive dependency of LiteRT-LM; declared because tool arguments and results
    // are parsed with it. org.json would be stubbed out in JVM unit tests.
    implementation(libs.gson)

    // Steps, heart rate, sleep and workouts the phone's health apps keep in Health Connect, read
    // only and only when asked (health/). Built into Android 14 and later.
    implementation(libs.androidx.health.connect)

    // The natural voice (voice/NaturalVoice): Kokoro run by sherpa-onnx, with ONNX Runtime linked
    // into its one native library (24 MB on arm64). 1.13.8 is the last release with eSpeak NG
    // built in; 2.0 drops it and silently skips numbers and names. Not on Maven: see fetchSherpaOnnx.
    implementation(files(sherpaOnnxAar).builtBy(fetchSherpaOnnx))
    // The voice is published as a .tar.bz2, which Android can't unpack on its own.
    implementation(libs.commons.compress)

    // Place reminders ring on arrival through Play Services' geofences: watched on the phone with
    // the least power. Phones without Play Services use Android's proximity alerts instead.
    implementation(libs.play.services.location)

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
