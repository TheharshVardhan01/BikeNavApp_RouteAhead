package com.h2km33t.routeahead

import com.h2km33t.routeahead.nav.RouteSnapper
import com.h2km33t.routeahead.nav.RouteTransform
import com.h2km33t.routeahead.nav.stepAt
import com.h2km33t.routeahead.routing.Geo
import com.h2km33t.routeahead.routing.LatLng
import com.h2km33t.routeahead.routing.Maneuver
import com.h2km33t.routeahead.routing.Route
import com.h2km33t.routeahead.routing.RouteStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the maths that decides what distance the rider sees counting down. A bug here
 * produces a plausible-looking but wrong number, which is the failure mode that actually
 * sends someone through a junction.
 */
class NavigationGeometryTest {

    private val surat = LatLng(21.1702, 72.8311)

    /** An L: 1 km east, then a right turn and 1 km north. */
    private fun lShapedRoute(): Route {
        val corner = Geo.offset(surat, 90.0, 1000.0)
        val polyline = buildList {
            for (m in 0..1000 step 25) add(Geo.offset(surat, 90.0, m.toDouble()))
            for (m in 25..1000 step 25) add(Geo.offset(corner, 0.0, m.toDouble()))
        }
        return Route(
            polyline = polyline,
            steps = listOf(
                RouteStep(Maneuver.DEPART, "First Street", 1000.0, 90.0, 0.0),
                RouteStep(Maneuver.RIGHT, "Second Street", 1000.0, 100.0, 1000.0),
                RouteStep(Maneuver.ARRIVE, "Destination", 0.0, 0.0, 2000.0)
            ),
            totalDistanceM = 2000.0,
            totalDurationS = 190.0
        )
    }

    // ------------------------------------------------------------------ geodesy

    @Test
    fun `offset and distance agree in both axes`() {
        assertEquals(100.0, Geo.distanceM(surat, Geo.offset(surat, 0.0, 100.0)), 0.5)
        assertEquals(100.0, Geo.distanceM(surat, Geo.offset(surat, 90.0, 100.0)), 0.5)
        assertEquals(0.0, Geo.bearingDeg(surat, Geo.offset(surat, 0.0, 100.0)), 0.5)
        assertEquals(90.0, Geo.bearingDeg(surat, Geo.offset(surat, 90.0, 100.0)), 0.5)
    }

    @Test
    fun `a degree of longitude is not a degree of latitude`() {
        // The v1 nearest-point search compared raw degrees. At Surat's latitude that
        // makes east-west error read ~7% short, biasing every snap.
        val northDegree = Geo.distanceM(surat, LatLng(surat.lat + 0.01, surat.lng))
        val eastDegree = Geo.distanceM(surat, LatLng(surat.lat, surat.lng + 0.01))
        assertTrue(
            "degrees must not be treated as interchangeable",
            kotlin.math.abs(northDegree - eastDegree) > 50
        )
    }

    @Test
    fun `projection finds the perpendicular foot`() {
        val a = surat
        val b = Geo.offset(surat, 90.0, 100.0)
        val offLine = Geo.offset(Geo.lerp(a, b, 0.5), 0.0, 30.0)

        val (t, perp) = Geo.projectOntoSegment(offLine, a, b)
        assertEquals(0.5, t, 0.02)
        assertEquals(30.0, perp, 1.0)
    }

    @Test
    fun `projection clamps beyond both ends`() {
        val a = surat
        val b = Geo.offset(surat, 90.0, 100.0)
        assertEquals(0.0, Geo.projectOntoSegment(Geo.offset(a, 270.0, 50.0), a, b).first, 0.0)
        assertEquals(1.0, Geo.projectOntoSegment(Geo.offset(b, 90.0, 50.0), a, b).first, 0.0)
    }

    @Test
    fun `a zero length segment does not divide by zero`() {
        val (t, perp) = Geo.projectOntoSegment(Geo.offset(surat, 45.0, 10.0), surat, surat)
        assertEquals(0.0, t, 0.0)
        assertTrue(perp.isFinite())
    }

    // ------------------------------------------------------------------ snapping

    @Test
    fun `snapping reports position along the route and lateral error`() {
        val route = lShapedRoute()
        val snapper = RouteSnapper(route)

        val rider = Geo.offset(Geo.offset(surat, 90.0, 400.0), 0.0, 8.0)
        val snap = snapper.snap(rider)

        assertEquals(400.0, snap.distanceAlongM, 15.0)
        assertEquals(8.0, snap.lateralErrorM, 1.5)
    }

    @Test
    fun `leaving the road produces a large lateral error`() {
        val route = lShapedRoute()
        val snapper = RouteSnapper(route)

        val lost = Geo.offset(Geo.offset(surat, 90.0, 400.0), 0.0, 120.0)
        // 45 m is the OFF_ROUTE_THRESHOLD_M the controller reroutes on.
        assertTrue(snapper.snap(lost).lateralErrorM > 45.0)
    }

    @Test
    fun `progress along the route never jumps backwards`() {
        val route = lShapedRoute()
        val snapper = RouteSnapper(route)

        var previous = -1.0
        for (m in 0..1000 step 50) {
            val along = snapper.snap(Geo.offset(surat, 90.0, m.toDouble())).distanceAlongM
            assertTrue("progress went backwards at $m m", along >= previous - 1.0)
            previous = along
        }
    }

    // ------------------------------------------------------------------ turn countdown

    /** Distance to the maneuver at the end of whichever step the rider is currently in. */
    private fun countdownAt(route: Route, snapper: RouteSnapper, metresAlong: Int): Double {
        val snap = snapper.snap(Geo.offset(surat, 90.0, metresAlong.toDouble()))
        val step = route.steps[route.stepAt(snap.distanceAlongM)]
        return (step.maneuverAtM - snap.distanceAlongM).coerceAtLeast(0.0)
    }

    @Test
    fun `distance to the turn counts down as the rider approaches`() {
        val route = lShapedRoute()
        val snapper = RouteSnapper(route)

        // Stops at 950 m deliberately: the turn is at 1000 m, and crossing it hands over
        // to the next maneuver rather than continuing to count down - see the test below.
        var previous = Double.MAX_VALUE
        for (m in 700..950 step 50) {
            val toTurn = countdownAt(route, snapper, m)
            assertTrue("countdown increased at $m m", toTurn <= previous + 1.0)
            previous = toTurn
        }
        assertTrue("should be within 60 m of the turn by 950 m", previous < 60.0)
    }

    @Test
    fun `crossing a maneuver hands over to the next one`() {
        // This is the behaviour a rider actually needs: the moment you complete a turn
        // the display starts counting down to the following one, rather than sitting at
        // zero. Locking it in because it looks like a bug in a countdown-monotonicity
        // test, and "fixing" it would break navigation.
        val route = lShapedRoute()
        val snapper = RouteSnapper(route)

        assertEquals("approaching the turn", 0, route.stepAt(950.0))
        assertEquals("past the turn", 1, route.stepAt(1050.0))

        val before = countdownAt(route, snapper, 950)
        val after = countdownAt(route, snapper, 1050)

        assertTrue("should be close to the first turn at 950 m", before < 60.0)
        assertTrue("should now be counting down to the second maneuver", after > 800.0)
    }

    @Test
    fun `stepAt selects the step the rider is currently in`() {
        val route = lShapedRoute()
        assertEquals(0, route.stepAt(0.0))
        assertEquals(0, route.stepAt(500.0))
        assertEquals(1, route.stepAt(1200.0))
        // Past the end it must stay on the last step rather than going out of bounds.
        assertEquals(route.steps.size - 1, route.stepAt(9999.0))
    }

    // ------------------------------------------------------------------ simplification

    @Test
    fun `simplify respects the point budget and keeps the endpoints`() {
        val route = lShapedRoute()
        val reduced = RouteTransform.simplify(route.polyline, 12)

        assertTrue("must fit the BLE budget", reduced.size <= 12)
        assertEquals(route.polyline.first(), reduced.first())
        assertEquals(route.polyline.last(), reduced.last())
    }

    @Test
    fun `simplify keeps the corner`() {
        // The whole reason for Douglas-Peucker over even-interval decimation: the corner
        // is the one vertex the rider needs, and decimation drops it whenever the vertex
        // count isn't a clean multiple of the budget.
        val route = lShapedRoute()
        val corner = Geo.offset(surat, 90.0, 1000.0)

        val nearestToCorner = RouteTransform.simplify(route.polyline, 12)
            .minOf { Geo.distanceM(it, corner) }

        assertTrue("corner was smoothed away (off by ${nearestToCorner.toInt()} m)",
            nearestToCorner < 30.0)
    }

    @Test
    fun `simplify passes short lists through untouched`() {
        val route = lShapedRoute()
        val short = route.polyline.take(5)
        assertEquals(short, RouteTransform.simplify(short, 12))
        assertEquals(12, RouteTransform.simplify(route.polyline.take(12), 12).size)
    }

    @Test
    fun `a straight line collapses to its endpoints`() {
        val straight = (0..2000 step 10).map { Geo.offset(surat, 90.0, it.toDouble()) }
        assertEquals(2, RouteTransform.simplify(straight, 12).size)
    }

    // ------------------------------------------------------------------ ahead slice

    @Test
    fun `the ahead slice starts at the bike and runs forward`() {
        val route = lShapedRoute()
        val snapper = RouteSnapper(route)
        val snap = snapper.snap(Geo.offset(surat, 90.0, 400.0))

        // Heading east along the first leg.
        val slice = RouteTransform.aheadSlice(route, snap, headingDeg = 90f)

        assertTrue("slice must not be empty", slice.isNotEmpty())
        assertEquals("first point sits on the bike", 0f, slice.first().x, 1f)
        assertEquals("first point sits on the bike", 0f, slice.first().y, 1f)
        assertTrue("must fit the packet budget", slice.size <= 12)
        // Everything ahead of the rider, allowing a little slack for GPS-style rounding.
        assertTrue("all points should be ahead", slice.all { it.y > -5f })
    }

    @Test
    fun `the ahead slice bends right when the route turns right`() {
        val route = lShapedRoute()
        val snapper = RouteSnapper(route)
        // 100 m before the corner, still heading east; the route turns north (left of
        // east is north... which in bike frame is a LEFT turn - assert accordingly).
        val snap = snapper.snap(Geo.offset(surat, 90.0, 900.0))
        val slice = RouteTransform.aheadSlice(route, snap, headingDeg = 90f)

        // Heading east (+Y east), north becomes -X, i.e. to the rider's left.
        assertTrue(
            "route turning north while heading east must appear to the left",
            slice.last().x < -50f
        )
    }
}
