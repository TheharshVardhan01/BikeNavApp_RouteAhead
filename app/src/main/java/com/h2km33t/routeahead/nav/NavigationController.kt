package com.h2km33t.routeahead.nav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.h2km33t.routeahead.ble.BleRouteClient
import com.h2km33t.routeahead.protocol.NavPacket
import com.h2km33t.routeahead.protocol.NavPayload
import com.h2km33t.routeahead.routing.Landmark
import com.h2km33t.routeahead.routing.LatLng
import com.h2km33t.routeahead.routing.Maneuver
import com.h2km33t.routeahead.routing.NearbyRoad
import com.h2km33t.routeahead.routing.OsrmClient
import com.h2km33t.routeahead.routing.OverpassClient
import com.h2km33t.routeahead.routing.Place
import com.h2km33t.routeahead.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Runs a navigation session: GPS in, device frames out.
 *
 * Deliberately a process-wide singleton rather than a ViewModel. The rider's phone will
 * be in a pocket with the screen off for most of a ride, so the session has to outlive
 * every Activity; [NavigationService] just keeps the process alive and shows the
 * notification, while the actual state lives here.
 */
class NavigationController(
    private val context: Context,
    val ble: BleRouteClient
) {
    companion object {
        private const val TAG = "NavigationController"

        /** How far off the line before we believe the rider has actually left the route. */
        private const val OFF_ROUTE_THRESHOLD_M = 45.0

        /** Consecutive off-route fixes before rerouting. Single fixes are usually GPS noise. */
        private const val OFF_ROUTE_FIXES = 3

        /** Don't reroute more often than this, however lost the rider is. */
        private const val REROUTE_COOLDOWN_MS = 12_000L

        /**
         * Resend the current frame at least this often even if unchanged, so the device
         * never mistakes a standstill for a dropped link. Must stay well under the
         * firmware's STALE_AFTER_MS (6 s).
         */
        private const val HEARTBEAT_MS = 3_000L

        /** Remaining distance at which we call it arrived. */
        private const val ARRIVAL_RADIUS_M = 30

        /** Below this speed GPS bearing is meaningless, so we use the road's bearing. */
        private const val MIN_SPEED_FOR_GPS_HEADING_KMH = 5f

        private const val LOCATION_INTERVAL_MS = 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val fusedLocation: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private var snapper: RouteSnapper? = null
    private var offRouteFixes = 0
    private var lastRerouteAt = 0L
    private var lastSentPacket: ByteArray? = null
    private var lastSentAt = 0L
    private var lastPayload: NavPayload? = null
    private var heartbeatJob: Job? = null
    private var routeJob: Job? = null
    private var contextJob: Job? = null

    /**
     * Map context for the current route, in world coordinates.
     *
     * Fetched once when a route is applied and re-projected into the bike's frame on
     * every fix. Overpass is a shared public instance; querying it at 1 Hz would be
     * both rude and far too slow to keep up with the display.
     */
    private var routeLandmarks: List<Landmark> = emptyList()
    private var routeBranches: List<NearbyRoad> = emptyList()

    /** True while location updates are registered. */
    private var trackingLocation = false

    // ---------------------------------------------------------------- session control

    /**
     * Starts a session to [destination]. Fetches the route from the rider's current
     * position, then begins the live loop.
     */
    @SuppressLint("MissingPermission")
    fun startNavigation(destination: Place) {
        routeJob?.cancel()
        _state.update {
            it.copy(
                phase = NavPhase.ROUTING,
                destination = destination,
                route = null,
                error = null,
                offRoute = false
            )
        }

        routeJob = scope.launch {
            val origin = _state.value.position ?: awaitFirstFix()
            if (origin == null) {
                _state.update {
                    it.copy(phase = NavPhase.IDLE, error = "No GPS fix yet - move somewhere with open sky")
                }
                return@launch
            }

            when (val result = OsrmClient.getRoute(origin, destination.position)) {
                is OsrmClient.Result.Success -> {
                    applyRoute(result.route)
                    startLocationUpdates()
                }
                is OsrmClient.Result.Failure -> {
                    _state.update { it.copy(phase = NavPhase.IDLE, error = result.message) }
                }
            }
        }
    }

    /** Ends the session and tells the device to go back to its idle screen. */
    fun stopNavigation() {
        routeJob?.cancel()
        routeJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        contextJob?.cancel()
        contextJob = null
        lastPayload = null
        snapper = null
        offRouteFixes = 0
        lastSentPacket = null
        routeLandmarks = emptyList()
        routeBranches = emptyList()

        _state.update {
            NavigationState(
                phase = NavPhase.IDLE,
                position = it.position,
                headingDeg = it.headingDeg
            )
        }
        // One final frame so the device clears its screen instead of freezing on the
        // last turn it was shown.
        pushToDevice(NavPayload(hasRoute = false))
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * Resends the current frame on a fixed cadence.
     *
     * Piggy-backing the heartbeat on location updates was not enough: indoors the fused
     * provider can take 6+ seconds between fixes even when asked for 1 s, which is longer
     * than the firmware's stale window, so the device kept flapping to "No signal" while
     * perfectly connected. This ticks independently of GPS.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                val payload = lastPayload ?: continue
                if (!_state.value.isNavigating) continue
                if (System.currentTimeMillis() - lastSentAt >= HEARTBEAT_MS) {
                    pushToDevice(payload)
                }
            }
        }
    }

    private fun applyRoute(route: Route) {
        snapper = RouteSnapper(route)
        offRouteFixes = 0
        startHeartbeat()
        fetchMapContext(route)
        _state.update {
            it.copy(
                phase = NavPhase.NAVIGATING,
                route = route,
                offRoute = false,
                error = null,
                remainingDistanceM = route.totalDistanceM.toInt(),
                remainingSeconds = route.totalDurationS.toInt()
            )
        }
    }

    /**
     * Pulls the side roads and places along [route] in the background.
     *
     * Deliberately fire-and-forget: navigation starts the instant the route arrives and
     * never waits on this. If Overpass is slow or down the rider gets a bare route line,
     * which is exactly what they had before - the map context appears a few seconds
     * later, or not at all, and nothing else is affected either way.
     */
    private fun fetchMapContext(route: Route) {
        contextJob?.cancel()
        routeLandmarks = emptyList()
        routeBranches = emptyList()

        contextJob = scope.launch {
            val branches = OverpassClient.roadsAlong(route)
            routeBranches = branches
            val landmarks = OverpassClient.landmarksAlong(route)
            routeLandmarks = landmarks
            Log.i(TAG, "Map context: ${branches.size} side roads, ${landmarks.size} places")
        }
    }

    // ---------------------------------------------------------------- location

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onLocation)
        }
    }

    /**
     * Begins location updates. Called when a session starts, and also on app open so the
     * map has somewhere to centre and routing has an origin ready.
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (trackingLocation) return
        trackingLocation = true

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            trackingLocation = false
            _state.update { it.copy(error = "Location permission is required to navigate") }
        }
    }

    fun stopLocationUpdates() {
        if (!trackingLocation) return
        trackingLocation = false
        fusedLocation.removeLocationUpdates(locationCallback)
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitFirstFix(): LatLng? {
        startLocationUpdates()
        // Give the GPS a few seconds to produce something before giving up. lastLocation
        // is often instant from the system cache; a cold fix outdoors takes a few seconds.
        repeat(40) {
            _state.value.position?.let { return it }
            kotlinx.coroutines.delay(250)
        }
        return null
    }

    /** The core per-fix update. Everything the device shows is derived here. */
    private fun onLocation(location: Location) {
        val position = LatLng(location.latitude, location.longitude)
        val speedKmh = (location.speed * 3.6f).coerceAtLeast(0f)

        val current = _state.value
        val route = current.route
        val snapper = this.snapper

        if (route == null || snapper == null || !current.isNavigating) {
            // Not navigating - still track position so the map and routing origin are live.
            _state.update {
                it.copy(
                    position = position,
                    speedKmh = speedKmh,
                    headingDeg = if (location.hasBearing()) location.bearing else it.headingDeg
                )
            }
            return
        }

        val snap = snapper.snap(position)

        // GPS bearing is only trustworthy while moving. Standing at a junction it goes
        // stale or reports 0, which would swing the route preview on the device around
        // for no reason - so below a walking pace we use the road's own bearing.
        val heading = if (speedKmh >= MIN_SPEED_FOR_GPS_HEADING_KMH && location.hasBearing()) {
            location.bearing
        } else {
            snapper.routeBearingAt(snap)
        }

        val offRoute = handleOffRoute(snap.lateralErrorM, position)

        val stepIndex = route.stepAt(snap.distanceAlongM)
        val step = route.steps.getOrNull(stepIndex)

        val distanceToManeuver = if (step != null) {
            (step.maneuverAtM - snap.distanceAlongM).coerceAtLeast(0.0).toInt()
        } else 0

        val remainingDistance = (route.totalDistanceM - snap.distanceAlongM)
            .coerceAtLeast(0.0).toInt()
        val remainingSeconds = remainingDuration(route, stepIndex, snap.distanceAlongM).toInt()

        val arrived = remainingDistance <= ARRIVAL_RADIUS_M

        // Announce the street the rider is turning ONTO, not the one they're on - that's
        // the one they need to look for on a signpost.
        val upcomingStreet = route.steps.getOrNull(stepIndex + 1)?.streetName
            ?: step?.streetName.orEmpty()

        val aheadSlice = RouteTransform.aheadSlice(route, snap, heading)

        // The map context around the bike. Cheap arithmetic over a cached list - the
        // network work happened once, when the route was applied.
        val branchesAhead =
            RouteTransform.localBranches(snap.position, heading, routeBranches)
        val landmarksAhead =
            RouteTransform.localLandmarks(snap.position, heading, routeLandmarks)

        // The step after this one drives the "then left" hint, so the rider can see a
        // quick second turn coming instead of being surprised by it.
        val followingStep = route.steps.getOrNull(stepIndex + 1)

        val newState = current.copy(
            phase = when {
                arrived -> NavPhase.ARRIVED
                offRoute -> NavPhase.REROUTING
                else -> NavPhase.NAVIGATING
            },
            position = position,
            headingDeg = heading,
            speedKmh = speedKmh,
            maneuver = if (arrived) Maneuver.ARRIVE else step?.maneuver ?: Maneuver.STRAIGHT,
            distanceToManeuverM = distanceToManeuver,
            maneuverStreet = upcomingStreet,
            roundaboutExit = step?.roundaboutExit,
            remainingDistanceM = remainingDistance,
            remainingSeconds = remainingSeconds,
            routeAhead = aheadSlice,
            branchesAhead = branchesAhead,
            landmarksAhead = landmarksAhead,
            nextManeuver = followingStep?.maneuver,
            distanceToNextManeuverM = followingStep?.distanceM?.toInt() ?: 0,
            offRoute = offRoute
        )
        _state.value = newState

        if (arrived) {
            pushToDevice(
                NavPayload(
                    maneuver = Maneuver.ARRIVE,
                    speedKmh = speedKmh,
                    streetName = newState.destination?.name.orEmpty(),
                    hasRoute = true,
                    arrived = true
                )
            )
            stopLocationUpdates()
            return
        }

        pushToDevice(
            NavPayload(
                maneuver = newState.maneuver,
                distanceToManeuverM = distanceToManeuver,
                speedKmh = speedKmh,
                etaSeconds = remainingSeconds,
                remainingDistanceM = remainingDistance,
                streetName = upcomingStreet,
                routeAhead = aheadSlice,
                branches = branchesAhead,
                landmarks = landmarksAhead,
                hasRoute = true,
                offRoute = offRoute,
                rerouting = newState.phase == NavPhase.REROUTING
            )
        )
    }

    /**
     * Remaining travel time.
     *
     * Summing the durations of the steps still to come (plus the unfinished fraction of
     * the current one) keeps the ETA consistent with OSRM's own speed assumptions per
     * road type. Scaling a single average over the whole route makes the ETA lurch every
     * time the rider moves between a village lane and a highway.
     */
    private fun remainingDuration(route: Route, stepIndex: Int, distanceAlongM: Double): Double {
        if (stepIndex < 0 || route.steps.isEmpty()) {
            // No step data - fall back to the route's overall average speed.
            val fraction = (route.totalDistanceM - distanceAlongM) / route.totalDistanceM
            return route.totalDurationS * fraction.coerceIn(0.0, 1.0)
        }

        val step = route.steps[stepIndex]
        val stepRemaining = (step.maneuverAtM - distanceAlongM).coerceAtLeast(0.0)
        val stepFraction = if (step.distanceM > 1.0) stepRemaining / step.distanceM else 0.0

        var total = step.durationS * stepFraction.coerceIn(0.0, 1.0)
        for (i in stepIndex + 1 until route.steps.size) {
            total += route.steps[i].durationS
        }
        return total
    }

    /** Returns true while the rider is considered off-route. Triggers a reroute. */
    private fun handleOffRoute(lateralErrorM: Double, position: LatLng): Boolean {
        if (lateralErrorM <= OFF_ROUTE_THRESHOLD_M) {
            offRouteFixes = 0
            return false
        }

        offRouteFixes++
        if (offRouteFixes < OFF_ROUTE_FIXES) return false

        val now = System.currentTimeMillis()
        if (now - lastRerouteAt < REROUTE_COOLDOWN_MS) return true
        lastRerouteAt = now

        val destination = _state.value.destination ?: return true
        Log.i(TAG, "Off route by ${lateralErrorM.toInt()} m - rerouting")

        routeJob?.cancel()
        routeJob = scope.launch {
            when (val result = OsrmClient.getRoute(position, destination.position)) {
                is OsrmClient.Result.Success -> applyRoute(result.route)
                is OsrmClient.Result.Failure ->
                    // Keep the old route on screen rather than blanking the device; the
                    // rider may well rejoin it, and the cooldown will retry shortly.
                    Log.w(TAG, "Reroute failed: ${result.message}")
            }
        }
        return true
    }

    // ---------------------------------------------------------------- device output

    private fun pushToDevice(payload: NavPayload) {
        lastPayload = payload
        val packet = NavPacket.build(payload)

        // Skip byte-identical frames to keep BLE quiet - but not indefinitely. The device
        // treats silence longer than its stale window (6 s) as a dropped phone and shows
        // "No signal". At a standstill nothing changes, so without a heartbeat the device
        // flaps in and out of that state. Resend at least every HEARTBEAT_MS even when the
        // frame is identical, which keeps the device's lastPacketMs fresh.
        val now = System.currentTimeMillis()
        val identical = lastSentPacket?.contentEquals(packet) == true
        if (identical && now - lastSentAt < HEARTBEAT_MS) return

        lastSentPacket = packet
        lastSentAt = now
        ble.send(packet)
    }
}
