package com.h2km33t.routeahead.ble

/** What the connection card in the UI shows. */
sealed interface BleConnectionState {
    data object Idle : BleConnectionState
    data object Scanning : BleConnectionState
    data object Connecting : BleConnectionState
    data class Connected(val deviceName: String, val mtu: Int) : BleConnectionState
    data class Reconnecting(val attempt: Int) : BleConnectionState
    data class Failed(val reason: String) : BleConnectionState

    val isReady: Boolean get() = this is Connected
}
