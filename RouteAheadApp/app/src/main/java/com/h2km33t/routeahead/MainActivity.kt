package com.h2km33t.routeahead

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.h2km33t.routeahead.route.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private var bleClient: BleRouteClient? = null
    private var navManager: RouteNavigationManager? = null

    private val destinationPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val lat = result.data?.getDoubleExtra("lat", 0.0) ?: return@registerForActivityResult
            val lng = result.data?.getDoubleExtra("lng", 0.0) ?: return@registerForActivityResult
            onDestinationPicked(LatLng(lat, lng))
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            setStatus("Permissions granted. Ready.")
        } else {
            setStatus("Permissions denied - GPS and BLE won't work without them.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        statusText = TextView(this).apply {
            text = "Not connected."
            textSize = 16f
        }

        val connectButton = Button(this).apply {
            text = "Connect to ESP32"
            setOnClickListener { connectToEsp32() }
        }

        val pickDestinationButton = Button(this).apply {
            text = "Pick destination"
            setOnClickListener {
                destinationPickerLauncher.launch(Intent(this@MainActivity, DestinationPickerActivity::class.java))
            }
        }

        root.addView(statusText)
        root.addView(connectButton)
        root.addView(pickDestinationButton)
        setContentView(root)

        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun connectToEsp32() {
        setStatus("Scanning for ESP32...")
        bleClient = BleRouteClient(
            context = this,
            onConnected = { runOnUiThread { setStatus("Connected to ESP32.") } },
            onDisconnected = { runOnUiThread { setStatus("Disconnected from ESP32.") } }
        )
        bleClient?.startScanAndConnect()
    }

    private fun onDestinationPicked(destination: LatLng) {
        setStatus("Fetching route...")

        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setStatus("Location permission not granted.")
            return
        }

        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location == null) {
                setStatus("No GPS fix yet - move to open sky and try again.")
                return@addOnSuccessListener
            }
            val origin = LatLng(location.latitude, location.longitude)

            // Network call - must run off the main thread
            CoroutineScope(Dispatchers.IO).launch {
                val route = OsrmClient.getRoute(origin, destination)
                runOnUiThread {
                    if (route == null) {
                        setStatus("Failed to fetch route - check internet connection.")
                    } else {
                        setStatus("Route fetched (${route.size} points). Starting navigation.")
                        startNavigation(route)
                    }
                }
            }
        }
    }

    private fun startNavigation(route: List<LatLng>) {
        val client = bleClient
        if (client == null || !client.isReady()) {
            setStatus("Route ready, but ESP32 not connected yet. Connect first.")
            return
        }

        navManager?.stop()
        navManager = RouteNavigationManager(
            context = this,
            fullRoute = route,
            sendPacket = { bytes -> client.sendPacket(bytes) }
        )
        navManager?.start()
        setStatus("Navigating...")
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        navManager?.stop()
        bleClient?.disconnect()
    }
}
