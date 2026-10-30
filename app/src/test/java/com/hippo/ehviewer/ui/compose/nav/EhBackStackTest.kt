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
package com.hippo.ehviewer.ui.compose.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class EhBackStackTest {
    @Test
    fun `single entry scene hands the back press to the host`() {
        assertEquals(EhBackAction.POP_SCENE, ehBackAction(1))
        assertEquals(EhBackAction.POP_SCENE, ehBackAction(0))
    }

    @Test
    fun `deeper stack pops the top entry first`() {
        assertEquals(EhBackAction.POP_ENTRY, ehBackAction(2))
        assertEquals(EhBackAction.POP_ENTRY, ehBackAction(5))
    }
}
