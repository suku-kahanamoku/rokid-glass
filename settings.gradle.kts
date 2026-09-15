pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "rokid-glass"
include(":app")
include(":device")
include(":modules:glasses-platform")
include(":modules:assistant")
include(":modules:device-ui")
include(":modules:products")
include(":modules:transcription")
