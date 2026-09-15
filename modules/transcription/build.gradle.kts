import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

abstract class PrepareVoskCzechModelTask : DefaultTask() {
    @get:Input
    abstract val modelUrl: Property<String>

    @get:Input
    abstract val modelSha256: Property<String>

    @get:LocalState
    abstract val archiveFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val expectedSha256 = modelSha256.get()
        val archive = archiveFile.get().asFile
        archive.parentFile.mkdirs()
        if (!archive.isFile || archive.sha256() != expectedSha256) {
            val temporaryArchive = File(archive.parentFile, "${archive.name}.download")
            temporaryArchive.delete()
            URI(modelUrl.get()).toURL().openStream().buffered().use { input ->
                temporaryArchive.outputStream().buffered().use(input::copyTo)
            }
            check(temporaryArchive.sha256() == expectedSha256) {
                "Stažený český model Vosk nemá očekávaný SHA-256."
            }
            check(temporaryArchive.renameTo(archive)) {
                "Stažený český model Vosk nelze uložit do ${archive.absolutePath}."
            }
        }

        val assetsDirectory = outputDirectory.get().asFile
        val modelDirectory = File(assetsDirectory, "model-cs")
        assetsDirectory.deleteRecursively()
        modelDirectory.deleteRecursively()
        check(modelDirectory.mkdirs()) {
            "Nelze vytvořit adresář ${modelDirectory.absolutePath}."
        }
        val expectedPrefix = "vosk-model-small-cs-0.4-rhasspy/"
        val safeRoot = modelDirectory.canonicalPath + File.separator

        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relativePath = entry.name.removePrefix(expectedPrefix)
                if (relativePath.isBlank()) continue
                val output = File(modelDirectory, relativePath)
                check(output.canonicalPath.startsWith(safeRoot)) {
                    "Neplatná cesta v archivu modelu: ${entry.name}"
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile.mkdirs()
                    output.outputStream().buffered().use(zip::copyTo)
                }
                zip.closeEntry()
            }
        }
        File(modelDirectory, "uuid").writeText("$expectedSha256\n")
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val prepareVoskCzechModel = tasks.register<PrepareVoskCzechModelTask>(
    "prepareVoskCzechModel",
) {
    group = "build setup"
    description = "Stáhne a připraví kontrolovaný český offline model Vosk."
    modelUrl.set("https://alphacephei.com/vosk/models/vosk-model-small-cs-0.4-rhasspy.zip")
    modelSha256.set("287c3bbefc8ad67b4ab9636eecef3d62acc3719990777d03e226db5a7f19fbda")
    archiveFile.set(
        rootProject.layout.projectDirectory.file(
            ".gradle/vosk-models/vosk-model-small-cs-0.4-rhasspy.zip",
        ),
    )
    outputDirectory.set(rootProject.layout.projectDirectory.dir(".gradle/vosk-assets"))
}

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.suku.rokidglass.transcription"
    compileSdk = 36

    defaultConfig {
        minSdk = 31

        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            prepareVoskCzechModel,
            PrepareVoskCzechModelTask::outputDirectory,
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    api("com.alphacephei:vosk-android:0.3.75")
    testImplementation("junit:junit:4.13.2")
}
