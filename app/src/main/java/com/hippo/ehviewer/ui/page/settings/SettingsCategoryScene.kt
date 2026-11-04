/*
 * Copyright 2026 EhViewer contributors
 *
 * This file is part of EhViewer
 *
 * EhViewer is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * EhViewer is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with EhViewer.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package com.hippo.ehviewer.ui.page.settings

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.EhApplication.Companion.application
import com.hippo.ehviewer.EhApplication.Companion.searchDatabase
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.UrlOpener
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.SettingsPage
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.screen.settings.AccountDialog
import com.hippo.ehviewer.ui.compose.screen.settings.AdvancedSettingsScreen
import com.hippo.ehviewer.ui.compose.screen.settings.DownloadLocationDialog
import com.hippo.ehviewer.ui.compose.screen.settings.DownloadSettingsScreen
import com.hippo.ehviewer.ui.compose.screen.settings.EhSettingsScreen
import com.hippo.ehviewer.ui.compose.screen.settings.FilterSettingsScreen
import com.hippo.ehviewer.ui.compose.screen.settings.ImageLimitsDialog
import com.hippo.ehviewer.ui.compose.screen.settings.PrivacySettingsScreen
import com.hippo.ehviewer.ui.compose.screen.settings.ProxyDialog
import com.hippo.ehviewer.ui.compose.screen.settings.ReadSettingsScreen
import com.hippo.ehviewer.ui.compose.screen.settings.SecurityPatternScreen
import com.hippo.ehviewer.ui.compose.screen.settings.UserAgentDialog
import com.hippo.ehviewer.ui.host.OnHostResume
import com.hippo.ehviewer.ui.host.TIP_LENGTH_SHORT
import com.hippo.ehviewer.ui.host.ehHostOrNoOp
import com.hippo.ehviewer.ui.keepNoMediaFileStatus
import com.hippo.unifile.UniFile
import com.hippo.util.ReadableTime
import com.hippo.util.addTextToClipboard
import kotlinx.coroutines.launch

/**
 * 设置分类页的分流入口。
 *
 * 九个设置子页全部就地渲染，`when` 因此是穷尽的：加页时编译器会强制这里跟着改，
 * 不会再出现「点进去是一个空白壳」的过渡态。老的 `SettingsActivity` 与 `BaseFragment`
 * 那条链路已经删干净。
 */
@Composable
fun SettingsCategoryScene(route: EhRoute.SettingsCategory) {
    when (route.page) {
        SettingsPage.EH -> EhSettingsScene()
        SettingsPage.READ -> ReadSettingsScene()
        SettingsPage.PRIVACY -> PrivacySettingsScene()
        SettingsPage.DOWNLOAD -> DownloadSettingsScene()
        SettingsPage.ADVANCED -> AdvancedSettingsScene()
        SettingsPage.UCONFIG, SettingsPage.MY_TAGS -> WebEditorScene(route.page)
        SettingsPage.FILTER -> FilterSettingsScene()
        SettingsPage.SECURITY -> SecurityPatternScene()
    }
}

/**
 * 过滤器页。
 *
 * 数据在 [com.hippo.ehviewer.client.EhFilter] 的内存分组里，`FilterSettingsViewModel`
 * 只负责「快照 + 写库后重建」；空态、对话框这些纯 UI 都在 `FilterSettingsScreen`。
 */
@Composable
private fun FilterSettingsScene() {
    val navigator = ehNavigator()
    val vm = viewModel { FilterSettingsViewModel() }

    FilterSettingsScreen(
        entries = vm.entries,
        onBack = navigator::pop,
        onAdd = vm::add,
        onToggle = vm::toggle,
        onDelete = vm::delete,
    )
}

@Composable
private fun ReadSettingsScene() {
    val navigator = ehNavigator()
    ReadSettingsScreen(onBack = navigator::pop)
}

@Composable
private fun PrivacySettingsScene() {
    val navigator = ehNavigator()
    val host = ehHostOrNoOp()
    val scope = rememberCoroutineScope()
    // 手势锁页现在就在同一条返回栈上：pop 回来本页会重新组合，读数自然是新的。
    // 这里的 resume 计数只兜住「从系统设置等外部页面回来」的改动。
    var resumeCount by remember { mutableIntStateOf(0) }
    OnHostResume { resumeCount++ }
    val patternSet = remember(resumeCount) { !Settings.security.isNullOrEmpty() }

    PrivacySettingsScreen(
        patternSet = patternSet,
        onBack = navigator::pop,
        onOpenPattern = { navigator.push(EhRoute.SettingsCategory(SettingsPage.SECURITY)) },
        onClearSearchHistory = {
            scope.launch {
                searchDatabase.clearQuery()
                host.showTip(R.string.settings_privacy_clear_search_history_cleared, TIP_LENGTH_SHORT)
            }
        },
    )
}

@Composable
private fun SecurityPatternScene() {
    val context = LocalContext.current
    val navigator = ehNavigator()
    val biometricAvailable = remember {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    SecurityPatternScreen(
        initialPattern = Settings.security,
        biometricAvailable = biometricAvailable,
        initialFingerprintEnabled = Settings.enableFingerprint,
        onCancel = navigator::pop,
        onConfirm = { pattern, cellSize, enableFingerprint ->
            // 与老的 SetSecurityFragment 同一口径：格子数 ≤ 1 视为清除
            val security = if (cellSize <= 1) "" else pattern
            Settings.putSecurity(security)
            Settings.putEnableFingerprint(biometricAvailable && enableFingerprint && security.isNotEmpty())
            navigator.pop()
        },
    )
}

@Composable
private fun EhSettingsScene() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val navigator = ehNavigator()
    val host = ehHostOrNoOp()
    val scope = rememberCoroutineScope()
    val vm = viewModel { EhSettingsViewModel(context.applicationContext as Application) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var showLimits by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.start() }

    EhSettingsScreen(
        signedIn = vm.signedIn,
        displayName = vm.displayName,
        tagTranslatable = vm.tagTranslatable,
        imageLimitsSummary = vm.imageLimitsSummary,
        onBack = navigator::pop,
        onOpenAccount = { showAccount = true },
        onOpenImageLimits = {
            vm.openLimits()
            showLimits = true
        },
        onOpenUConfig = { navigator.push(EhRoute.SettingsCategory(SettingsPage.UCONFIG)) },
        onOpenMyTags = { navigator.push(EhRoute.SettingsCategory(SettingsPage.MY_TAGS)) },
        onOpenFilter = { navigator.push(EhRoute.SettingsCategory(SettingsPage.FILTER)) },
        onOpenUrl = { url -> UrlOpener.openUrl(context, url, true) },
        onThemeChanged = { value ->
            // 值已经由 ListPreferenceRow 落盘。`Settings.theme` 真正生效的地方是
            // `EhActivity.attachBaseContext`（FragmentActivity 收不到 AppCompatDelegate
            // 的派发），所以这里只需要重建宿主；顺带给 AppCompat 的静态开关也同步一份，
            // 免得它自己派发的对话框（AppCompatDialog）算出另一套明暗
            AppCompatDelegate.setDefaultNightMode(value)
            context.findActivity()?.recreate()
        },
        onBlackDarkThemeChanged = {
            // 纯黑只在深色下有效，没开深色时不用重建（与老实现一致）
            if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_YES > 0) {
                application.recreateAllActivity()
            }
        },
        onGallerySiteChanged = {
            // 站点换了之后 UConfig 要重新取一次（老实现同样在这里补一把）
            scope.launch { runCatching { EhEngine.getUConfig() } }
        },
        onShowTagTranslationsChanged = { enabled ->
            if (enabled) scope.launch { EhTagDatabase.update(true) }
        },
    )

    if (showAccount) {
        AccountDialog(
            message = vm.accountMessage,
            cookie = vm.accountCookie,
            signedIn = vm.signedIn,
            onDismiss = { showAccount = false },
            onCopyCookie = { cookie ->
                context.addTextToClipboard(cookie, true)
                showAccount = false
            },
            onRefreshIgneous = {
                vm.refreshIgneous()
                showAccount = false
            },
            onSignOut = {
                showAccount = false
                host.showTip(R.string.settings_eh_account_sign_out_tip, TIP_LENGTH_SHORT)
                vm.signOut()
            },
        )
    }

    if (showLimits) {
        ImageLimitsDialog(
            message = vm.limitsMessage,
            actionLabel = vm.limitsActionLabel,
            actionEnabled = vm.limitsActionEnabled,
            onAction = vm::resetLimits,
            onDismiss = { showLimits = false },
        )
    }

    LaunchedEffect(vm.limitsResetCount) {
        if (vm.limitsResetCount > 0) {
            host.showTip(R.string.settings_eh_reset_limits_succeed, TIP_LENGTH_SHORT)
        }
    }
}

@Composable
private fun DownloadSettingsScene() {
    val context = LocalContext.current
    val navigator = ehNavigator()
    val host = ehHostOrNoOp()
    val scope = rememberCoroutineScope()
    val vm = viewModel { DownloadSettingsViewModel(context.applicationContext as Application) }
    var showLocationDialog by rememberSaveable { mutableStateOf(false) }

    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setDownloadLocation(uri)
    }
    val pickDir: () -> Unit = {
        runCatching { dirPicker.launch(null) }
            .onFailure { host.showTip(R.string.error_cant_find_activity, TIP_LENGTH_SHORT) }
    }

    DownloadSettingsScreen(
        downloadLocationUri = vm.downloadLocationUri,
        running = vm.running,
        onBack = navigator::pop,
        onOpenLocation = {
            val location = Settings.downloadLocation
            // 已经是自定义目录时先给个「换目录 / 恢复默认」的选择，否则直接开选择器
            if (location != null && !UniFile.isFileUri(location.uri)) {
                showLocationDialog = true
            } else {
                pickDir()
            }
        },
        onMediaScanChanged = { scope.launch { keepNoMediaFileStatus() } },
        onRestoreDownload = vm::restoreDownloads,
        onCleanRedundancy = vm::cleanRedundancy,
    )

    if (showLocationDialog) {
        DownloadLocationDialog(
            location = Settings.downloadLocation?.uri?.toString().orEmpty(),
            onPickNew = {
                showLocationDialog = false
                pickDir()
            },
            onReset = {
                showLocationDialog = false
                vm.resetDownloadLocation()
            },
            onDismiss = { showLocationDialog = false },
        )
    }

    LaunchedEffect(vm.tip) { vm.consumeTip()?.let { host.showTip(it, TIP_LENGTH_SHORT) } }
}

@Composable
private fun AdvancedSettingsScene() {
    val context = LocalContext.current
    val navigator = ehNavigator()
    val host = ehHostOrNoOp()
    val vm = viewModel { AdvancedSettingsViewModel(context.applicationContext as Application) }
    var showProxy by rememberSaveable { mutableStateOf(false) }
    var showUserAgent by rememberSaveable { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            context.grantWrite(uri)
            vm.exportData(uri)
        }
    }
    val logcatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            context.grantWrite(uri)
            vm.dumpLogcat(uri)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importData(uri)
    }

    AdvancedSettingsScreen(
        proxySummary = vm.proxySummary,
        userAgent = vm.userAgent,
        busy = vm.busy,
        onBack = navigator::pop,
        onOpenProxy = { showProxy = true },
        onOpenUserAgent = { showUserAgent = true },
        onDumpLogcat = {
            runCatching { logcatLauncher.launch("log-" + ReadableTime.getFilenamableTime() + ".zip") }
                .onFailure { host.showTip(R.string.error_cant_find_activity, TIP_LENGTH_SHORT) }
        },
        onBackupFavorite = vm::backupFavorites,
        onExportData = {
            runCatching { exportLauncher.launch(ReadableTime.getFilenamableTime() + ".db") }
                .onFailure { host.showTip(R.string.error_cant_find_activity, TIP_LENGTH_SHORT) }
        },
        onImportData = {
            runCatching { importLauncher.launch(arrayOf("*/*")) }
                .onFailure { host.showTip(R.string.error_cant_find_activity, TIP_LENGTH_SHORT) }
        },
        onOpenByDefault = {
            runCatching {
                context.startActivity(
                    Intent(
                        AndroidSettings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                        "package:${context.packageName}".toUri(),
                    ),
                )
            }.onFailure {
                runCatching {
                    context.startActivity(
                        Intent(
                            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}".toUri(),
                        ),
                    )
                }
            }
        },
        onAppLanguageChanged = { value ->
            if ("system" == value) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(value))
            }
        },
    )

    if (showProxy) {
        ProxyDialog(
            typeLabels = vm.proxyTypes.toList(),
            initialType = vm.currentProxyType,
            initialIp = vm.currentProxyIp,
            initialPort = vm.currentProxyPort,
            isValidPort = vm::isValidPort,
            onDismiss = { showProxy = false },
            onSave = vm::saveProxy,
        )
    }

    if (showUserAgent) {
        UserAgentDialog(
            initialUserAgent = vm.userAgent,
            onDismiss = { showUserAgent = false },
            onSave = vm::saveUserAgent,
        )
    }

    LaunchedEffect(vm.tip) { vm.consumeTip()?.let { host.showTip(it, TIP_LENGTH_SHORT) } }
}

/** MIUI 上 `grantUriPermission` 会抛 RemoteException，失败不影响后续写入 */
private fun Context.grantWrite(uri: android.net.Uri) {
    runCatching {
        grantUriPermission(
            BuildConfig.APPLICATION_ID,
            uri,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
