import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
}

/**
 * Xray-core comes as a 59 MB prebuilt AAR from 2dust/AndroidLibXrayLite. It is fetched here
 * instead of being committed, and the checksum pins the exact build we tested against.
 */
val xrayVersion = "v26.8.20"
val xraySha256 = "670cf11d9d10a6bb6548ac4f593acfa4339155732f6f8de4d45923f30a74deed"
val xrayAar = layout.projectDirectory.file("libs/libv2ray.aar").asFile

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

if (!xrayAar.isFile || sha256(xrayAar) != xraySha256) {
    val url = "https://github.com/2dust/AndroidLibXrayLite/releases/download/" +
        "$xrayVersion/libv2ray.aar"
    logger.lifecycle("Downloading Xray-core $xrayVersion from $url")
    xrayAar.parentFile.mkdirs()
    val tmp = File(xrayAar.parentFile, "libv2ray.aar.part")
    URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    val actual = sha256(tmp)
    if (actual != xraySha256) {
        tmp.delete()
        throw GradleException("libv2ray.aar checksum mismatch: expected $xraySha256, got $actual")
    }
    tmp.renameTo(xrayAar)
}

android {
    namespace = "com.kurskievtr.siderouteng"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kurskievtr.siderouteng"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(files(xrayAar))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
}
