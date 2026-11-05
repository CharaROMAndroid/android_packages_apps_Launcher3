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
import android.content.SharedPreferences
import android.os.UserHandle
import android.util.Log
import com.android.launcher3.pm.UserCache

/**
 * Per-user store of apps hidden from the launcher (all apps, widgets, search).
 *
 * Hiding is a visibility feature only. It does NOT stop an app from being started by other means
 * (recents, notifications, other launchers). Real access protection is delegated to the
 * framework App Lock, see [TrustAppsActivity].
 *
 * Entries are keyed by user serial number (never reused by the platform, unlike user ids) plus
 * package, so a hidden app in one profile never hides the same package in another profile.
 * Use [get] so that every consumer shares the single instance.
 */
class HiddenAppsStore(
    private val prefs: SharedPreferences,
    private val serialOf: (UserHandle) -> Long,
) {

    fun isHidden(packageName: String, user: UserHandle): Boolean = try {
        isHidden(packageName, serialOf(user))
    } catch (e: RuntimeException) {
        // Hiding is cosmetic; failing to resolve a user must not make apps unreachable.
        Log.w(TAG, "isHidden failed", e)
        false
    }

    fun isHidden(packageName: String, userSerial: Long): Boolean = try {
        userSerial >= 0 && prefs.getStringSet(KEY, emptySet())?.contains(key(packageName, userSerial)) == true
    } catch (e: RuntimeException) {
        Log.w(TAG, "isHidden failed", e)
        false
    }

    fun setHidden(packageName: String, user: UserHandle, hidden: Boolean) {
        val serial = try { serialOf(user) } catch (e: RuntimeException) { -1L }
        setHidden(packageName, serial, hidden)
    }

    @Synchronized
    fun setHidden(packageName: String, userSerial: Long, hidden: Boolean) {
        if (userSerial < 0) return // unresolved user: never write under a bogus key
        val set = HashSet(prefs.getStringSet(KEY, emptySet()) ?: emptySet())
        val k = key(packageName, userSerial)
        val changed = if (hidden) set.add(k) else set.remove(k)
        if (changed) prefs.edit().putStringSet(KEY, set).apply()
    }

    /** Drops every entry of a removed user. */
    @Synchronized
    fun clearUser(userSerial: Long) {
        val prefix = "$userSerial$SEP"
        val set = HashSet(prefs.getStringSet(KEY, emptySet()) ?: emptySet())
        if (set.removeAll { it.startsWith(prefix) }) prefs.edit().putStringSet(KEY, set).apply()
    }

    companion object {
        private const val TAG = "HiddenAppsStore"
        private const val FILE = "hidden_apps"
        private const val KEY = "hidden"
        private const val SEP = ':'

        @JvmStatic fun key(packageName: String, userSerial: Long) = "$userSerial$SEP$packageName"

        @Volatile private var instance: HiddenAppsStore? = null

        /** The single shared instance. */
        @JvmStatic
        fun get(context: Context): HiddenAppsStore =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    HiddenAppsStore(app.getSharedPreferences(FILE, Context.MODE_PRIVATE)) {
                        UserCache.getInstance(app).userManagerState.getCachedInfoOrNull(it)?.iconInfo?.userSerial ?: -1L
                    }
                }.also { instance = it }
            }
    }
}
