import com.android.build.api.artifact.SingleArtifact
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

abstract class GenerateApkSha256 : DefaultTask() {
    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Internal
    abstract val apkDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val apkContents: FileTree
        get() = apkDirectory.asFileTree.matching {
            exclude("openchat.apk.sha256")
        }

    @get:OutputDirectory
    abstract val exportDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    val apkFile: Provider<RegularFile>
        get() = apkDirectory.file("openchat.apk")

    @get:OutputFile
    val checksumFile: Provider<RegularFile>
        get() = apkDirectory.file("openchat.apk.sha256")

    @TaskAction
    fun generate() {
        val apk = apkFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        checksumFile.get().asFile.writeText("$hash  ${apk.name}\n", Charsets.UTF_8)
        fileSystemOperations.sync {
            from(apkDirectory)
            into(exportDirectory)
        }
        logger.lifecycle("SHA-256: $hash  ${apk.name}")
    }
}

android {
    namespace = "com.im.openchat"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.im.openchat"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("openchat-signing/openchat-release.jks")
            keyAlias = "openchat"
            val localPassword = providers.fileContents(
                rootProject.layout.projectDirectory.file("openchat-signing/password.txt"),
            ).asText.map { it.trimEnd('\r', '\n') }
            // Keep credentials local; an absent password must not produce an unsigned release.
            storePassword = providers.environmentVariable("OPENCHAT_STORE_PASSWORD")
                .orElse(localPassword).orElse("").get()
            keyPassword = providers.environmentVariable("OPENCHAT_KEY_PASSWORD")
                .orElse(storePassword.orEmpty()).get()
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            ndk {
                abiFilters += "arm64-v8a"
            }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            // 移除默认父目录，仅将业务分组目录注册为资源根目录。
            res.directories.clear()
            res.directories.addAll(
                listOf(
                    "src/main/res/common",
                    "src/main/res/chat",
                    "src/main/res/address",
                    "src/main/res/login",
                    "src/main/res/me",
                ),
            )
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        check(variant.outputs.size == 1) {
            "The fixed openchat.apk filename requires a single APK per variant."
        }
        // AGP 9.5 exposes APK naming through this incubating public API.
        @Suppress("UnstableApiUsage")
        variant.outputs.single().outputFileName.set("openchat.apk")
        val exportDirectory = rootProject.layout.projectDirectory.dir("build/${variant.name}")
        val checksumTask = tasks.register<GenerateApkSha256>(
            "generate${variant.name.replaceFirstChar { it.uppercaseChar() }}ApkSha256",
        ) {
            description = "Generates the SHA-256 checksum for the ${variant.name} APK."
            this.exportDirectory.set(exportDirectory)
        }
        variant.artifacts.use(checksumTask)
            .wiredWith(GenerateApkSha256::apkDirectory)
            .toListenTo(SingleArtifact.APK)
        tasks.named<Delete>("clean") {
            delete(exportDirectory)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.livekit.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.ffmpeg.kit)
    // implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.android.gif.drawable)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.android.debug.database)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
