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
    private val _tileShapeMode = MutableStateFlow(TileShapeMode.NORMAL)
    private val _brightnessMatchTileShape = MutableStateFlow(false)
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            updateTileShapeMode()
            updateBrightnessMatchTileShape()
        }
    }

    init {
        // Register observer for the tile shape mode setting
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_ENABLE_TILE_SQUISHING),
            false,
            settingsObserver
        )
        // Register observer for the brightness match tile shape setting
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_BRIGHTNESS_MATCH_TILE_SHAPE),
            false,
            settingsObserver
        )
        updateTileShapeMode()
        updateBrightnessMatchTileShape()
    }
    
    private fun updateTileShapeMode() {
        val modeValue = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.QS_ENABLE_TILE_SQUISHING,
            0, // Default to NORMAL (0)
            UserHandle.USER_CURRENT
        )
        _tileShapeMode.value = TileShapeMode.fromInt(modeValue)
    }
    
    private fun updateBrightnessMatchTileShape() {
        val isEnabled = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.QS_BRIGHTNESS_MATCH_TILE_SHAPE,
            0, // Default to disabled
            UserHandle.USER_CURRENT
        ) == 1
        _brightnessMatchTileShape.value = isEnabled
    }
    
    // Expose the raw squishiness value - the shape logic is handled separately
    val squishiness: StateFlow<Float> = _squishiness
    
    // Expose the brightness match tile shape setting
    val brightnessMatchTileShape: StateFlow<Boolean> = _brightnessMatchTileShape
    
    // Expose the current tile shape mode for brightness matching
    val tileShapeMode: StateFlow<TileShapeMode> = _tileShapeMode

    /**
     * Returns the effective tile state for shape calculation.
     * Based on the tile shape mode, tiles can appear normal, all round, or all squared.
     */
    fun getEffectiveTileStateForShape(actualState: Int): Int {
        return when (_tileShapeMode.value) {
            TileShapeMode.NORMAL -> actualState
            TileShapeMode.ALL_ROUND -> com.android.systemui.qs.tiles.base.shared.model.QSTileState.ActivationState.INACTIVE.legacyState
            TileShapeMode.ALL_SQUARED -> com.android.systemui.qs.tiles.base.shared.model.QSTileState.ActivationState.ACTIVE.legacyState
        }
    }

    fun setSquishinessValue(value: Float) {
        _squishiness.value = value
    }
    
    fun destroy() {
        context.contentResolver.unregisterContentObserver(settingsObserver)
    }
    
    /**
     * Enum representing the different tile shape modes
     */
    enum class TileShapeMode(val value: Int) {
        NORMAL(0),      // Normal squishy behavior based on tile state
        ALL_ROUND(1),   // All tiles appear round (inactive)
        ALL_SQUARED(2); // All tiles appear squared (active)
        
        companion object {
            fun fromInt(value: Int): TileShapeMode {
                return values().find { it.value == value } ?: NORMAL
            }
        }
    }
}
