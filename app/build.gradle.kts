import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

abstract class EmbedDeviceApkTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val deviceApk: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun embed() {
        val apk = deviceApk.orNull?.asFile ?: throw GradleException("Device APK property is not set")
        if (!apk.exists()) {
            throw GradleException("Device APK not found at ${apk.absolutePath}. Make sure the :device project is built correctly.")
        }
        val outputFile = outputDirectory.file("rokid-glass-device.apk").get().asFile
        outputFile.parentFile.mkdirs()
        apk.copyTo(outputFile, overwrite = true)
    }
}

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
    description = "Ověří lokální konfiguraci release podpisu."
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
    namespace = "cz.suku.rokidglass"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.suku.rokidglass"
        minSdk = 31
        targetSdk = 36
        versionCode = 11
        versionName = "1.10"
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

androidComponents {
    onVariants(selector().all()) { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val deviceBuildType = if (variant.buildType == "release") "release" else "debug"
        val deviceBuildTypeName = deviceBuildType.replaceFirstChar { it.uppercase() }
        val embedTask = tasks.register<EmbedDeviceApkTask>(
            "embedDevice${variantName}Apk",
        ) {
            group = "build"
            description = "Sestaví $deviceBuildType device APK a vloží ji do ${variant.name} assets."
            val deviceProject = project(":device")
            val assembleTask = deviceProject.tasks.named("assemble$deviceBuildTypeName")
            dependsOn(assembleTask)
            deviceApk.set(
                assembleTask.flatMap {
                    deviceProject.layout.buildDirectory.file(
                        "outputs/apk/$deviceBuildType/device-$deviceBuildType.apk",
                    )
                }
            )
            outputDirectory.set(
                layout.buildDirectory.dir("generated/device-apk/${variant.name}/assets"),
            )
        }

        variant.sources.assets?.addGeneratedSourceDirectory(
            embedTask,
            EmbedDeviceApkTask::outputDirectory,
        )
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.rokid.cxr:client-l:1.1.1")
    implementation(project(":modules:glasses-platform"))
    implementation(project(":modules:products"))
    implementation(project(":modules:transcription"))
}
