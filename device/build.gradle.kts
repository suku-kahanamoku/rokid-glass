import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val releaseSigningFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningFile.isFile) {
        releaseSigningFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(property: String, environment: String): String? =
    releaseSigningProperties.getProperty(property)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: System.getenv(environment)?.trim()?.takeIf(String::isNotEmpty)

val releaseStorePath = releaseSigningValue("storeFile", "ROKID_UPLOAD_STORE_FILE")
val releaseStorePassword = releaseSigningValue("storePassword", "ROKID_UPLOAD_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "ROKID_UPLOAD_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "ROKID_UPLOAD_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Ověří lokální konfiguraci release podpisu device APK."
    doLast {
        check(releaseSigningConfigured) {
            "Doplň storeFile, storePassword, keyAlias a keyPassword do " +
                "necommitovaného keystore.properties nebo proměnných ROKID_UPLOAD_* ."
        }
        check(rootProject.file(requireNotNull(releaseStorePath)).isFile) {
            "Release keystore neexistuje: $releaseStorePath"
        }
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.suku.rokidglass.device"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.suku.rokidglass.device"
        minSdk = 31
        targetSdk = 36
        versionCode = 18
        versionName = "1.17"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = rootProject.file(requireNotNull(releaseStorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation(project(":modules:glasses-platform"))
}
