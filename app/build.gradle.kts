import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

abstract class ReleaseEpochVersionCodeSource : ValueSource<Int, ValueSourceParameters.None> {
    override fun obtain(): Int {
        // Increases every 10 seconds (~179,450,000 in 2026, well below Play's 2,100,000,000 limit),
        // ensuring every signed release build gets a strictly increasing versionCode without manual bumps.
        return (System.currentTimeMillis() / 10_000L).toInt()
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

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

val isReleaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true) || it.contains("bundle", ignoreCase = true)
}
val computedVersionCode = if (isReleaseTaskRequested) {
    providers.of(ReleaseEpochVersionCodeSource::class) {}.get()
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
        if (keystorePropertiesFile.exists()) {
            create("release") {
                val storePath = keystoreProperties.getProperty("storeFile")
                if (!storePath.isNullOrBlank()) {
                    storeFile = rootProject.file(storePath)
                }
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropertiesFile.exists()) {
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
    testOptions {
        managedDevices {
            localDevices {
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
