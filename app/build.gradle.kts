import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is supplied out-of-band: local.properties (git-ignored) or env.
// Keys: yukaribox.keystore.{file,password,keyAlias,keyPassword} /
// YUKARIBOX_KEYSTORE_{FILE,PASSWORD,KEY_ALIAS,KEY_PASSWORD}.
// When absent, assembleRelease stays unsigned (same as before).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    localProps.getProperty(propKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("yukaribox.keystore.file", "YUKARIBOX_KEYSTORE_FILE")
val releaseStorePassword = signingValue("yukaribox.keystore.password", "YUKARIBOX_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("yukaribox.keystore.keyAlias", "YUKARIBOX_KEY_ALIAS")
val releaseKeyPassword = signingValue("yukaribox.keystore.keyPassword", "YUKARIBOX_KEY_PASSWORD")
    ?: releaseStorePassword

android {
    namespace = "dev.yukaribox.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.yukaribox.vpn"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // local.properties is machine-generated and git-ignored; its escaping is not our concern.
        disable += "PropertyEscape"
        abortOnError = true
        checkReleaseBuilds = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Reproducible builds (F-Droid). The Google dependency-metadata block AGP embeds
    // in the APK is signed and varies per build environment, so it breaks bit-for-bit
    // reproducibility (and F-Droid strips it anyway). Drop it so identical source +
    // toolchain yields a byte-identical release APK. AGP already produces deterministic
    // zip entries (sorted, fixed timestamps); the prebuilt arm64 libcore .so ships as-is.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Compose compiler diagnostics, off unless asked for: `./gradlew assembleDebug -PcomposeMetrics`.
//
// The reports are the only way to answer "is this composable skippable, and is that
// parameter stable" without guessing. They are gated behind a property rather than left on
// because the compiler writes a file per module per variant and that is compile time spent
// on every ordinary build.
//
// What to read afterwards, under `app/build/compose-reports/`:
//   *-classes.txt    — stability of every class the UI touches (`stable`/`unstable` per field)
//   *-composables.txt — `restartable`/`skippable` per composable, and which params block it
//   *-module.json    — the counts, useful as a before/after number
//
// Nothing here changes the emitted code, so a build with and without the flag ships the
// same APK.
composeCompiler {
    if (providers.gradleProperty("composeMetrics").isPresent) {
        metricsDestination = layout.buildDirectory.dir("compose-metrics")
        reportsDestination = layout.buildDirectory.dir("compose-reports")
    }
}

// Static analysis. The detekt Gradle plugin does not yet support AGP 9's DSL
// (detekt#8981), so we run the standalone CLI through JavaExec instead — fully
// decoupled from AGP/Gradle versions. Gate: ./gradlew detekt
val detektCli: Configuration by configurations.creating

// Every Kotlin source set, not just main: the debug set holds AdbControlReceiver,
// which drives the whole app headlessly, and the test set holds the assertions the
// gates rest on. Leaving either outside the gate meant a rule could be broken in
// exactly the code that is hardest to review by eye.
val detektSources = listOf("src/main/kotlin", "src/debug/kotlin", "src/test/kotlin")

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Runs detekt static analysis over the main, debug and test Kotlin sources."
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    classpath = detektCli
    val configFile = rootProject.file("config/detekt/detekt.yml")
    val baselineFile = rootProject.file("config/detekt/baseline.xml")
    val sources = detektSources.map { file(it) }.filter { it.isDirectory }
    sources.forEach { inputs.dir(it) }
    inputs.file(configFile)
    if (baselineFile.exists()) inputs.file(baselineFile)
    args(
        "--input", sources.joinToString(",") { it.absolutePath },
        "--config", configFile.absolutePath,
        "--build-upon-default-config",
        "--jvm-target", "17",
    )
    if (baselineFile.exists()) {
        args("--baseline", baselineFile.absolutePath)
    }
}

// Regenerates config/detekt/baseline.xml from the current findings. Run manually
// (./gradlew detektBaseline) when intentionally accepting pre-existing issues;
// the `detekt` gate then fails only on issues introduced after the baseline.
tasks.register<JavaExec>("detektBaseline") {
    group = "verification"
    description = "Creates/updates the detekt baseline from current findings."
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    classpath = detektCli
    val configFile = rootProject.file("config/detekt/detekt.yml")
    val baselineFile = rootProject.file("config/detekt/baseline.xml")
    val sources = detektSources.map { file(it) }.filter { it.isDirectory }
    args(
        "--input", sources.joinToString(",") { it.absolutePath },
        "--config", configFile.absolutePath,
        "--build-upon-default-config",
        "--jvm-target", "17",
        "--create-baseline",
        "--baseline", baselineFile.absolutePath,
    )
}

dependencies {
    detektCli(libs.detekt.cli)

    implementation(files("libs/libcore.aar"))

    implementation(libs.kotlinx.serialization.json)
    // QR encode/decode from bitmaps (no camera) — pure-Java ZXing core.
    implementation("com.google.zxing:core:3.5.4")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
