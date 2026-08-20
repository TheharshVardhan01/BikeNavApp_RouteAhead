package com.h2km33t.routeahead.nav

import com.h2km33t.routeahead.protocol.LocalBranch
import com.h2km33t.routeahead.protocol.LocalLandmark
import com.h2km33t.routeahead.protocol.LocalPoint
import com.h2km33t.routeahead.routing.LatLng
import com.h2km33t.routeahead.routing.Maneuver
import com.h2km33t.routeahead.routing.Place
import com.h2km33t.routeahead.routing.Route

/** Overall phase of a navigation session. */
enum class NavPhase { IDLE, ROUTING, NAVIGATING, REROUTING, ARRIVED }

/**
 * The single source of truth the UI renders and the packet builder serialises.
 * One object so the phone screen and the device screen can never disagree.
 */
data class NavigationState(
    val phase: NavPhase = NavPhase.IDLE,

    val destination: Place? = null,
    val route: Route? = null,

    val position: LatLng? = null,
    val headingDeg: Float = 0f,
    val speedKmh: Float = 0f,

    val maneuver: Maneuver = Maneuver.STRAIGHT,
    val distanceToManeuverM: Int = 0,
    val maneuverStreet: String = "",
    val roundaboutExit: Int? = null,

    val remainingDistanceM: Int = 0,
    val remainingSeconds: Int = 0,

    /**
     * The road ahead in bike-relative metres (+Y forward, +X right) - the same geometry
     * sent to the ESP32, so the phone and the handlebar display show the identical shape
     * rather than two different approximations of it.
     */
    val routeAhead: List<LocalPoint> = emptyList(),

    /**
     * Map context in the same bike-relative frame: the roads branching off the route,
     * and the places beside it. Fetched once per route and re-projected each fix, so
     * the phone and the handlebar display draw the identical junction.
     */
    val branchesAhead: List<LocalBranch> = emptyList(),
    val landmarksAhead: List<LocalLandmark> = emptyList(),

    /** The maneuver after the current one, for the "then..." hint. */
    val nextManeuver: Maneuver? = null,
    val distanceToNextManeuverM: Int = 0,

    val offRoute: Boolean = false,
    val error: String? = null
) {
    val isNavigating: Boolean
        get() = phase == NavPhase.NAVIGATING || phase == NavPhase.REROUTING

    /** ETA as a wall-clock epoch millis, for "arrive at 19:42" style display. */
    val arrivalEpochMs: Long
        get() = System.currentTimeMillis() + remainingSeconds * 1000L
}

/** Formats a metre distance the way a rider reads it at a glance. */
fun formatDistance(metres: Int): String = when {
    metres < 1000 -> {
        // Below 1 km, round to something a rider can act on. Nobody turns "in 237 m".
        val rounded = when {
            metres < 20 -> metres
            metres < 200 -> (metres / 10) * 10
            else -> (metres / 50) * 50
        }
        "$rounded m"
    }
    metres < 10_000 -> String.format("%.1f km", metres / 1000.0)
    else -> "${metres / 1000} km"
}

/** Formats a duration as "12 min" / "1 h 24". */
fun formatDuration(seconds: Int): String {
    val totalMinutes = (seconds + 30) / 60
    return if (totalMinutes < 60) {
        "$totalMinutes min"
    } else {
        "${totalMinutes / 60} h ${(totalMinutes % 60).toString().padStart(2, '0')}"
    }
}

/** Human-readable instruction text, e.g. "Turn left onto MG Road". */
fun instructionText(maneuver: Maneuver, street: String, exit: Int?): String {
    val verb = when (maneuver) {
        Maneuver.STRAIGHT -> "Continue straight"
        Maneuver.LEFT -> "Turn left"
        Maneuver.RIGHT -> "Turn right"
        Maneuver.SLIGHT_LEFT -> "Keep left"
        Maneuver.SLIGHT_RIGHT -> "Keep right"
        Maneuver.SHARP_LEFT -> "Sharp left"
        Maneuver.SHARP_RIGHT -> "Sharp right"
        Maneuver.UTURN -> "Make a U-turn"
        Maneuver.ROUNDABOUT ->
            if (exit != null) "At the roundabout take exit $exit" else "Enter the roundabout"
        Maneuver.MERGE -> "Merge"
        Maneuver.FORK_LEFT -> "Take the left fork"
        Maneuver.FORK_RIGHT -> "Take the right fork"
        Maneuver.DEPART -> "Start"
        Maneuver.ARRIVE -> "Arrive"
        Maneuver.ON_RAMP -> "Take the ramp"
        Maneuver.OFF_RAMP -> "Take the exit"
    }

    if (street.isBlank()) return verb
    val preposition = when (maneuver) {
        Maneuver.ARRIVE -> "at"
        Maneuver.STRAIGHT, Maneuver.MERGE -> "on"
        Maneuver.ROUNDABOUT -> "onto"
        else -> "onto"
    }
    return "$verb $preposition $street"
}
