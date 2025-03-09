/*
 * Copyright 2011-2025 Mark Injerd
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
package com.pilot51.voicenotify.db

import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.pilot51.voicenotify.PreferenceHelper.DEFAULT_APP_DEFAULT_ENABLE
import com.pilot51.voicenotify.PreferenceHelper.KEY_APP_DEFAULT_ENABLE
import com.pilot51.voicenotify.PreferenceHelper.getPrefFlow
import com.pilot51.voicenotify.PreferenceHelper.setPref
import com.pilot51.voicenotify.R
import com.pilot51.voicenotify.VNApplication.Companion.appContext
import com.pilot51.voicenotify.db.AppDatabase.Companion.db
import com.pilot51.voicenotify.withTimeoutInterruptible
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.seconds

object AppRepository {
	private val TAG = AppRepository::class.simpleName
	private val ioScope = CoroutineScope(Dispatchers.IO)
	/** The default enabled value for new apps. */
	private var appDefaultEnable =
		runBlocking(Dispatchers.IO) {
			getPrefFlow(KEY_APP_DEFAULT_ENABLE, DEFAULT_APP_DEFAULT_ENABLE).first()
		}
		set(value) {
			field = value
			setPref(KEY_APP_DEFAULT_ENABLE, value)
		}
	val appsFlow = db.appDao.getAllFlow()
	val isUpdating = MutableStateFlow(false)

	/** Updates the app list from the system. */
	fun updateAppsList() {
		if (isUpdating.value) return
		isUpdating.value = true
		CoroutineScope(Dispatchers.IO).launch {
			val apps = appsFlow.first()
			val packMan = appContext.packageManager
			val installedApps = try {
				withTimeoutInterruptible(10.seconds) {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
						packMan.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
					} else {
						packMan.getInstalledApplications(0)
					}
				}
			} catch (e: TimeoutCancellationException) {
				Log.e(TAG, "Timed out fetching list of installed apps")
				return@launch
			}

			// Remove uninstalled
			apps.forEach { app ->
				if (installedApps.none { it.packageName == app.packageName }) {
					delete(app)
				}
			}

			// Add new
			for (appInfo in installedApps) {
				if (apps.any { it.packageName == appInfo.packageName }) {
					continue
				}
				val label = try {
					withTimeoutInterruptible(1.seconds) {
						appInfo.loadLabel(packMan)
					}.toString()
				} catch (e: TimeoutCancellationException) {
					Log.e(TAG, "Timed out fetching app label for package ${appInfo.packageName}")
					continue
				}
				val app = App(
					packageName = appInfo.packageName,
					label = label,
					isEnabled = appDefaultEnable
				)
				addOrUpdate(app)
			}
			isUpdating.value = false
		}
	}

	/**
	 * @param pkg Package name used to find [App] in current list or create a new one from system.
	 * @return Found or created [App], otherwise `null` if app not found on system.
	 */
	suspend fun findOrAddApp(pkg: String): App? {
		val apps = appsFlow.first()
		apps.find { it.packageName == pkg }?.let {
			return it
		}
		val appLabel = try {
			withTimeoutInterruptible(2.seconds) {
				val packMan = appContext.packageManager
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					packMan.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
				} else {
					packMan.getApplicationInfo(pkg, 0)
				}.run {
					loadLabel(packMan).toString()
				}
			}
		} catch (e: NameNotFoundException) {
			Log.w(TAG, "App not found for package $pkg")
			return null
		} catch (e: TimeoutCancellationException) {
			Log.e(TAG, "Timed out fetching app info/label for package $pkg")
			return null
		}
		return App(
			packageName = pkg,
			label = appLabel,
			isEnabled = appDefaultEnable
		).also {
			addOrUpdate(it)
		}
	}

	/** Inserts or updates [app] in database. */
	private fun addOrUpdate(app: App) {
		ioScope.launch {
			db.appDao.upsert(app)
		}
	}

	/** Sets the enabled state of [app] in the database. */
	private fun updateAppEnable(app: App, enable: Boolean) {
		ioScope.launch {
			db.appDao.updateAppEnable(app.packageName, enable)
		}
	}

	/** Removes [app] from the database. */
	private fun delete(app: App) {
		ioScope.launch {
			db.appDao.delete(app)
		}
	}

	/**
	 * Sets the enabled state of all apps as well as [appDefaultEnable].
	 * @param enable `true` to enable all apps, `false` to ignore all apps.
	 */
	fun massIgnore(enable: Boolean) {
		appDefaultEnable = enable
		ioScope.launch {
			db.appDao.updateAllEnable(enable)
		}
	}

	fun toggleIgnore(app: App) {
		updateAppEnable(app, !app.isEnabled)
		Toast.makeText(
			appContext,
			appContext.getString(
				if (app.isEnabled) R.string.app_is_ignored else R.string.app_is_not_ignored,
				app.label
			),
			Toast.LENGTH_SHORT
		).show()
	}
}
