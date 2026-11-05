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

import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.LauncherApps
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.ORDERED_BG_EXECUTOR

/**
 * Manages per-user hidden apps and offers a shortcut to the framework App Lock.
 *
 * Security model:
 *  - The screen reveals nothing until the device credential/biometric prompt succeeds, every time
 *    the activity is shown again after being stopped. No secure lock screen means no access
 *    (fail closed; the old Lineage code allowed it).
 *  - Hiding only affects the launcher UI. Protecting an app from being opened is delegated to the
 *    framework App Lock via the system-provided PendingIntent, so it also holds for recents,
 *    notifications and other launchers. The launcher never enforces auth on its own.
 *  - App Lock is only offered for apps in the launcher's own user.
 */
class TrustAppsActivity : Activity() {

    private data class Row(
        val label: String,
        val displayLabel: String,
        val packageName: String,
        val user: UserHandle,
        val userSerial: Long,
        val appLockSupported: Boolean,
        val appLockEnabled: Boolean,
    )

    private lateinit var store: HiddenAppsStore
    private lateinit var list: ListView
    private val rows = ArrayList<Row>()
    private var authenticated = false
    private var authenticating = false

    /**
     * Bumped every time authentication is invalidated (stop) or granted. Background results and
     * toggle callbacks capture it and are dropped when it no longer matches, so nothing computed
     * or requested in a previous authenticated session can surface in a later one.
     */
    private var generation = 0L
    private var cancel: CancellationSignal? = null
    private var reloadNeeded = false

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_trust_app, parent, false)
            val row = rows[position]
            v.findViewById<TextView>(R.id.trust_app_label).text = row.displayLabel
            val sw = v.findViewById<Switch>(R.id.trust_app_hidden)
            sw.setOnCheckedChangeListener(null)
            val hiddenNow = store.isHidden(row.packageName, row.userSerial)
            sw.isChecked = hiddenNow
            sw.contentDescription = getString(
                if (hiddenNow) R.string.trust_apps_switch_hidden_desc
                else R.string.trust_apps_switch_visible_desc,
                row.displayLabel
            )
            sw.setOnCheckedChangeListener { _, checked ->
                if (!authenticated) return@setOnCheckedChangeListener
                store.setHidden(row.packageName, row.userSerial, checked)
                reloadNeeded = true
                sw.contentDescription = getString(
                    if (checked) R.string.trust_apps_switch_hidden_desc
                    else R.string.trust_apps_switch_visible_desc,
                    row.displayLabel
                )
            }
            val lock = v.findViewById<Button>(R.id.trust_app_lock)
            val canLock = row.appLockSupported && row.user == android.os.Process.myUserHandle()
            lock.visibility = if (canLock) View.VISIBLE else View.GONE
            lock.setText(if (row.appLockEnabled) R.string.disable_app_lock else R.string.enable_app_lock)
            lock.contentDescription = getString(
                if (row.appLockEnabled) R.string.disable_app_lock else R.string.enable_app_lock
            ) + ", " + row.displayLabel
            lock.setOnClickListener { toggleAppLock(row) }
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the list out of screenshots and the recents thumbnail.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setTitle(R.string.trust_apps_manager_name)
        store = HiddenAppsStore.get(this)
        setContentView(R.layout.activity_trust_apps)
        list = findViewById(R.id.trust_apps_list)
        list.adapter = adapter
        list.visibility = View.INVISIBLE
    }

    override fun onStart() {
        super.onStart()
        if (!authenticated) authenticate()
    }

    override fun onStop() {
        super.onStop()
        cancel?.cancel()
        cancel = null
        authenticating = false
        // Always re-authenticate when coming back; also drop the data from memory.
        authenticated = false
        generation++
        list.visibility = View.INVISIBLE
        rows.clear()
        adapter.notifyDataSetChanged()
        if (reloadNeeded) {
            reloadNeeded = false
            LauncherAppState.getInstance(this).model.reloadIfActive("hidden-apps-changed")
        }
    }

    private fun authenticate() {
        if (authenticating) return
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            Toast.makeText(this, R.string.trust_apps_no_lock_error, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        authenticating = true
        val signal = CancellationSignal().also { cancel = it }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.trust_apps_auth_manager))
            .setAllowedAuthenticators(
                Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(signal, MAIN_EXECUTOR, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                // Ignore late callbacks from a cancelled prompt or a destroyed activity.
                if (signal.isCanceled || isFinishing || isDestroyed) return
                authenticating = false
                authenticated = true
                generation++
                loadApps(generation)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                if (signal.isCanceled) return
                authenticating = false
                if (!isFinishing && !isDestroyed) finish()
            }
        })
    }

    private fun loadApps(gen: Long) {
        val ctx: Context = applicationContext
        ORDERED_BG_EXECUTOR.execute {
            val result = try {
                queryApps(ctx)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Failed to load apps", e)
                null
            }
            MAIN_EXECUTOR.execute {
                // Only publish into the exact authenticated session that asked for it.
                if (!isCurrent(gen)) return@execute
                if (result == null) {
                    Toast.makeText(this, R.string.trust_apps_action_failed, Toast.LENGTH_SHORT).show()
                    finish()
                    return@execute
                }
                rows.clear()
                rows.addAll(result)
                adapter.notifyDataSetChanged()
                list.visibility = View.VISIBLE
            }
        }
    }

    private fun isCurrent(gen: Long) =
        authenticated && gen == generation && !isFinishing && !isDestroyed

    private fun queryApps(ctx: Context): List<Row> {
        val um = ctx.getSystemService(UserManager::class.java)!!
        val la = ctx.getSystemService(LauncherApps::class.java)!!
        val cache = UserCache.getInstance(ctx)
        val result = ArrayList<Row>()
        for (user in um.userProfiles) {
            if (!um.isUserUnlocked(user)) continue
            // Private space has its own lock; never list it here.
            if (cache.isPrivate(user)) continue
            val serial = cache.getSerialNumberForUser(user)
            val isWork = cache.isWork(user)
            for (info in la.getActivityList(null, user)) {
                val ai = info.applicationInfo
                val supported = android.security.Flags.appLockApis() && ai.isAppLockSupported
                val label = info.label.toString()
                val shown = if (isWork) {
                    ctx.getString(R.string.trust_apps_profile_label, label, ctx.getString(R.string.all_apps_work_tab))
                } else label
                result.add(
                    Row(label, shown, info.componentName.packageName, user, serial,
                        supported, supported && ai.isAppLockEnabled)
                )
            }
        }
        return result
            .distinctBy { it.packageName to it.userSerial }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.userSerial }))
    }

    private fun toggleAppLock(row: Row) {
        if (!authenticated) return
        val gen = generation
        ORDERED_BG_EXECUTOR.execute {
            val intent = try {
                packageManager.getEnableAppLockIntentForPackage(row.packageName, !row.appLockEnabled)
            } catch (e: RuntimeException) {
                Log.e(TAG, "App Lock intent failed", e)
                null
            }
            MAIN_EXECUTOR.execute {
                // A stale request from an earlier authenticated session must never fire.
                if (!isCurrent(gen)) return@execute
                try {
                    if (intent == null) throw IllegalStateException("no intent")
                    // The framework shows its own confirmation; state refreshes on next open.
                    intent.send()
                } catch (e: PendingIntent.CanceledException) {
                    Log.w(TAG, "App Lock intent cancelled", e)
                    Toast.makeText(this, R.string.trust_apps_action_failed, Toast.LENGTH_SHORT).show()
                } catch (e: RuntimeException) {
                    Toast.makeText(this, R.string.trust_apps_action_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private companion object {
        const val TAG = "TrustAppsActivity"
    }
}
