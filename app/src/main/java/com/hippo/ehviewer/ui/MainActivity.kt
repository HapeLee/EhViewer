/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.ui

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.TextUtils
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.IdRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.hippo.app.EditTextDialogBuilder
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUrlOpener
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.cachedGalleryDetail
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.nav.galleryQueryOf
import com.hippo.ehviewer.ui.compose.nav.homepageRoute
import com.hippo.ehviewer.ui.compose.nav.initialCheckRoute
import com.hippo.ehviewer.ui.compose.nav.legacyRouteOf
import com.hippo.ehviewer.ui.compose.nav.sceneClassOf
import com.hippo.ehviewer.ui.compose.nav.toLegacyArgs
import com.hippo.ehviewer.ui.host.EhHost
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.CookieSignInScene
import com.hippo.ehviewer.ui.scene.DownloadsScene
import com.hippo.ehviewer.ui.scene.FavoritesScene
import com.hippo.ehviewer.ui.scene.GalleryCommentsScene
import com.hippo.ehviewer.ui.scene.GalleryDetailScene
import com.hippo.ehviewer.ui.scene.GalleryInfoScene
import com.hippo.ehviewer.ui.scene.GalleryListScene
import com.hippo.ehviewer.ui.scene.GalleryPreviewsScene
import com.hippo.ehviewer.ui.scene.HistoryScene
import com.hippo.ehviewer.ui.scene.ProgressScene
import com.hippo.ehviewer.ui.scene.SecurityScene
import com.hippo.ehviewer.ui.scene.SelectSiteScene
import com.hippo.ehviewer.ui.scene.SignInScene
import com.hippo.ehviewer.ui.scene.SolidScene
import com.hippo.ehviewer.ui.scene.WebViewSignInScene
import com.hippo.ehviewer.widget.EhStageLayout
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFragment
import com.hippo.scene.StageActivity
import com.hippo.unifile.UniFile
import com.hippo.unifile.sha1
import com.hippo.util.addTextToClipboard
import com.hippo.util.getClipboardManager
import com.hippo.util.getParcelableExtraCompat
import com.hippo.util.getUrlFromClipboard
import com.hippo.util.isAtLeastQ
import com.hippo.util.isAtLeastS
import com.hippo.widget.DrawerView
import com.hippo.widget.LoadImageView
import com.hippo.yorozuya.SimpleHandler
import com.hippo.yorozuya.ViewUtils

class MainActivity :
    StageActivity(),
    EhHost,
    NavigationView.OnNavigationItemSelectedListener {
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) refreshTopScene()
        }
    private lateinit var connectivityManager: ConnectivityManager
    private var mSnackBar: CoordinatorLayout? = null
    private var mDrawerLayout: DrawerLayout? = null
    private var mStageLayout: EhStageLayout? = null
    private var mNavView: NavigationView? = null
    private var mRightDrawer: DrawerView? = null
    private var mAvatar: LoadImageView? = null
    private var mDisplayName: TextView? = null
    private var mNavCheckedItem = 0

    override var containerViewId: Int = R.id.fragment_container

    override var launchAnnouncer: Announcer = announcerFor(initialCheckRoute())

    /**
     * route → 旧 `Announcer`。
     *
     * 宿主（[StageActivity]）还只认 `Announcer(class, args)`，所以这里做一次适配；
     * 意图已经全部用 route 表达，P7-b 换成 `NavDisplay` 之后直接删掉这个函数。
     */
    private fun announcerFor(route: EhRoute): Announcer = Announcer(sceneClassOf(route)).setArgs(route.toLegacyArgs(cachedGalleryDetail(route)))

    /**
     * 冷启动（栈里还是空的）要先过守卫链；已经开着就直接压目标页
     * ——这是原来 `processAnnouncer` 的语义，只是换成 route 之后不用再串 Bundle 了。
     */
    private fun processRoute(route: EhRoute): Announcer = announcerFor(if (sceneCount == 0) initialCheckRoute(route) else route)

    private fun startRoute(route: EhRoute) {
        startScene(processRoute(route))
    }

    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        val action = intent.action
        if (Intent.ACTION_VIEW == action) {
            val uri = intent.data ?: return false
            val route = EhUrlOpener.parseUrl(uri.toString())
            if (route != null) {
                startRoute(route)
                return true
            }
        } else if (Intent.ACTION_SEND == action) {
            val type = intent.type
            if ("text/plain" == type) {
                val builder = ListUrlBuilder().apply {
                    keyword = intent.getStringExtra(Intent.EXTRA_TEXT)
                }
                startRoute(EhRoute.GalleryList(galleryQueryOf(builder)))
                return true
            } else if (type != null && type.startsWith("image/")) {
                val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
                if (null != uri) {
                    UniFile.fromUri(this, uri)?.sha1()?.let { hash ->
                        val builder = ListUrlBuilder().apply {
                            mode = ListUrlBuilder.MODE_IMAGE_SEARCH
                            this.hash = hash
                        }
                        startRoute(EhRoute.GalleryList(galleryQueryOf(builder)))
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onUnrecognizedIntent(intent: Intent?) {
        val clazz = topSceneClass
        if (clazz != null && SolidScene::class.java.isAssignableFrom(clazz)) {
            // TODO the intent lost
            return
        }
        if (!handleIntent(intent)) {
            var handleUrl = false
            if (intent != null && Intent.ACTION_VIEW == intent.action) {
                handleUrl = true
                if (intent.data != null) {
                    val url = intent.data.toString()
                    EditTextDialogBuilder(this, url, "")
                        .setTitle(R.string.error_cannot_parse_the_url)
                        .setPositiveButton(android.R.string.copy) { _: DialogInterface?, _: Int ->
                            this.addTextToClipboard(
                                url,
                                false,
                            )
                        }
                        .show()
                }
            }
            if (0 == sceneCount) {
                if (handleUrl) {
                    finish()
                } else {
                    startRoute(homepageRoute())
                }
            }
        }
    }

    override fun onStartSceneFromIntent(clazz: Class<*>, args: Bundle?): Announcer = processRoute(legacyRouteOf(clazz.name, args) ?: homepageRoute())

    override fun onCreate2(savedInstanceState: Bundle?) {
        connectivityManager = getSystemService()!!
        setContentView(R.layout.activity_main)
        mSnackBar = ViewUtils.`$$`(this, R.id.snackbar) as CoordinatorLayout
        mStageLayout = ViewUtils.`$$`(this, R.id.fragment_container) as EhStageLayout
        mDrawerLayout = ViewUtils.`$$`(this, R.id.draw_view) as DrawerLayout
        mNavView = ViewUtils.`$$`(this, R.id.nav_view) as NavigationView
        mRightDrawer = ViewUtils.`$$`(this, R.id.right_drawer) as DrawerView
        if (mDrawerLayout != null) {
            mDrawerLayout!!.setStatusBarBackgroundColor(0)
        }
        if (mNavView != null) {
            val headerLayout = mNavView!!.getHeaderView(0)
            mAvatar = ViewUtils.`$$`(headerLayout, R.id.avatar) as LoadImageView
            mDisplayName = ViewUtils.`$$`(headerLayout, R.id.display_name) as TextView
            ViewUtils.`$$`(headerLayout, R.id.night_mode).setOnClickListener {
                val theme = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_YES <= 0
                val target = if (((getSystemService(UI_MODE_SERVICE) as UiModeManager).nightMode == UiModeManager.MODE_NIGHT_YES) == theme) {
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                } else if (theme) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
                AppCompatDelegate.setDefaultNightMode(target)
                Settings.putTheme(target)
                recreate()
            }
            updateProfile()
            mNavView!!.setNavigationItemSelectedListener(this)
            mAvatar!!.setOnClickListener { updateProfile() }
        }
        if (savedInstanceState == null) {
            checkDownloadLocation()
            if (Settings.meteredNetworkWarning) {
                checkMeteredNetwork()
            }
            if (isAtLeastS) {
                if (!Settings.appLinkVerifyTip) {
                    try {
                        checkAppLinkVerify()
                    } catch (_: PackageManager.NameNotFoundException) {
                    }
                }
            }
        } else {
            onRestore(savedInstanceState)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Throws(PackageManager.NameNotFoundException::class)
    private fun checkAppLinkVerify() {
        val manager = getSystemService(DomainVerificationManager::class.java)
        val userState = manager.getDomainVerificationUserState(packageName) ?: return
        var hasUnverified = false
        val hostToStateMap = userState.hostToStateMap
        for (key in hostToStateMap.keys) {
            val stateValue = hostToStateMap[key]
            if (stateValue == null || stateValue == DomainVerificationUserState.DOMAIN_STATE_VERIFIED || stateValue == DomainVerificationUserState.DOMAIN_STATE_SELECTED) {
                continue
            }
            hasUnverified = true
            break
        }
        if (hasUnverified) {
            AlertDialog.Builder(this)
                .setTitle(R.string.app_link_not_verified_title)
                .setMessage(R.string.app_link_not_verified_message)
                .setPositiveButton(R.string.open_settings) { _: DialogInterface?, _: Int ->
                    try {
                        val intent = Intent(
                            android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                            "package:$packageName".toUri(),
                        )
                        startActivity(intent)
                    } catch (_: Throwable) {
                        val intent = Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:$packageName".toUri(),
                        )
                        startActivity(intent)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.dont_show_again) { _: DialogInterface?, _: Int ->
                    Settings.putAppLinkVerifyTip(
                        true,
                    )
                }
                .show()
        }
    }

    private fun checkDownloadLocation() {
        val uniFile = Settings.downloadLocation
        // null == uniFile for first start
        if (null == uniFile || uniFile.ensureDir()) {
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.waring)
            .setMessage(R.string.invalid_download_location)
            .setPositiveButton(R.string.get_it, null)
            .show()
    }

    private fun checkMeteredNetwork() {
        if (connectivityManager.isActiveNetworkMetered) {
            if (isAtLeastQ && mSnackBar != null) {
                Snackbar.make(
                    mSnackBar!!,
                    R.string.metered_network_warning,
                    Snackbar.LENGTH_LONG,
                )
                    .setAction(R.string.settings) {
                        val panelIntent =
                            Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                        startActivity(panelIntent)
                    }
                    .show()
            } else {
                showTip(R.string.metered_network_warning, BaseScene.LENGTH_LONG)
            }
        }
    }

    private fun onRestore(savedInstanceState: Bundle) {
        mNavCheckedItem = savedInstanceState.getInt(KEY_NAV_CHECKED_ITEM)
    }

    override fun onSaveInstanceState(outState: Bundle, outPersistentState: PersistableBundle) {
        super.onSaveInstanceState(outState, outPersistentState)
        outState.putInt(KEY_NAV_CHECKED_ITEM, mNavCheckedItem)
    }

    override fun onDestroy() {
        super.onDestroy()
        mDrawerLayout = null
        mNavView = null
        mRightDrawer = null
        mAvatar = null
        mDisplayName = null
    }

    override fun onResume() {
        super.onResume()
        setNavCheckedItem(mNavCheckedItem)
        checkClipboardUrl()
    }

    override fun onTransactScene() {
        super.onTransactScene()
        checkClipboardUrl()
    }

    private fun checkClipboardUrl() {
        SimpleHandler.getInstance().postDelayed({
            if (!isSolid) {
                checkClipboardUrlInternal()
            }
        }, 300)
    }

    private val isSolid: Boolean
        get() {
            val topClass = topSceneClass
            return topClass == null || SolidScene::class.java.isAssignableFrom(topClass)
        }

    private fun createRouteFromClipboardUrl(url: String): EhRoute? {
        val result1 = GalleryDetailUrlParser.parse(url, false)
        if (result1 != null) {
            return EhRoute.GalleryDetail(gid = result1.gid, token = result1.token)
        }
        val result2 = GalleryPageUrlParser.parse(url, false)
        if (result2 != null) {
            return EhRoute.Progress(
                gid = result2.gid,
                pToken = result2.pToken,
                page = result2.page,
            )
        }
        return null
    }

    private fun checkClipboardUrlInternal() {
        val text = this.getClipboardManager().getUrlFromClipboard(this)
        val hashCode = text?.hashCode() ?: 0
        if (text != null && hashCode != 0 && Settings.clipboardTextHashCode != hashCode) {
            val route = createRouteFromClipboardUrl(text)
            if (route != null && mSnackBar != null) {
                val snackbar = Snackbar.make(
                    mSnackBar!!,
                    R.string.clipboard_gallery_url_snack_message,
                    Snackbar.LENGTH_SHORT,
                )
                snackbar.setAction(R.string.clipboard_gallery_url_snack_action) {
                    ehNavigator().push(route)
                }
                snackbar.show()
            }
        }
        Settings.putClipboardTextHashCode(hashCode)
    }

    override fun onSceneViewCreated(scene: SceneFragment, savedInstanceState: Bundle?) {
        super.onSceneViewCreated(scene, savedInstanceState)
        createDrawerView(scene)
    }

    override fun recreateDrawerView(scene: BaseScene) {
        createDrawerView(scene)
    }

    @SuppressLint("RtlHardcoded")
    fun createDrawerView(scene: SceneFragment?) {
        if (scene is BaseScene && mRightDrawer != null && mDrawerLayout != null) {
            mRightDrawer!!.removeAllViews()
            val drawerView = scene.createDrawerView(
                scene.layoutInflater,
                mRightDrawer,
                null,
            )
            if (drawerView != null) {
                mRightDrawer!!.addView(drawerView)
                mDrawerLayout!!.setDrawerLockMode(
                    DrawerLayout.LOCK_MODE_UNLOCKED,
                    GravityCompat.END,
                )
            } else {
                mDrawerLayout!!.setDrawerLockMode(
                    DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
                    GravityCompat.END,
                )
            }
        }
    }

    override fun onSceneViewDestroyed(scene: SceneFragment) {
        super.onSceneViewDestroyed(scene)
        if (scene is BaseScene) {
            scene.destroyDrawerView()
        }
    }

    override fun updateProfile() {
        if (mAvatar == null || mDisplayName == null) {
            return
        }
        val avatarUrl = Settings.avatar
        if (TextUtils.isEmpty(avatarUrl)) {
            mAvatar!!.load(R.drawable.default_avatar)
        } else {
            mAvatar!!.load(avatarUrl!!, avatarUrl)
        }
        val displayName = Settings.displayName
        if (TextUtils.isEmpty(displayName)) {
            mDisplayName!!.text = getString(R.string.default_display_name)
        } else {
            mDisplayName!!.text = displayName
        }
    }

    override fun addAboveSnackView(view: View) {
        mStageLayout?.addAboveSnackView(view)
    }

    override fun removeAboveSnackView(view: View) {
        mStageLayout?.removeAboveSnackView(view)
    }

    override fun setDrawerLockMode(lockMode: Int, edgeGravity: Int) {
        mDrawerLayout?.setDrawerLockMode(lockMode, edgeGravity)
    }

    override fun openDrawer(drawerGravity: Int) {
        mDrawerLayout?.openDrawer(drawerGravity)
    }

    override fun closeDrawer(drawerGravity: Int) {
        mDrawerLayout?.closeDrawer(drawerGravity)
    }

    override fun toggleDrawer(drawerGravity: Int) {
        mDrawerLayout?.run {
            if (isDrawerOpen(drawerGravity)) {
                closeDrawer(drawerGravity)
            } else {
                openDrawer(drawerGravity)
            }
        }
    }

    override fun setNavCheckedItem(@IdRes resId: Int) {
        mNavCheckedItem = resId
        mNavView?.run {
            if (resId == 0) {
                setCheckedItem(R.id.nav_stub)
            } else {
                setCheckedItem(resId)
            }
        }
    }

    override fun showTip(@StringRes id: Int, length: Int) {
        showTip(getString(id), length)
    }

    private val isDrawerOpen
        get() = mNavView?.isVisible == true || mRightDrawer?.isVisible == true

    /**
     * If activity is running, show snack bar, otherwise show toast
     */
    override fun showTip(message: CharSequence, length: Int) {
        if (mSnackBar != null && !isDrawerOpen) {
            Snackbar.make(
                mSnackBar!!,
                message,
                if (length == BaseScene.LENGTH_LONG) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT,
            ).show()
        } else {
            Toast.makeText(
                this,
                message,
                if (length == BaseScene.LENGTH_LONG) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
            ).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isDrawerOpen) {
            mDrawerLayout!!.closeDrawers()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Don't select twice
        if (item.isChecked) {
            return false
        }
        val id = item.itemId
        when (id) {
            R.id.nav_homepage -> {
                startSceneFirstly(announcerFor(homepageRoute(GalleryListScene.ACTION_HOMEPAGE)))
            }
            R.id.nav_subscription -> {
                startSceneFirstly(announcerFor(homepageRoute(GalleryListScene.ACTION_SUBSCRIPTION)))
            }
            R.id.nav_whats_hot -> {
                startSceneFirstly(announcerFor(homepageRoute(GalleryListScene.ACTION_WHATS_HOT)))
            }
            R.id.nav_toplist -> {
                startSceneFirstly(announcerFor(homepageRoute(GalleryListScene.ACTION_TOP_LIST)))
            }
            R.id.nav_favourite -> {
                startRoute(EhRoute.Favorites)
            }
            R.id.nav_history -> {
                startRoute(EhRoute.History)
            }
            R.id.nav_downloads -> {
                startRoute(EhRoute.Downloads())
            }
            R.id.nav_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                settingsLauncher.launch(intent)
            }
        }
        if (id != R.id.nav_stub) {
            mDrawerLayout?.closeDrawers()
        }
        return true
    }

    companion object {
        private const val KEY_NAV_CHECKED_ITEM = "nav_checked_item"

        init {
            registerLaunchMode(SecurityScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(SignInScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(WebViewSignInScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(CookieSignInScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(SelectSiteScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(GalleryListScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TOP)
            registerLaunchMode(GalleryDetailScene::class.java, SceneFragment.LAUNCH_MODE_STANDARD)
            registerLaunchMode(GalleryInfoScene::class.java, SceneFragment.LAUNCH_MODE_STANDARD)
            registerLaunchMode(GalleryCommentsScene::class.java, SceneFragment.LAUNCH_MODE_STANDARD)
            registerLaunchMode(GalleryPreviewsScene::class.java, SceneFragment.LAUNCH_MODE_STANDARD)
            registerLaunchMode(DownloadsScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(FavoritesScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
            registerLaunchMode(HistoryScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TOP)
            registerLaunchMode(ProgressScene::class.java, SceneFragment.LAUNCH_MODE_STANDARD)
        }
    }
}
