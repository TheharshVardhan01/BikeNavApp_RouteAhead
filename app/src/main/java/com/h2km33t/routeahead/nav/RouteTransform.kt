package com.h2km33t.routeahead.nav

import com.h2km33t.routeahead.protocol.LocalBranch
import com.h2km33t.routeahead.protocol.LocalLandmark
import com.h2km33t.routeahead.protocol.LocalPoint
import com.h2km33t.routeahead.protocol.NavPacket
import com.h2km33t.routeahead.routing.Geo
import com.h2km33t.routeahead.routing.Landmark
import com.h2km33t.routeahead.routing.LatLng
import com.h2km33t.routeahead.routing.NearbyRoad
import com.h2km33t.routeahead.routing.Route
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Turns "where am I on this route" into the little heading-up polyline the device draws.
 */
object RouteTransform {

    /**
     * Converts a coordinate into metres east/north of [origin], then rotates that into
     * the bike's frame: +Y straight ahead, +X to the right of travel.
     */
    fun toLocal(origin: LatLng, point: LatLng, headingDeg: Float): LocalPoint {
        val avgLatRad = Math.toRadians((origin.lat + point.lat) / 2.0)
        val north = Math.toRadians(point.lat - origin.lat) * Geo.EARTH_RADIUS_M
        val east = Math.toRadians(point.lng - origin.lng) * Geo.EARTH_RADIUS_M * cos(avgLatRad)

        val h = Math.toRadians(headingDeg.toDouble())
        val cosH = cos(h)
        val sinH = sin(h)

        val forward = east * sinH + north * cosH
        val right = east * cosH - north * sinH
        return LocalPoint(right.toFloat(), forward.toFloat())
    }

    /**
     * Takes the next [maxDistanceM] metres of route ahead of the rider and returns it in
     * device coordinates, ready to pack.
     *
     * Unlike the v1 version this walks forward from the snapped position rather than the
     * nearest vertex, and starts the slice at the rider's own position - so the line on
     * screen always begins at the bike marker instead of jumping to whichever vertex
     * happened to be closest.
     */
    fun aheadSlice(
        route: Route,
        snapped: SnapResult,
        headingDeg: Float,
        maxDistanceM: Double = 250.0,
        maxPoints: Int = NavPacket.MAX_ROUTE_POINTS
    ): List<LocalPoint> {
        if (route.isEmpty) return emptyList()

        val origin = snapped.position
        val points = ArrayList<LatLng>()
        points.add(origin)

        val cutoff = snapped.distanceAlongM + maxDistanceM
        var i = snapped.segmentIndex + 1
        while (i < route.polyline.size && route.cumulativeM[i] <= cutoff) {
            points.add(route.polyline[i])
            i++
        }
        // Include one vertex past the cutoff so the drawn line reaches the edge of the
        // screen instead of stopping short at the last vertex inside the window.
        if (i < route.polyline.size) points.add(route.polyline[i])

        return simplify(points, maxPoints).map { toLocal(origin, it, headingDeg) }
    }

    /**
     * How far from the bike map context is still worth sending.
     *
     * Matched to the 250 m slice [aheadSlice] takes: anything beyond that would be
     * projected outside the drawn area on the device anyway, and every entry that gets
     * clipped is a wasted six bytes of a frame that has none to spare.
     */
    private const val CONTEXT_RADIUS_M = 280f

    /** Nothing more than this far behind the bike - it is already ridden past. */
    private const val CONTEXT_BEHIND_M = -40f

    /**
     * Projects cached landmarks into the bike's frame, keeping the nearest few.
     *
     * The list itself is fetched once per route; this runs on every GPS fix, which is
     * why it does nothing but arithmetic.
     */
    fun localLandmarks(
        origin: LatLng,
        headingDeg: Float,
        landmarks: List<Landmark>,
        maxCount: Int = NavPacket.MAX_LANDMARKS
    ): List<LocalLandmark> =
        landmarks
            .map { toLocal(origin, it.position, headingDeg) to it.type.ordinal }
            .filter { (p, _) -> inContext(p.x, p.y) }
            .sortedBy { (p, _) -> hypot(p.x, p.y) }
            .take(maxCount)
            .map { (p, type) -> LocalLandmark(p.x, p.y, type) }

    /**
     * Projects cached side roads into the bike's frame, keeping the nearest few.
     *
     * The compass bearing becomes a heading relative to the direction of travel, since
     * the device draws everything heading-up and knows nothing about north.
     */
    fun localBranches(
        origin: LatLng,
        headingDeg: Float,
        roads: List<NearbyRoad>,
        maxCount: Int = NavPacket.MAX_BRANCHES
    ): List<LocalBranch> =
        roads
            .map { road ->
                val p = toLocal(origin, road.position, headingDeg)
                val relative = ((road.bearingDeg - headingDeg) % 360.0 + 360.0) % 360.0
                LocalBranch(p.x, p.y, relative.toFloat(), road.lengthM.toFloat())
            }
            .filter { inContext(it.x, it.y) }
            .sortedBy { hypot(it.x, it.y) }
            .take(maxCount)

    private fun inContext(x: Float, y: Float): Boolean =
        y >= CONTEXT_BEHIND_M && hypot(x, y) <= CONTEXT_RADIUS_M

    /**
     * Reduces a dense polyline to at most [maxPoints] while keeping its corners.
     *
     * This is Douglas-Peucker rather than the v1 even-interval decimation. On a
     * 12-point budget decimation can drop the single vertex that *is* the turn, which
     * on a small display shows a curve cutting the corner - exactly the frames a rider
     * is looking at when it matters most.
     */
    fun simplify(points: List<LatLng>, maxPoints: Int): List<LatLng> {
        if (points.size <= maxPoints) return points

        // Binary-search the tolerance that lands just under the point budget. Cheaper
        // than it looks (a handful of passes over <=1000 points) and avoids having to
        // pick a metre tolerance that works for both a hairpin and a motorway.
        var low = 0.5
        var high = 200.0
        var best = listOf(points.first(), points.last())

        repeat(12) {
            val mid = (low + high) / 2
            val candidate = douglasPeucker(points, mid)
            if (candidate.size > maxPoints) {
                low = mid
            } else {
                best = candidate
                high = mid
            }
        }
        return best
    }

    private fun douglasPeucker(points: List<LatLng>, toleranceM: Double): List<LatLng> {
        if (points.size < 3) return points

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true

        // Explicit stack instead of recursion: a dense OSRM polyline can be thousands
        // of vertices deep in the degenerate case.
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)

        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            if (end <= start + 1) continue

            var farthest = -1
            var maxDist = 0.0
            for (i in start + 1 until end) {
                val (_, perp) = Geo.projectOntoSegment(points[i], points[start], points[end])
                if (perp > maxDist) {
                    maxDist = perp
                    farthest = i
                }
            }

            if (maxDist > toleranceM && farthest > 0) {
                keep[farthest] = true
                stack.addLast(start to farthest)
                stack.addLast(farthest to end)
            }
        }

        return points.filterIndexed { index, _ -> keep[index] }
    }
}

/** Where the rider is, expressed as a position on the route line. */
data class SnapResult(
    /** The GPS fix projected onto the route. */
    val position: LatLng,
    /** Index of the polyline segment the rider is on. */
    val segmentIndex: Int,
    /** Distance from the route origin to [position], metres. */
    val distanceAlongM: Double,
    /** How far the raw GPS fix sat from the line, metres. Drives off-route detection. */
    val lateralErrorM: Double
)

/**
 * Projects a GPS fix onto the route.
 *
 * Searching a window around the last known position rather than the whole route is what
 * stops the classic failure where a route doubles back on itself (a flyover above the
 * road you came in on) and the snap jumps kilometres backwards, sending the ETA and the
 * turn countdown haywire.
 */
class RouteSnapper(private val route: Route) {

    private var lastSegment = 0

    /** How many segments either side of the last match to consider. */
    private val windowBack = 20
    private val windowForward = 400

    fun snap(position: LatLng): SnapResult {
        if (route.isEmpty) {
            return SnapResult(position, 0, 0.0, 0.0)
        }

        val from = (lastSegment - windowBack).coerceAtLeast(0)
        val to = (lastSegment + windowForward).coerceAtMost(route.polyline.size - 2)

        var best = searchRange(position, from, to)

        // If the windowed match is poor the rider probably isn't where we thought
        // (app resumed after a long pause, or they drove off and rejoined further on),
        // so fall back to a full sweep before declaring them off-route.
        if (best.lateralErrorM > 60.0) {
            val global = searchRange(position, 0, route.polyline.size - 2)
            if (global.lateralErrorM < best.lateralErrorM) best = global
        }

        lastSegment = best.segmentIndex
        return best
    }

    private fun searchRange(position: LatLng, from: Int, to: Int): SnapResult {
        var bestIndex = from
        var bestT = 0.0
        var bestPerp = Double.MAX_VALUE

        for (i in from..to) {
            val a = route.polyline[i]
            val b = route.polyline[i + 1]
            val (t, perp) = Geo.projectOntoSegment(position, a, b)
            if (perp < bestPerp) {
                bestPerp = perp
                bestIndex = i
                bestT = t
            }
        }

        val a = route.polyline[bestIndex]
        val b = route.polyline[bestIndex + 1]
        val segmentLength = route.cumulativeM[bestIndex + 1] - route.cumulativeM[bestIndex]

        return SnapResult(
            position = Geo.lerp(a, b, bestT),
            segmentIndex = bestIndex,
            distanceAlongM = route.cumulativeM[bestIndex] + segmentLength * bestT,
            lateralErrorM = bestPerp
        )
    }

    /**
     * Bearing of the route itself at the snapped point.
     *
     * Used as a heading fallback: GPS bearing is only meaningful once you're moving, so
     * at a red light `location.bearing` goes stale or reads zero and the display would
     * spin. Below walking pace we point the map along the road instead.
     */
    fun routeBearingAt(snap: SnapResult): Float {
        val i = snap.segmentIndex.coerceIn(0, route.polyline.size - 2)
        return Geo.bearingDeg(route.polyline[i], route.polyline[i + 1]).toFloat()
    }
}

/** Picks the step the rider is currently executing, given how far along the route they are. */
fun Route.stepAt(distanceAlongM: Double): Int {
    if (steps.isEmpty()) return -1
    for (i in steps.indices) {
        if (distanceAlongM < steps[i].maneuverAtM - 1.0) return i
    }
    return steps.size - 1
}
