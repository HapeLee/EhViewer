import java.util.Properties
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

// 显示版本由 version.properties 手工维护，内部编号由 Git 提交数派生。
// 构建本身从不修改任何计数：同一提交在任何机器上得到同一个 versionCode。
// 正式发布只由 GitHub Actions 触发（.github/workflows/release.yml）。
val appVersionProperties = Properties().apply {
    load(providers.fileContents(rootProject.layout.projectDirectory.file("version.properties")).asText.get().reader())
}

fun versionSegment(key: String): Int {
    val raw = appVersionProperties.getProperty(key, "").trim()
    val value = raw.toIntOrNull()
    require(value != null && value >= 0) { "$key must be a non-negative integer, was '$raw'" }
    require(raw == value.toString()) { "$key must not have leading zeros, was '$raw'" }
    return value
}

val versionMajor = versionSegment("VERSION_MAJOR")
val versionMinor = versionSegment("VERSION_MINOR")
val versionPatch = versionSegment("VERSION_PATCH")
val versionSuffix = versionSegment("VERSION_SUFFIX")
val versionCodeBase = versionSegment("VERSION_CODE_BASE")
require(versionSuffix == 0 || versionSuffix == 1) { "VERSION_SUFFIX must be 0 (release) or 1 (prerelease)" }

val baseVersionName = "$versionMajor.$versionMinor.$versionPatch"
// 预发布序号 -beta.N 需要已有 tag 才能计算，由 CI 通过 -PehVersionName 传入；
// 本地预发布构建固定为 -beta.local，避免与线上已发布版本重名。
val appVersionName = providers.gradleProperty("ehVersionName")
    .getOrElse(if (versionSuffix == 1) "$baseVersionName-beta.local" else baseVersionName)
    .trim()

// 提交数是仓库属性：本地与 CI 一致，无需回写文件，也不存在并发分配。
// 注意 --depth 1 的浅克隆只会得到 1，CI 必须 fetch-depth: 0。
val gitCommitCount = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}.getOrNull()

// 旧方案是 major*100000 + minor*10000 + patch + 1（v1.8.14 = 180015），
// 因此编号必须叠加 VERSION_CODE_BASE 才能覆盖安装，见 version.properties。
val appVersionCode = providers.gradleProperty("ehVersionCode").orNull?.trim()?.toIntOrNull()
    ?: (versionCodeBase + (gitCommitCount ?: 0))
require(appVersionCode in 1..2_100_000_000) { "versionCode must be between 1 and 2100000000, was $appVersionCode" }
require(Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(alpha|beta|rc)\.([1-9][0-9]*|local))?""").matches(appVersionName)) {
    "versionName must be X.Y.Z or X.Y.Z-alpha.N / beta.N / rc.N, was $appVersionName"
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
        applicationId = "io.kazusa.ehviewer"
        resValue("string", "shortcut_target_package", "io.kazusa.ehviewer")
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "VERSION_CODE", "\"$appVersionCode\"")
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
            resValue("string", "shortcut_target_package", "io.kazusa.ehviewer.debug")
            buildConfigField("String", "BUILD_TIME", "\"\"")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }

    namespace = "io.kazusa.ehviewer"
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

    // 旧的 View 体系（RecyclerView / SwipeRefreshLayout / Preference /
    // MaterialDrawer / CoordinatorLayout）全部随 XML 布局与老控件删掉了，
    // 页面现在只有 Compose + 三个 AndroidView（WebView / LockPatternView / 公式 TextView）
    implementation(libs.androidx.fragment)
    // https://developer.android.com/jetpack/androidx/releases/lifecycle
    implementation(libs.androidx.lifecycle.process)

    // https://developer.android.com/jetpack/androidx/releases/paging
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    // paging-runtime 传递依赖 recyclerview（我们自己已经不用它了），显式钉住版本，
    // 免得被降到 1.2.1
    implementation(libs.androidx.recyclerview)

    // https://developer.android.com/jetpack/androidx/releases/room
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.paging)

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
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation 3（新的导航系统）
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.navigationevent.compose)

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
