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

package com.android.systemui.qs.panels.domain.interactor

import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.data.repository.IconAndNameCustomRepository
import com.android.systemui.qs.panels.data.repository.StockTilesRepository
import com.android.systemui.qs.panels.domain.model.EditTilesModel
import com.android.systemui.qs.panels.shared.model.EditTileData
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfigProvider
import com.android.systemui.res.R
import javax.inject.Inject

@SysUISingleton
class EditTilesListInteractor
@Inject
constructor(
    private val stockTilesRepository: StockTilesRepository,
    private val qsTileConfigProvider: QSTileConfigProvider,
    private val iconAndNameCustomRepository: IconAndNameCustomRepository,
) {

    private val customTileMap: Map<String, CustomTileConfig> = mapOf(
        "cell" to CustomTileConfig(R.drawable.ic_swap_vert, R.string.mobile_data, TileCategory.CONNECTIVITY),
        "wifi" to CustomTileConfig(R.drawable.ic_qs_category_connectivty, R.string.quick_settings_wifi_label, TileCategory.CONNECTIVITY),
        "vpn" to CustomTileConfig(R.drawable.ic_qs_vpn, R.string.quick_settings_vpn_label, TileCategory.CONNECTIVITY),
        "usb_tether" to CustomTileConfig(R.drawable.ic_qs_usb_tether, R.string.quick_settings_usb_tether_label, TileCategory.CONNECTIVITY),
        "sync" to CustomTileConfig(R.drawable.ic_qs_sync, R.string.quick_settings_sync_label, TileCategory.CONNECTIVITY),
        "nfc" to CustomTileConfig(R.drawable.ic_qs_nfc, R.string.quick_settings_nfc_label, TileCategory.CONNECTIVITY),
        "aod" to CustomTileConfig(R.drawable.ic_qs_aod, R.string.quick_settings_aod_label, TileCategory.UTILITIES),
        "caffeine" to CustomTileConfig(R.drawable.ic_qs_caffeine, R.string.quick_settings_caffeine_label, TileCategory.UTILITIES),
        "powershare" to CustomTileConfig(R.drawable.ic_qs_powershare, R.string.quick_settings_powershare_label, TileCategory.UTILITIES),
        "profiles" to CustomTileConfig(R.drawable.ic_qs_profiles, R.string.quick_settings_profiles_label, TileCategory.UTILITIES),
        "ambient_display" to CustomTileConfig(R.drawable.ic_qs_ambient_display, R.string.quick_settings_ambient_display_label, TileCategory.DISPLAY),
        "heads_up" to CustomTileConfig(R.drawable.ic_qs_heads_up, R.string.quick_settings_heads_up_label, TileCategory.DISPLAY),
        "reading_mode" to CustomTileConfig(R.drawable.ic_qs_reader, R.string.quick_settings_reading_mode, TileCategory.DISPLAY)
    )

    private data class CustomTileConfig(
        val iconRes: Int,
        val labelRes: Int,
        val category: TileCategory
    )

    /**
     * Provides a list of the tiles to edit, with their UI information (icon, labels).
     *
     * The icons have the label as their content description.
     */
    suspend fun getTilesToEdit(): EditTilesModel {
        val stockTiles = stockTilesRepository.stockTiles.map { tile ->
            if (qsTileConfigProvider.hasConfig(tile.spec)) {
                val config = qsTileConfigProvider.getConfig(tile.spec)
                EditTileData(
                    tile,
                    Icon.Resource(
                        config.uiConfig.iconRes,
                        ContentDescription.Resource(config.uiConfig.labelRes),
                    ),
                    Text.Resource(config.uiConfig.labelRes),
                    null,
                    category = config.category,
                )
            } else {
                val fallback = customTileMap[tile.spec]
                val iconRes = fallback?.iconRes ?: android.R.drawable.star_on
                val category = fallback?.category ?: TileCategory.UNKNOWN

                EditTileData(
                    tile,
                    Icon.Resource(iconRes, ContentDescription.Loaded(tile.spec)),
                    if (fallback != null) {
                        Text.Resource(fallback.labelRes)
                    } else {
                        Text.Loaded(tile.spec)
                    },
                    null,
                    category,
                )
            }
        }
        return EditTilesModel(stockTiles, iconAndNameCustomRepository.getCustomTileData())
    }
}
