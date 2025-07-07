/*
 * SPDX-FileCopyrightText: AxionOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.policy.networkspeed

import java.util.Objects

class NetworkSpeedIconState {

    private var mVisible: Boolean = false
    private var mSpeedText: Long = 0
    private var mSlot: String? = null

    override fun equals(other: Any?): Boolean {
        if (other !is NetworkSpeedIconState) return false
        return mVisible == other.mVisible &&
               mSpeedText == other.mSpeedText &&
               mSlot == other.mSlot
    }

    override fun hashCode(): Int {
        return Objects.hash(mVisible, mSpeedText, mSlot)
    }

    fun setSpeedText(speed: Long) {
        mSpeedText = speed
    }

    fun getSpeedText(): Long {
        return mSpeedText
    }

    private fun copyTo(target: NetworkSpeedIconState) {
        target.mVisible = this.mVisible
        target.mSpeedText = this.mSpeedText
        target.mSlot = this.mSlot
    }

    fun setVisible(visible: Boolean) {
        if (mVisible != visible) {
            mVisible = visible
        }
    }

    fun isVisible(): Boolean {
        return mVisible
    }

    fun copy(): NetworkSpeedIconState {
        val copy = NetworkSpeedIconState()
        copyTo(copy)
        return copy
    }

    fun setSlot(slot: String?) {
        mSlot = slot
    }

    override fun toString(): String {
        return "NetworkSpeedIconState(slot: $mSlot, visible: $mVisible, speed: $mSpeedText)"
    }
}
