/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.data.repository

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.android.systemui.dagger.qualifiers.Application

@SysUISingleton
class TileSquishinessRepository @Inject constructor(
    private val context: Context
) {
    private val _squishiness = MutableStateFlow(1f)
    private val _isSquishingEnabled = MutableStateFlow(true)
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            updateSquishingEnabled()
        }
    }

    init {
        // Register observer for the tile squishing setting
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_ENABLE_TILE_SQUISHING),
            false,
            settingsObserver
        )
        updateSquishingEnabled()
    }
    
    private fun updateSquishingEnabled() {
        val isEnabled = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.QS_ENABLE_TILE_SQUISHING,
            1,
            UserHandle.USER_CURRENT
        ) == 1
        _isSquishingEnabled.value = isEnabled
    }
    
    // Expose the raw squishiness value - the shape logic is handled separately
    val squishiness: StateFlow<Float> = _squishiness

    /**
     * Returns the effective tile state for shape calculation.
     * When squishing is disabled, tiles should appear as inactive (round) regardless of their actual state.
     */
    fun getEffectiveTileStateForShape(actualState: Int): Int {
        return if (_isSquishingEnabled.value) {
            actualState
        } else {
            // Return inactive state to make tiles appear round
            com.android.systemui.qs.tiles.base.shared.model.QSTileState.ActivationState.INACTIVE.legacyState
        }
    }

    fun setSquishinessValue(value: Float) {
        _squishiness.value = value
    }
    
    fun destroy() {
        context.contentResolver.unregisterContentObserver(settingsObserver)
    }
}
