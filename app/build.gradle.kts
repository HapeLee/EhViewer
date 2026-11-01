import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 起内置 Kotlin 支持，不再应用 org.jetbrains.kotlin.android
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.spotless)
}

@Suppress("UnstableApiUsage")
android {
    // Compose 1.13.0-beta01 的 AAR 元数据要求 compileSdk >= 37.1
    compileSdk = 37
    compileSdkMinor = 1
    buildToolsVersion = "37.0.0"

    androidResources {
        localeFilters += listOf(
            "zh",
            "zh-rCN",
            "zh-rHK",
            "zh-rTW",
            "ja",
        )
    }

    val signConfig = signingConfigs.create("release") {
        storeFile = File(projectDir.path + "/keystore/androidkey.jks")
        storePassword = "000000"
        keyAlias = "key0"
        keyPassword = "000000"
        enableV3Signing = true
        enableV4Signing = true
    }

    val commitSha by lazy {
        val stdout = providers.exec {
            commandLine = "git rev-parse --short=7 HEAD".split(' ')
        }.standardOutput
        stdout.asText.get().trim()
    }

    val buildTime by lazy {
        val stdout = providers.exec {
            commandLine = "git log -1 --format=%cd --date=iso".split(' ')
        }.standardOutput
        stdout.asText.get().trim()
    }

    defaultConfig {
        applicationId = "org.moedog.ehviewer"
        minSdk = 31
        targetSdk = 36
        versionCode = 180015
        versionName = "1.8.14"
        buildConfigField("String", "VERSION_CODE", "\"${defaultConfig.versionCode}\"")
        buildConfigField("String", "COMMIT_SHA", "\"$commitSha\"")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable.add("MissingTranslation")
    }

    packaging {
        resources {
            excludes += "/META-INF/**"
            excludes += "/kotlin/**"
            excludes += "**.txt"
            excludes += "**.bin"
        }
    }

    dependenciesInfo.includeInApk = false

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signConfig
            buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "BUILD_TIME", "\"\"")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    namespace = "com.hippo.ehviewer"
}

dependencies {
    // https://developer.android.com/jetpack/androidx/releases/activity
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.webkit)

    implementation(libs.androidx.core)

    // https://developer.android.com/jetpack/androidx/releases/datastore
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.fragment)
    // https://developer.android.com/jetpack/androidx/releases/lifecycle
    implementation(libs.androidx.lifecycle.process)

    // https://developer.android.com/jetpack/androidx/releases/paging
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)

    // https://developer.android.com/jetpack/androidx/releases/room
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.paging)

    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.drawer)
    implementation(libs.material)

    // Compose + Material 3 Expressive（新的设计系统）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation 3（新的导航系统）
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // 阅读器：Coil Compose + telephoto（缩放/平移 + 大图自动分块）
    implementation(libs.coil.compose)
    implementation(libs.telephoto.zoomable.image.coil3)

    // 纯 JVM 压缩库，替代 libarchive（JNI）；RAR/CBR 暂不支持
    implementation(libs.zip4j)
    implementation(libs.commons.compress)
    implementation(libs.xz)

    testImplementation(libs.junit)

    // https://square.github.io/okhttp/changelogs/changelog/
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp.coroutines)
    implementation(libs.okhttp.tls)
    implementation(libs.okhttp.dnsoverhttps)

    implementation(libs.okio.jvm)

    // https://github.com/RikkaApps/RikkaX
    implementation(libs.bundles.rikkax)

    // https://coil-kt.github.io/coil/changelog/
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.ktor.utils)
    implementation(libs.jsoup)

    coreLibraryDesugaring(libs.desugar)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        progressiveMode = true
        optIn.addAll(
            "coil3.annotation.ExperimentalCoilApi",
            "kotlin.contracts.ExperimentalContracts",
            "kotlin.time.ExperimentalTime",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
            "kotlinx.coroutines.InternalCoroutinesApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

configurations.all {
    exclude("dev.rikka.rikkax.appcompat", "appcompat")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

val ktlintVersion = libs.ktlint.get().version

spotless {
    kotlin {
        // https://github.com/diffplug/spotless/issues/111
        target("src/**/*.kt")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        ktlint(ktlintVersion)
    }
}
