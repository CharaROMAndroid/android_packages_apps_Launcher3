/*
 * Copyright (C) 2026 CharaROM
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.lineage.trust

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class HiddenAppsStoreTest {
    private lateinit var store: HiddenAppsStore

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ctx.getSharedPreferences("hidden_apps_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = HiddenAppsStore(prefs) { it.hashCode().toLong() }
    }

    @Test fun defaultsToVisible() {
        assertThat(store.isHidden("a.b", 0L)).isFalse()
    }

    @Test fun hidingIsPerUser() {
        store.setHidden("a.b", 0L, true)
        assertThat(store.isHidden("a.b", 0L)).isTrue()
        assertThat(store.isHidden("a.b", 10L)).isFalse()
    }

    @Test fun unhideRemoves() {
        store.setHidden("a.b", 0L, true)
        store.setHidden("a.b", 0L, false)
        assertThat(store.isHidden("a.b", 0L)).isFalse()
    }

    @Test fun clearUserOnlyAffectsThatUser() {
        store.setHidden("a.b", 1L, true)
        store.setHidden("a.b", 10L, true)
        store.clearUser(1L)
        assertThat(store.isHidden("a.b", 1L)).isFalse()
        assertThat(store.isHidden("a.b", 10L)).isTrue()
    }

    @Test fun serialPrefixDoesNotCollide() {
        store.setHidden("x", 1L, true)
        assertThat(store.isHidden("x", 11L)).isFalse()
        assertThat(store.isHidden("1:x", 1L)).isFalse()
    }
}
