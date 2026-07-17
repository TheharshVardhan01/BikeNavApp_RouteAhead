package com.h2km33t.routeahead.route

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import java.util.UUID

/**
 * Handles the BLE connection to the ESP32 NimBLE server defined in the firmware
 * (RouteAheadTest_v3_ble_main.cpp). Scans for the device by name, connects,
 * discovers the route characteristic, and exposes a simple sendPacket() call.
 *
 * These UUIDs must match SERVICE_UUID / ROUTE_CHAR_UUID in the ESP32 firmware exactly.
 */
class BleRouteClient(
    private val context: Context,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val ROUTE_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        const val DEVICE_NAME = "BikeNav-RouteAhead" // must match NimBLEDevice::init(...) name in firmware
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var routeCharacteristic: BluetoothGattCharacteristic? = null
    private var isConnected = false

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: return
            if (deviceName == DEVICE_NAME) {
                adapter?.bluetoothLeScanner?.stopScan(this)
                connectToDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            // Common causes: BT off, missing permission, too many scans in short time (Android throttles this)
        }
    }

    /**
     * Starts scanning for the ESP32. Caller must have already requested
     * BLUETOOTH_SCAN / BLUETOOTH_CONNECT (API 31+) or BLUETOOTH/BLUETOOTH_ADMIN (older) permissions,
     * and confirmed Bluetooth is enabled.
     */
    @SuppressLint("MissingPermission")
    fun startScanAndConnect() {
        if (adapter == null || !adapter.isEnabled) return
        adapter.bluetoothLeScanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    routeCharacteristic = null
                    onDisconnected()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service = g.getService(SERVICE_UUID) ?: return
            val characteristic = service.getCharacteristic(ROUTE_CHAR_UUID) ?: return

            routeCharacteristic = characteristic
            isConnected = true
            onConnected()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // Optional: could track write success/failure here for retry logic later.
        }
    }

    /**
     * Sends a route packet (built by RoutePacketBuilder.buildPacket) to the ESP32.
     * Safe to call even if not yet connected - it just silently no-ops, since
     * RouteNavigationManager may call this frequently before a connection exists.
     */
    @SuppressLint("MissingPermission")
    fun sendPacket(packet: ByteArray) {
        val g = gatt ?: return
        val characteristic = routeCharacteristic ?: return
        if (!isConnected) return

        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = packet
        g.writeCharacteristic(characteristic)
    }

    fun isReady(): Boolean = isConnected

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isConnected = false
    }
}
