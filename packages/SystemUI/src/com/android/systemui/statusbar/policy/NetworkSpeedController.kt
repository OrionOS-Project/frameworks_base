/*
 * SPDX-FileCopyrightText: AxionOS Project
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.policy

import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.*
import android.database.ContentObserver
import android.net.*
import android.net.TrafficStats
import android.os.*
import android.provider.Settings
import android.view.ViewGroup
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.Dependency
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusIconDisplayable
import com.android.systemui.statusbar.phone.StatusBarIconHolder
import com.android.systemui.statusbar.phone.NetworkSpeedIconHolder
import com.android.systemui.statusbar.phone.StatusBarIconControllerImplEx
import com.android.systemui.statusbar.policy.networkspeed.NetworkSpeedIconState
import com.android.systemui.statusbar.policy.networkspeed.NetworkSpeedView
import kotlinx.coroutines.*

class NetworkSpeedController private constructor(
    private val context: Context
) {

    private val slotNetworkSpeed =
        context.resources.getString(R.string.status_bar_network_speed)

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val contentResolver = context.contentResolver

    private var isSwitchOn = false
    private var isConnected = false
    private var networkVisibility = false

    private var lastTime = 0L
    private var lastTotalBytes = 0L

    private var keyguardUpdateMonitor: KeyguardUpdateMonitor? = null
    private var keyguardCallback: KeyguardUpdateMonitorCallback? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var speedUpdateJob: Job? = null

    private val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateConnectionState(hasValidatedInternet(network))
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            updateConnectionState(hasValidatedInternet(network, caps))
        }

        override fun onLost(network: Network) {
            updateConnectionState(false)
        }
    }

    private val networkSpeedObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reset()
                updateSwitchState()
                restartSpeedUpdates()
            }
        }

    fun init() {
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(ICON_HIDE_LIST),
            true,
            networkSpeedObserver
        )
        networkSpeedObserver.onChange(true)

        keyguardUpdateMonitor = Dependency.get(KeyguardUpdateMonitor::class.java)
        keyguardCallback = object : KeyguardUpdateMonitorCallback() {
            override fun onUserSwitchComplete(newUserId: Int) {
                networkSpeedObserver.onChange(true)
            }
        }
        keyguardUpdateMonitor?.registerCallback(keyguardCallback)
    }

    private fun hasValidatedInternet(
            network: Network, caps: NetworkCapabilities? = null): Boolean {
        val caps = caps ?: connectivityManager.getNetworkCapabilities(network)
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun updateSwitchState() {
        val iconHideList = Settings.Secure.getStringForUser(
            contentResolver,
            ICON_HIDE_LIST,
            UserHandle.USER_CURRENT
        )
        isSwitchOn = !iconHideList.isNullOrEmpty() 
            && !iconHideList.contains(slotNetworkSpeed)
    }

    private fun updateConnectionState(connected: Boolean) {
        isConnected = connected
        restartSpeedUpdates()
    }

    private fun restartSpeedUpdates() {
        speedUpdateJob?.cancel()
        if (isConnected && isSwitchOn) {
            speedUpdateJob = scope.launch {
                while (isActive) {
                    updateNetworkSpeed()
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        } else {
            scope.launch {
                updateNetworkSpeed()
            }
        }
    }

    private suspend fun updateNetworkSpeed() {
        val currentTime = System.currentTimeMillis()
        val totalBytes = getTotalBytes()

        var speed = 0L
        if (lastTime > 0 && lastTotalBytes > 0 &&
            totalBytes > lastTotalBytes && currentTime > lastTime
        ) {
            speed = (((totalBytes - lastTotalBytes) * 1000) / (currentTime - lastTime)).toLong()
        }

        val iconState = NetworkSpeedIconState().apply {
            setVisible(isConnected && isSwitchOn && speed > AUTOHIDE_THRESHOLD)
            setSpeedText(speed)
            setSlot(slotNetworkSpeed)
        }

        withContext(Dispatchers.Main) {
            StatusBarIconControllerImplEx.get().setNetworkSpeedIcon(slotNetworkSpeed, iconState)
            if (networkVisibility != iconState.isVisible()) {
                networkVisibility = iconState.isVisible()
            }
        }

        lastTime = currentTime
        lastTotalBytes = totalBytes
    }

    private fun getTotalBytes(): Long {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        return if (rx >= 0 && tx >= 0) rx + tx else 0
    }

    private fun reset() {
        lastTime = 0
        lastTotalBytes = 0
    }

    fun isSwitchOn(): Boolean = isSwitchOn

    fun addHolder(
        index: Int,
        slot: String,
        rootGroup: ViewGroup,
        holder: StatusBarIconHolder,
        blocked: Boolean
    ): StatusIconDisplayable? {
        return StatusBarIconControllerImplEx.get()
            .addHolder(index, slot, rootGroup, holder, blocked)
    }

    fun onSetIconHolder(viewIndex: Int, holder: StatusBarIconHolder, rootGroup: ViewGroup) {
        if (holder.type == 6 && holder is NetworkSpeedIconHolder) {
            val view = rootGroup.getChildAt(viewIndex) as? NetworkSpeedView ?: return
            view.applyNetworkState(holder.getNetworkSpeedIconState())
        }
    }

    companion object {
        @Volatile
        private var instance: NetworkSpeedController? = null

        fun init(context: Context): NetworkSpeedController {
            return instance ?: synchronized(this) {
                instance ?: NetworkSpeedController(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun get(): NetworkSpeedController {
            return instance ?: throw IllegalStateException("init must be called first!!!")
        }

        const val TAG = "NetworkSpeedController"
        const val ICON_HIDE_LIST = "icon_blacklist"
        const val REFRESH_INTERVAL_MS = 4000L
        const val AUTOHIDE_THRESHOLD = 1024L // 1KB
    }
}
