package com.h2km33t.routeahead.routing

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 coordinate. Note OSRM URLs want lng,lat - the opposite order. */
data class LatLng(val lat: Double, val lng: Double)

/**
 * Turn types we can show on the device.
 *
 * The ORDER of the first nine entries is frozen: it's sent over BLE as a raw ordinal
 * and the ESP32's `ManeuverType` enum indexes into the same list. Append new types at
 * the END only - inserting in the middle silently shows the wrong icon on the device.
 */
enum class Maneuver {
    STRAIGHT,
    LEFT,
    RIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    UTURN,
    ROUNDABOUT,

    // Appended in protocol v2.
    MERGE,
    FORK_LEFT,
    FORK_RIGHT,
    DEPART,
    ARRIVE,
    ON_RAMP,
    OFF_RAMP;

    companion object {
        /**
         * Maps an OSRM `maneuver.type` + `maneuver.modifier` pair onto our icon set.
         * OSRM has far more type/modifier combinations than a 128px screen can usefully
         * distinguish, so several collapse onto the same arrow.
         */
        fun fromOsrm(type: String, modifier: String?): Maneuver {
            // Roundabouts are their own icon regardless of exit direction.
            if (type == "roundabout" || type == "rotary" ||
                type == "roundabout turn" || type == "exit roundabout" || type == "exit rotary"
            ) return ROUNDABOUT

            when (type) {
                "depart" -> return DEPART
                "arrive" -> return ARRIVE
                "merge" -> return MERGE
                "on ramp" -> return ON_RAMP
                "off ramp" -> return OFF_RAMP
                "fork" -> return when (modifier) {
                    "left", "slight left", "sharp left" -> FORK_LEFT
                    "right", "slight right", "sharp right" -> FORK_RIGHT
                    else -> STRAIGHT
                }
            }

            // "turn", "new name", "continue", "end of road", "notification" all fall
            // through to the modifier, which is what actually carries the direction.
            return when (modifier) {
                "left" -> LEFT
                "right" -> RIGHT
                "slight left" -> SLIGHT_LEFT
                "slight right" -> SLIGHT_RIGHT
                "sharp left" -> SHARP_LEFT
                "sharp right" -> SHARP_RIGHT
                "uturn" -> UTURN
                else -> STRAIGHT
            }
        }
    }
}

/**
 * One instruction along the route ("turn left onto MG Road").
 *
 * [distanceAlongRouteM] is where this step *starts*, measured from the route origin.
 * The maneuver itself happens at [distanceAlongRouteM] + [distanceM], i.e. at the END
 * of the step - that's the point we count down to on the display.
 */
data class RouteStep(
    val maneuver: Maneuver,
    val streetName: String,
    val distanceM: Double,
    val durationS: Double,
    val distanceAlongRouteM: Double,
    val roundaboutExit: Int? = null
) {
    val maneuverAtM: Double get() = distanceAlongRouteM + distanceM
}

/**
 * A full route: the polyline the device draws, plus the instruction list.
 *
 * [cumulativeM] has the same length as [polyline] and holds the distance from the
 * route origin to each vertex, so snapping a GPS fix to the line gives us "how far
 * along am I" in one lookup instead of re-summing the whole route every second.
 */
data class Route(
    val polyline: List<LatLng>,
    val steps: List<RouteStep>,
    val totalDistanceM: Double,
    val totalDurationS: Double
) {
    val cumulativeM: DoubleArray = DoubleArray(polyline.size).also { acc ->
        for (i in 1 until polyline.size) {
            acc[i] = acc[i - 1] + Geo.distanceM(polyline[i - 1], polyline[i])
        }
    }

    val isEmpty: Boolean get() = polyline.size < 2
}

/** Small geodesy helpers. Flat-earth approximations - accurate well past the few km we care about. */
object Geo {

    const val EARTH_RADIUS_M = 6371000.0

    /** Metres between two coordinates (equirectangular approximation). */
    fun distanceM(a: LatLng, b: LatLng): Double {
        val avgLat = Math.toRadians((a.lat + b.lat) / 2.0)
        val dLat = Math.toRadians(b.lat - a.lat) * EARTH_RADIUS_M
        val dLng = Math.toRadians(b.lng - a.lng) * EARTH_RADIUS_M * cos(avgLat)
        return sqrt(dLat * dLat + dLng * dLng)
    }

    /** Compass bearing from [a] to [b] in degrees, 0 = north, 90 = east. */
    fun bearingDeg(a: LatLng, b: LatLng): Double {
        val avgLat = Math.toRadians((a.lat + b.lat) / 2.0)
        val north = Math.toRadians(b.lat - a.lat)
        val east = Math.toRadians(b.lng - a.lng) * cos(avgLat)
        return (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
    }

    /** Smallest signed angle from [from] to [to], in -180..180. */
    fun angleDiffDeg(from: Double, to: Double): Double {
        var d = (to - from + 540.0) % 360.0 - 180.0
        if (abs(d) == 180.0) d = 180.0
        return d
    }

    /**
     * Projects [p] onto the segment [a]-[b] in a local metric frame centred on [a].
     *
     * Returns the fraction along the segment (clamped to 0..1) and the perpendicular
     * distance in metres. Working in metres rather than raw degrees matters: a degree
     * of longitude is ~30% shorter than a degree of latitude at 25 deg N, so a
     * degree-space projection biases every snap eastward.
     */
    fun projectOntoSegment(p: LatLng, a: LatLng, b: LatLng): Pair<Double, Double> {
        val latScale = EARTH_RADIUS_M * Math.PI / 180.0
        val lngScale = latScale * cos(Math.toRadians(a.lat))

        val ax = 0.0
        val ay = 0.0
        val bx = (b.lng - a.lng) * lngScale
        val by = (b.lat - a.lat) * latScale
        val px = (p.lng - a.lng) * lngScale
        val py = (p.lat - a.lat) * latScale

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-9) return 0.0 to sqrt(px * px + py * py)

        val t = ((px * dx + py * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = t * dx
        val cy = t * dy
        val perp = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
        return t to perp
    }

    /** Interpolates between two coordinates. [t] is 0..1. */
    fun lerp(a: LatLng, b: LatLng, t: Double) =
        LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)

    /** Moves [from] by [distanceM] along [bearingDeg]. Used to synthesise test fixes. */
    fun offset(from: LatLng, bearingDeg: Double, distanceM: Double): LatLng {
        val br = Math.toRadians(bearingDeg)
        val dNorth = cos(br) * distanceM
        val dEast = sin(br) * distanceM
        val dLat = Math.toDegrees(dNorth / EARTH_RADIUS_M)
        val dLng = Math.toDegrees(dEast / (EARTH_RADIUS_M * cos(Math.toRadians(from.lat))))
        return LatLng(from.lat + dLat, from.lng + dLng)
    }
}
