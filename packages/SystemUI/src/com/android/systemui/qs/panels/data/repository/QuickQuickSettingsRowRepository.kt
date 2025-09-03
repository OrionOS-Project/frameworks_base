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

import android.content.res.Configuration
import android.content.res.Resources
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map

@SysUISingleton
class QuickQuickSettingsRowRepository
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    @ShadeDisplayAware private val resources: Resources,
    @ShadeDisplayAware configurationRepository: ConfigurationRepository,
    private val systemSettingsRepository: SystemSettingsRepository,
) {
    val rows =
        combine(
            systemSettingsRepository.intSetting("qs_layout_rows", 0),
            systemSettingsRepository.intSetting("qs_layout_rows_landscape", 0),
            configurationRepository.onConfigurationChange.emitOnStart()
        ) { settingValue, landscapeSettingValue, _ ->
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            
            if (isLandscape && landscapeSettingValue > 0) {
                landscapeSettingValue
            } else if (settingValue > 0) {
                settingValue
            } else {
                // Fallback to resources based on orientation
                if (isLandscape) {
                    resources.getInteger(R.integer.quick_settings_paginated_grid_num_rows_landscape)
                } else {
                    resources.getInteger(R.integer.quick_settings_paginated_grid_num_rows)
                }
            }
        }
        .mapLatest { it.coerceAtLeast(1) }
        .distinctUntilChanged()
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(),
            resources.getInteger(
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    R.integer.quick_settings_paginated_grid_num_rows_landscape
                else R.integer.quick_settings_paginated_grid_num_rows
            )
        )
    
    val quickRows =
        combine(
            systemSettingsRepository.intSetting("qqs_layout_rows", 0),
            systemSettingsRepository.intSetting("qqs_layout_rows_landscape", 0),
            configurationRepository.onConfigurationChange.emitOnStart()
        ) { settingValue, landscapeSettingValue, _ ->
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            
            if (isLandscape && landscapeSettingValue > 0) {
                landscapeSettingValue
            } else if (settingValue > 0) {
                settingValue
            } else {
                // Fallback to resources based on orientation
                if (isLandscape) {
                    resources.getInteger(R.integer.quick_qs_paginated_grid_num_rows_landscape)
                } else {
                    resources.getInteger(R.integer.quick_qs_paginated_grid_num_rows)
                }
            }
        }
        .mapLatest { it.coerceAtLeast(1) }
        .distinctUntilChanged()
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(),
            resources.getInteger(
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    R.integer.quick_qs_paginated_grid_num_rows_landscape
                else R.integer.quick_qs_paginated_grid_num_rows
            )
        )
    
    val quickColumns =
        combine(
            systemSettingsRepository.intSetting("qs_layout_columns", 0),
            systemSettingsRepository.intSetting("qs_layout_columns_landscape", 0),
            configurationRepository.onConfigurationChange.emitOnStart()
        ) { settingValue, landscapeSettingValue, _ ->
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            
            if (isLandscape && landscapeSettingValue > 0) {
                landscapeSettingValue
            } else if (settingValue > 0) {
                settingValue
            } else {
                // Fallback to resources based on orientation
                if (isLandscape) {
                    resources.getInteger(R.integer.quick_settings_infinite_grid_num_columns_landscape)
                } else {
                    resources.getInteger(R.integer.quick_settings_infinite_grid_num_columns)
                }
            }
        }
        .mapLatest { it.coerceAtLeast(1) }
        .distinctUntilChanged()
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(),
            resources.getInteger(
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    R.integer.quick_settings_infinite_grid_num_columns_landscape
                else R.integer.quick_settings_infinite_grid_num_columns
            )
        )
}
