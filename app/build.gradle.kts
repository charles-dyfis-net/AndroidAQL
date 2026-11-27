import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

abstract class ReleaseVersionCodeSource : ValueSource<Int, ReleaseVersionCodeSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val repoDir: DirectoryProperty
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    private fun runGit(repoDir: java.io.File, vararg args: String): Pair<Int, String> {
        val stdout = ByteArrayOutputStream()
        val result = runCatching {
            execOperations.exec {
                workingDir = repoDir
                commandLine("git", *args)
                standardOutput = stdout
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }
        }.getOrNull() ?: return -1 to ""
        return result.exitValue to stdout.toString(Charsets.UTF_8).trim()
    }

    override fun obtain(): Int {
        System.getenv("ANDROID_VERSION_CODE")?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let {
            return it
        }

        // Encode 20-second epoch buckets shifted left 1 bit plus bit 0 = 1 for clean exact-tag
        // builds (0 for untagged or dirty builds). Stays on the same ~179,5xx,xxx numeric scale
        // as legacy 10s-epoch codes while allowing an exact-tag rebuild over the same commit
        // (`tagged(X) == untagged(X) + 1`).
        val repoDir = parameters.repoDir.get().asFile
        val (diffRc, _) = runGit(repoDir, "diff-index", "--quiet", "HEAD", "--")
        if (diffRc == 0) {
            val (ctRc, ctOut) = runGit(repoDir, "show", "-s", "--format=%ct", "HEAD")
            val commitEpochSec = if (ctRc == 0) ctOut.toLongOrNull() else null
            if (commitEpochSec != null && commitEpochSec > 0L) {
                val (tagRc, _) = runGit(repoDir, "describe", "--exact-match", "--tags", "HEAD")
                val tagBit = if (tagRc == 0) 1L else 0L
                return ((commitEpochSec / 20L) * 2L + tagBit).toInt()
            }
        }

        val nowSec = System.currentTimeMillis() / 1_000L
        return ((nowSec / 20L) * 2L).toInt()
    }
}

abstract class GitDescribeVersionNameSource : ValueSource<String, GitDescribeVersionNameSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val repoDir: DirectoryProperty
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val stdout = ByteArrayOutputStream()
        val result = runCatching {
            execOperations.exec {
                workingDir = parameters.repoDir.get().asFile
                commandLine("git", "describe", "--tags", "--always", "--dirty")
                standardOutput = stdout
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }
        }.getOrNull() ?: return "0.0.0-unknown"

        if (result.exitValue != 0) {
            return "0.0.0-unknown"
        }

        return stdout.toString(Charsets.UTF_8).trim().removePrefix("v").ifEmpty { "0.0.0-unknown" }
    }
}

plugins {
    alias(libs.plugins.android.application)
}

val isReleaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true) || it.contains("bundle", ignoreCase = true)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (isReleaseTaskRequested && keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

fun resolveReleaseSigningProp(envName: String, propName: String): String? =
    if (!isReleaseTaskRequested) {
        null
    } else {
        System.getenv(envName)?.takeIf { it.isNotBlank() }
            ?: keystoreProperties.getProperty(propName)?.takeIf { it.isNotBlank() }
    }

val releaseStoreFile = resolveReleaseSigningProp("ANDROID_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = resolveReleaseSigningProp("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = resolveReleaseSigningProp("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = resolveReleaseSigningProp("ANDROID_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = isReleaseTaskRequested &&
    releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

val computedVersionCode = if (isReleaseTaskRequested) {
    providers.of(ReleaseVersionCodeSource::class) {
        parameters.repoDir.set(rootProject.layout.projectDirectory)
    }.get()
} else {
    2
}
val computedVersionName = providers.of(GitDescribeVersionNameSource::class) {
    parameters.repoDir.set(rootProject.layout.projectDirectory)
}.get()

val logBuildVersion = tasks.register("logBuildVersion") {
    val versionName = computedVersionName
    val versionCode = computedVersionCode
    doLast {
        logger.lifecycle("App version: $versionName (versionCode=$versionCode)")
    }
}

tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(logBuildVersion)
    }
    if (name == "assembleDebug") {
        dependsOn("lintDebug")
    }
    if (isReleaseTaskRequested && (name.contains("Release", ignoreCase = true) || name.contains("bundle", ignoreCase = true))) {
        notCompatibleWithConfigurationCache(
            "Release builds use ephemeral signing credentials that must not be persisted to the configuration cache"
        )
    }
}

android {
    namespace = "io.github.androidaql"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.dyfis.androidaql"
        minSdk = 33
        targetSdk = 37
        versionCode = computedVersionCode
        versionName = computedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        val strictResourceChecks = listOf(
            "UnusedResources",
            "StringFormatInvalid",
            "StringFormatCount",
            "StringFormatMatches"
        )
        error += strictResourceChecks
        fatal += strictResourceChecks
        checkReleaseBuilds = true
        abortOnError = true
    }
    testOptions {
        managedDevices {
            localDevices {
                create("pixel6Api33") {
                    device = "Pixel 6"
                    apiLevel = 33
                    systemImageSource = "aosp-atd"
                }
                create("pixel6Api35") {
                    device = "Pixel 6"
                    apiLevel = 35
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
