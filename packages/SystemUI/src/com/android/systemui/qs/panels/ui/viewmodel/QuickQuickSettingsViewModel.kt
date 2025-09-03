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

package com.android.systemui.qs.panels.ui.viewmodel

import android.content.res.Resources
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModelFactoryProvider
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QQS
import com.android.systemui.qs.panels.domain.interactor.QuickQuickSettingsRowInteractor
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.shared.model.splitInRowsSequence
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class QuickQuickSettingsViewModel
@AssistedInject
constructor(
    tilesInteractor: CurrentTilesInteractor,
    qsColumnsViewModelFactory: QSColumnsViewModel.Factory,
    quickQuickSettingsRowInteractor: QuickQuickSettingsRowInteractor,
    mediaInRowInLandscapeViewModelFactory: MediaInRowInLandscapeViewModel.Factory,
    val squishinessViewModel: TileSquishinessViewModel,
    iconTilesViewModel: IconTilesViewModel,
    val tileHapticsViewModelFactoryProvider: TileHapticsViewModelFactoryProvider,
    @ShadeDisplayAware private val resources: Resources,
) : ExclusiveActivatable() {

    private val hydrator = Hydrator("QuickQuickSettingsViewModel")
    private val qsColumnsViewModel = qsColumnsViewModelFactory.create(LOCATION_QQS)
    private val mediaInRowViewModel = mediaInRowInLandscapeViewModelFactory.create(LOCATION_QQS)

    val columns: Int
        get() = qsColumnsViewModel.columns

    // Use QS columns for smooth transition, but allow QQS-specific override if needed
    val qqsColumns = quickQuickSettingsRowInteractor.quickColumns

    private val largeTiles by
        hydrator.hydratedStateOf(traceName = "largeTiles", source = iconTilesViewModel.largeTiles)

    val rows by derivedStateOf {
        if (mediaInRowViewModel.shouldMediaShowInRow) {
            rowsWithoutMedia * 2
        } else {
            rowsWithoutMedia
        }
    }

    private val rowsWithoutMedia by
        hydrator.hydratedStateOf(
            traceName = "rowsWithoutMedia",
            initialValue = resources.getInteger(R.integer.quick_qs_paginated_grid_num_rows),
            source = quickQuickSettingsRowInteractor.quickRows,
        )

    private val largeTilesSpan by
        hydrator.hydratedStateOf(
            traceName = "largeTilesSpan",
            source = iconTilesViewModel.largeTilesSpan,
        )

    private val currentTiles by
        hydrator.hydratedStateOf(traceName = "currentTiles", source = tilesInteractor.currentTiles)

    val tileViewModels by derivedStateOf {
        currentTiles
            .map { SizedTileImpl(TileViewModel(it.tile, it.spec), it.spec.width()) }
            .let { splitInRowsSequence(it, columns).take(rows).toList().flatten() }
    }

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch { hydrator.activate() }
            launch { qsColumnsViewModel.activate() }
            launch { mediaInRowViewModel.activate() }
            awaitCancellation()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): QuickQuickSettingsViewModel
    }

    private fun TileSpec.width(): Int = if (largeTiles.contains(this)) minOf(largeTilesSpan, columns) else 1
}
