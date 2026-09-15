import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.suku.rokidglass.assistant"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.rokid.cxr:client-l:1.1.1")
    implementation(project(":modules:glasses-platform"))
    implementation(project(":modules:products"))
    implementation(project(":modules:transcription"))
}
