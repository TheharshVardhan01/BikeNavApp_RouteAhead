package com.h2km33t.routeahead.route

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*

/**
 * Runs the whole live-navigation loop:
 *   GPS update -> find position on route -> extract ahead slice -> pack -> send over BLE
 *
 * Call start() once you have a route (from OsrmClient.getRoute) and a connected BLE device.
 * Call stop() when navigation ends or the app is done.
 *
 * NOTE: This class does NOT handle BLE connection/pairing - it assumes you already have
 * a connected BluetoothGatt + characteristic from your existing BikeNavApp BLE code.
 * Pass a small callback lambda so this class doesn't need to know your BLE internals.
 */
class RouteNavigationManager(
    private val context: Context,
    private val fullRoute: List<LatLng>,
    private val sendPacket: (ByteArray) -> Unit // e.g. { bytes -> gattCharacteristic.setValue(bytes); gatt.writeCharacteristic(characteristic) }
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var lastSentPacketHash: Int = 0 // avoid spamming identical packets over BLE

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            onNewLocation(location)
        }
    }

    @SuppressLint("MissingPermission") // caller must have already requested ACCESS_FINE_LOCATION
    fun start() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L // update every 1 second - fine for bike speeds, adjust if you want less frequent BLE traffic
        ).build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    fun stop() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun onNewLocation(location: Location) {
        val currentPos = LatLng(location.latitude, location.longitude)

        // location.bearing comes from GPS itself (direction of recent movement) - reliable once moving at bike speed.
        // At very low speed/stationary, bearing can be noisy/stale; fine for a moving bike, worth revisiting if you
        // add a magnetometer-based heading fallback later.
        val heading = location.bearing

        val aheadSlice = RouteTransform.extractAheadSlice(
            route = fullRoute,
            currentPos = currentPos,
            headingDegrees = heading,
            maxDistanceM = 150f
        )
        val simplified = RouteTransform.simplify(aheadSlice, maxPoints = 10)

        val (maneuver, distanceToTurn) = determineNextManeuver(currentPos, heading)

        val packet = RoutePacketBuilder.buildPacket(maneuver, distanceToTurn, simplified)

        // Simple de-dupe: don't hammer BLE with identical packets every second if nothing changed
        val packetHash = packet.contentHashCode()
        if (packetHash != lastSentPacketHash) {
            sendPacket(packet)
            lastSentPacketHash = packetHash
        }
    }

    /**
     * Placeholder maneuver detection - determines the type/distance of the NEXT turn.
     *
     * TODO: this needs real logic once you're ready - e.g. detecting where the route's
     * bearing changes significantly ahead of current position, similar in spirit to how
     * ManeuverIconAnalyzer.kt classifies maneuvers, but driven by OSRM's route geometry
     * instead of the notification icon. For now this returns a placeholder so the BLE
     * pipeline can be tested end-to-end before maneuver detection is built.
     */
    private fun determineNextManeuver(
        currentPos: LatLng,
        heading: Float
    ): Pair<RoutePacketBuilder.ManeuverType, Int> {
        return Pair(RoutePacketBuilder.ManeuverType.STRAIGHT, 0)
    }
}
