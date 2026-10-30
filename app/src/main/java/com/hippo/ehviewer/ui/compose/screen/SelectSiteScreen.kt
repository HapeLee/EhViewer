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
package com.hippo.ehviewer.ui.compose.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUrl

/**
 * 站点选择页（原 `scene_select_site.xml` + `SelectSiteScene`）。
 *
 * 用 M3E 的 SingleChoiceSegmentedButtonRow 取代 MaterialButtonToggleGroup。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectSiteScreen(onConfirm: (Int) -> Unit) {
    var site by rememberSaveable { mutableStateOf(EhUrl.SITE_EX) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.select_scene),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(24.dp))
            SingleChoiceSegmentedButtonRow {
                val sites = listOf(EhUrl.SITE_E to R.string.site_e, EhUrl.SITE_EX to R.string.site_ex)
                sites.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = site == value,
                        onClick = { site = value },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = sites.size),
                    ) {
                        Text(text = stringResource(label))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.select_scene_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = { onConfirm(site) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        }
    }
}
