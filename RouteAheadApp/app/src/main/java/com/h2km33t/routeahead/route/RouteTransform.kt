package com.h2km33t.routeahead.route

import kotlin.math.*

/**
 * A point in the ESP32's coordinate system: metres relative to the bike,
 * rotated so +Y = straight ahead (current heading), +X = right of travel.
 * This matches RoutePoint in the ESP32 firmware exactly.
 */
data class LocalPoint(val x: Float, val y: Float)

object RouteTransform {

    private const val EARTH_RADIUS_M = 6371000.0

    /**
     * Converts a lat/lng offset from an origin into flat local metres (equirectangular approx).
     * Fine for distances under a few km - no need for anything fancier here.
     */
    private fun toLocalMetres(origin: LatLng, point: LatLng): LocalPoint {
        val dLat = Math.toRadians(point.lat - origin.lat)
        val dLng = Math.toRadians(point.lng - origin.lng)
        val avgLatRad = Math.toRadians((origin.lat + point.lat) / 2.0)

        val northMetres = dLat * EARTH_RADIUS_M
        val eastMetres = dLng * EARTH_RADIUS_M * cos(avgLatRad)

        // Note: this is "east/north" local frame, not yet rotated to heading - rotation happens next.
        return LocalPoint(eastMetres.toFloat(), northMetres.toFloat())
    }

    /**
     * Rotates an east/north point into heading-relative x/y.
     * headingDegrees: current direction of travel, 0 = north, 90 = east (standard compass bearing).
     */
    private fun rotateToHeading(point: LocalPoint, headingDegrees: Float): LocalPoint {
        val headingRad = Math.toRadians(headingDegrees.toDouble())
        val cosH = cos(headingRad)
        val sinH = sin(headingRad)

        // Rotate east/north into forward/right relative to heading
        val forward = point.x * sinH + point.y * cosH
        val right = point.x * cosH - point.y * sinH

        return LocalPoint(right.toFloat(), forward.toFloat())
    }

    /**
     * Finds the index of the route point closest to the current GPS position.
     * Simple nearest-neighbour search - fine for route sizes in the hundreds/low thousands of points.
     */
    fun findNearestPointIndex(route: List<LatLng>, currentPos: LatLng): Int {
        var bestIndex = 0
        var bestDistSq = Double.MAX_VALUE

        for (i in route.indices) {
            val dLat = route[i].lat - currentPos.lat
            val dLng = route[i].lng - currentPos.lng
            val distSq = dLat * dLat + dLng * dLng // squared distance is fine for comparison, cheaper than sqrt
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestIndex = i
            }
        }
        return bestIndex
    }

    /**
     * Extracts the next `maxDistanceM` metres of route ahead of the current position,
     * and returns it as heading-relative local points ready to send to the ESP32.
     *
     * @param route full route polyline from OSRM
     * @param currentPos current GPS fix
     * @param headingDegrees current compass heading (from GPS bearing or magnetometer)
     * @param maxDistanceM how far ahead to include (e.g. 150.0)
     */
    fun extractAheadSlice(
        route: List<LatLng>,
        currentPos: LatLng,
        headingDegrees: Float,
        maxDistanceM: Float = 150f
    ): List<LocalPoint> {
        if (route.isEmpty()) return emptyList()

        val startIndex = findNearestPointIndex(route, currentPos)
        val result = mutableListOf<LocalPoint>()

        for (i in startIndex until route.size) {
            val localFlat = toLocalMetres(currentPos, route[i])
            val rotated = rotateToHeading(localFlat, headingDegrees)

            // Stop once we've covered enough forward distance
            if (rotated.y > maxDistanceM) break

            // Skip points behind us (shouldn't normally happen once we start at nearest index, but GPS noise can cause it)
            if (rotated.y < -5f) continue

            result.add(rotated)
        }
        return result
    }

    /**
     * Reduces a dense list of points down to at most `maxPoints`, keeping the overall shape.
     * Simple approach: even-interval decimation. (Douglas-Peucker would preserve corners better,
     * but this is far simpler to reason about and good enough for a 10-12 point BLE payload.)
     */
    fun simplify(points: List<LocalPoint>, maxPoints: Int = 10): List<LocalPoint> {
        if (points.size <= maxPoints) return points

        val step = points.size.toFloat() / maxPoints
        val result = mutableListOf<LocalPoint>()
        var i = 0f
        while (result.size < maxPoints) {
            result.add(points[i.toInt()])
            i += step
        }
        return result
    }
}
