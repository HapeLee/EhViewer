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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.google.android.material.snackbar.Snackbar
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.compose.nav.SettingsPage
import com.hippo.ehviewer.ui.fragment.FilterFragment
import com.hippo.ehviewer.ui.fragment.MyTagsFragment
import com.hippo.ehviewer.ui.fragment.SetSecurityFragment
import com.hippo.ehviewer.ui.fragment.UConfigFragment
import com.hippo.ehviewer.ui.host.TIP_LENGTH_LONG

/**
 * 还没迁到 Compose 的设置子页宿主。
 *
 * 设置首页与五个分类页已经在 Nav3 返回栈里（`SettingsCategoryScene`），
 * 只剩四个编辑器（UConfig / 我的标签 / 过滤器 / 手势锁）仍走这里：
 * `newIntent(context, page)` 打开对应 Fragment，页面本身立刻从栈里退出。
 * 这四个也迁完之后，这个 Activity 与 [SettingsPage] 一起删掉。
 *
 * [SettingsPage.createFragment] 是唯一的映射表：已迁的页返回 `null`，这里会直接结束——
 * 老调用方（如果还有）会立刻暴露，而不是静默停在一个空白壳里。
 */
class SettingsActivity : EhActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference)
        setSupportActionBar(findViewById(R.id.toolbar))
        val bar = supportActionBar
        bar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            val fragment = pageOf(intent)?.createFragment()
            if (fragment == null) {
                // 没带 page，或这一页已经迁到 Compose：调用方过时了
                finish()
                return
            }
            supportFragmentManager
                .beginTransaction()
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_MATCH_ACTIVITY_OPEN)
                .replace(R.id.fragment, fragment)
                .commitAllowingStateLoss()
        }
    }

    fun showTip(@StringRes id: Int, length: Int) {
        showTip(getString(id), length)
    }

    fun showTip(message: CharSequence?, length: Int) {
        Snackbar.make(
            findViewById(R.id.snackbar),
            message!!,
            if (length == TIP_LENGTH_LONG) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT,
        ).show()
    }

    @Suppress("DEPRECATION")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_PAGE = "settings_page"

        /** 打开某一页还没迁到 Compose 的设置子页 */
        fun newIntent(context: Context, page: SettingsPage): Intent = Intent(context, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page.name)

        /** 用枚举名而不是下标传参：加页 / 删页时不会悄悄串页 */
        private fun pageOf(intent: Intent?): SettingsPage? {
            val name = intent?.getStringExtra(EXTRA_PAGE) ?: return null
            return SettingsPage.entries.firstOrNull { it.name == name }
        }

        /** `null` 表示这一页已经是 Compose 的，不该再由本 Activity 承载 */
        private fun SettingsPage.createFragment(): Fragment? = when (this) {
            SettingsPage.EH, SettingsPage.READ, SettingsPage.PRIVACY,
            SettingsPage.DOWNLOAD, SettingsPage.ADVANCED,
            -> null
            SettingsPage.UCONFIG -> UConfigFragment()
            SettingsPage.MY_TAGS -> MyTagsFragment()
            SettingsPage.FILTER -> FilterFragment()
            SettingsPage.SECURITY -> SetSecurityFragment()
        }
    }
}
