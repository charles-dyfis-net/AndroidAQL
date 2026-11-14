import java.io.FileInputStream
import java.util.Properties
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

abstract class ReleaseEpochVersionCodeSource : ValueSource<Int, ValueSourceParameters.None> {
    override fun obtain(): Int {
        // Increases every 10 seconds (~179,450,000 in 2026, well below Play's 2,100,000,000 limit),
        // ensuring every signed release build gets a strictly increasing versionCode without manual bumps.
        return (System.currentTimeMillis() / 10_000L).toInt()
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
        versionName = "0.1.0-m1"

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
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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
