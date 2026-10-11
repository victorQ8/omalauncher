import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Play upload key, from the gitignored keystore.properties at the repo root.
val uploadKey = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

val commitCount =
    providers
        .exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim().toIntOrNull() ?: 1 }

android {
    namespace = "dev.omalauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.omalauncher"
        minSdk = 29
        targetSdk = 37
        versionCode = 9
        versionName = "0.4.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    // Two apps from one source: the launcher, and its terminal on its own (src/terminal), which shares
    // everything the terminal is made of (proot, the compositor, foot's setup, themes, the extra keys).
    flavorDimensions += "app"
    productFlavors {
        create("launcher") {
            dimension = "app"
            isDefault = true
            buildConfigField("boolean", "TERMINAL_ONLY", "false")
        }
        create("terminal") {
            dimension = "app"
            applicationId = "dev.omalauncher.terminal"
            // The commit count, so a build from a newer commit always installs over the last one.
            versionCode = commitCount.get()
            buildConfigField("boolean", "TERMINAL_ONLY", "true")
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    packaging {
        jniLibs {
            // Extract .so files to nativeLibraryDir: proot is exec'd from there.
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libproot.so", "**/libproot-loader.so")
        }
    }

    // android.util.Log and friends are no-ops in JVM tests instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }

    signingConfigs {
        create("release") {
            uploadKey?.let {
                storeFile = rootProject.file(it.getProperty("storeFile"))
                storePassword = it.getProperty("storePassword")
                keyAlias = it.getProperty("keyAlias")
                keyPassword = it.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Shrinks the APK and drops what the app doesn't use, like the owl module's unused parts.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (uploadKey != null) "release" else "debug")
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose-stability.conf"))
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.commons.compress)
    implementation(libs.androidsvg)
    implementation(libs.zxing.core)
    implementation(project(":owl"))
    testImplementation(libs.junit)
    // android.jar only has stubs of org.json.
    testImplementation(libs.org.json)
}
