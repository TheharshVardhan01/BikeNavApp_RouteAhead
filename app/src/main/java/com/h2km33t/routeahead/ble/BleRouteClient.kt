package com.h2km33t.routeahead.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.h2km33t.routeahead.protocol.NavPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.UUID

/**
 * Owns the BLE link to the ESP32.
 *
 * Three things here are load-bearing and were the reason v1 didn't work on real hardware:
 *
 *  1. **MTU negotiation.** The default ATT MTU is 23 bytes, leaving 20 for the payload.
 *     A v2 nav frame is up to 85 bytes, so without requesting a larger MTU every write
 *     is silently truncated or rejected. We ask for [REQUESTED_MTU] right after service
 *     discovery and only report Connected once the stack answers.
 *
 *  2. **A write queue.** Android's GATT stack allows exactly one outstanding operation
 *     per connection. Firing a second `writeCharacteristic` before `onCharacteristicWrite`
 *     lands makes it return false and the packet vanishes. Everything goes through
 *     [operationQueue], drained one at a time.
 *
 *  3. **Auto-reconnect.** A bike goes through tunnels and the ESP32 browns out over bumps.
 *     A dropped link retries with backoff instead of needing the rider to poke the phone.
 */
class BleRouteClient(private val context: Context) {

    companion object {
        private const val TAG = "BleRouteClient"

        /** Must match SERVICE_UUID / ROUTE_CHAR_UUID / DEVICE_NAME in the ESP32 firmware. */
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val ROUTE_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        const val DEVICE_NAME = "BikeNav-RouteAhead"

        /**
         * 185 is the largest MTU iOS will grant and a common ceiling on Android too;
         * asking for more just gets negotiated back down. It leaves 182 payload bytes,
         * comfortably above NavPacket.MAX_PACKET_BYTES (85).
         */
        private const val REQUESTED_MTU = 185

        private const val SCAN_TIMEOUT_MS = 20_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val state: StateFlow<BleConnectionState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var routeCharacteristic: BluetoothGattCharacteristic? = null
    private var negotiatedMtu = 23

    /** Remembered so a dropped link can reconnect without scanning again. */
    private var lastDevice: BluetoothDevice? = null
    private var reconnectAttempt = 0

    /** True once the rider explicitly disconnected - suppresses auto-reconnect. */
    private var userDisconnected = false

    // ---------------------------------------------------------------- write queue

    private val operationQueue = ArrayDeque<ByteArray>()
    private var operationInFlight = false

    /**
     * Only the newest frame matters. If the queue backs up (weak link, phone busy) we
     * drop stale frames rather than making the device replay a queue of old turns.
     */
    private val maxQueuedPackets = 2

    // ---------------------------------------------------------------- scanning

    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // The ScanFilter already restricts this to our service UUID, but a name
            // check costs nothing and guards against another dev board flashed with
            // the same example UUIDs sitting in range.
            val name = result.device.name ?: result.scanRecord?.deviceName
            if (name != null && name != DEVICE_NAME) return

            stopScan()
            connectTo(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "A scan is already running"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Bluetooth needs restarting"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "This phone doesn't support BLE scanning"
                // Android silently throttles apps that start >5 scans in 30 seconds.
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Bluetooth is busy, try again in a moment"
                else -> "Scan failed (code $errorCode)"
            }
            _state.value = BleConnectionState.Failed(reason)
        }
    }

    /**
     * Scans for the device and connects to the first match.
     * Caller must already hold BLUETOOTH_SCAN + BLUETOOTH_CONNECT (API 31+).
     */
    @SuppressLint("MissingPermission")
    fun connect() {
        userDisconnected = false
        reconnectAttempt = 0

        val adapter = this.adapter
        if (adapter == null) {
            _state.value = BleConnectionState.Failed("This phone has no Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            _state.value = BleConnectionState.Failed("Turn Bluetooth on")
            return
        }
        if (_state.value.isReady || scanning) return

        // Reconnecting to a known device skips the scan entirely - much faster when the
        // rider restarts the app with the device still powered.
        lastDevice?.let {
            connectTo(it)
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _state.value = BleConnectionState.Failed("Bluetooth is turning on, try again")
            return
        }

        // Filtering in the scanner (not the callback) lets the Bluetooth chip do the
        // matching, which is dramatically easier on the battery during a long ride.
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        _state.value = BleConnectionState.Scanning
        scanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)

        handler.postDelayed({
            if (scanning) {
                stopScan()
                _state.value = BleConnectionState.Failed("No device found - is it powered on?")
            }
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: IllegalStateException) {
            // Adapter turned off mid-scan; nothing to stop.
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        lastDevice = device
        _state.value = BleConnectionState.Connecting

        closeGatt()
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    // ---------------------------------------------------------------- GATT callbacks

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectAttempt = 0
                    // A short settle before discovery avoids the GATT_ERROR (133) that
                    // some phones throw when discovery starts in the same breath as connect.
                    handler.postDelayed({ g.discoverServices() }, 300)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    routeCharacteristic = null
                    negotiatedMtu = 23
                    clearQueue()
                    closeGatt()

                    if (userDisconnected) {
                        _state.value = BleConnectionState.Idle
                    } else {
                        scheduleReconnect(status)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = BleConnectionState.Failed("Service discovery failed ($status)")
                g.disconnect()
                return
            }

            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                _state.value = BleConnectionState.Failed("Device is missing the RouteAhead service")
                g.disconnect()
                return
            }

            val characteristic = service.getCharacteristic(ROUTE_CHAR_UUID)
            if (characteristic == null) {
                _state.value = BleConnectionState.Failed("Device is missing the route characteristic")
                g.disconnect()
                return
            }

            routeCharacteristic = characteristic

            // Don't report Connected yet - a nav frame won't fit in the default 23-byte
            // MTU, so the link isn't actually usable until onMtuChanged comes back.
            if (!g.requestMtu(REQUESTED_MTU)) {
                Log.w(TAG, "requestMtu returned false; continuing at default MTU")
                onUsable(g, 23)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            onUsable(g, if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write failed with status $status")
            }
            operationInFlight = false
            drainQueue()
        }
    }

    @SuppressLint("MissingPermission")
    private fun onUsable(g: BluetoothGatt, mtu: Int) {
        negotiatedMtu = mtu
        val usable = mtu - 3 // 3 bytes of ATT header overhead

        if (usable < NavPacket.MAX_PACKET_BYTES) {
            // Not fatal: short frames (few route points, short street name) still fit,
            // and the packet builder emits variable-length frames. Worth surfacing though,
            // because it shows up as the route preview truncating on long straights.
            Log.w(
                TAG,
                "MTU $mtu leaves $usable payload bytes, below the " +
                        "${NavPacket.MAX_PACKET_BYTES}-byte worst case"
            )
        }

        val name = try {
            g.device.name ?: DEVICE_NAME
        } catch (e: SecurityException) {
            DEVICE_NAME
        }
        _state.value = BleConnectionState.Connected(name, mtu)
        drainQueue()
    }

    private fun scheduleReconnect(status: Int) {
        if (lastDevice == null || reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            _state.value = BleConnectionState.Failed(
                if (status == 133) "Lost connection to the device"
                else "Disconnected from the device"
            )
            return
        }

        reconnectAttempt++
        _state.value = BleConnectionState.Reconnecting(reconnectAttempt)

        // Backoff: 1s, 2s, 4s, 8s, 16s. Long enough to ride out a tunnel without
        // hammering the radio while the device is genuinely off.
        val delayMs = 1000L shl (reconnectAttempt - 1)
        handler.postDelayed({
            val device = lastDevice
            if (!userDisconnected && device != null && !_state.value.isReady) {
                connectTo(device)
            }
        }, delayMs)
    }

    // ---------------------------------------------------------------- sending

    /**
     * Queues a frame for the device. Safe to call before a connection exists - it
     * simply drops, since the navigation loop emits frames continuously anyway and
     * the next one will be along in a second.
     */
    fun send(packet: ByteArray) {
        if (!_state.value.isReady) return

        synchronized(operationQueue) {
            while (operationQueue.size >= maxQueuedPackets) operationQueue.pollFirst()
            operationQueue.addLast(packet)
        }
        handler.post { drainQueue() }
    }

    @SuppressLint("MissingPermission")
    private fun drainQueue() {
        if (operationInFlight) return

        val g = gatt ?: return
        val characteristic = routeCharacteristic ?: return
        if (!_state.value.isReady) return

        val packet = synchronized(operationQueue) { operationQueue.pollFirst() } ?: return

        operationInFlight = true
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                characteristic,
                packet,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = packet
                g.writeCharacteristic(characteristic)
            }
        }

        if (!ok) {
            // The stack refused it outright, so onCharacteristicWrite will never fire
            // and would otherwise wedge the queue forever.
            operationInFlight = false
        }
    }

    private fun clearQueue() {
        synchronized(operationQueue) { operationQueue.clear() }
        operationInFlight = false
    }

    // ---------------------------------------------------------------- teardown

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userDisconnected = true
        stopScan()
        handler.removeCallbacksAndMessages(null)
        clearQueue()
        gatt?.disconnect()
        closeGatt()
        _state.value = BleConnectionState.Idle
    }

    /** Drops the remembered device so the next connect() does a fresh scan. */
    fun forgetDevice() {
        lastDevice = null
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        // close() must always follow a connectGatt, otherwise Android leaks the client
        // interface and after ~30 connections every further connectGatt fails with 133.
        try {
            gatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close() threw", e)
        }
        gatt = null
    }
}
