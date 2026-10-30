pluginManagement {
    repositories {
        // 本机到 dl.google.com 的 JVM TLS 握手会被重置，用国内镜像代理 Google Maven / Maven Central，原仓库保留兜底
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 同上：dl.google.com 直连不可用，镜像优先
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

// AGP 9 起不再通过 com.android.settings 插件在 settings 里下发 SDK 版本，
// compileSdk / minSdk / targetSdk 统一写在 :app 的 android {} 里；工程已无原生代码。

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "EhViewer"
include(":app")
