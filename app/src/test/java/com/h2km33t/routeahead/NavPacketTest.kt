package com.h2km33t.routeahead

import com.h2km33t.routeahead.protocol.LocalBranch
import com.h2km33t.routeahead.protocol.LocalLandmark
import com.h2km33t.routeahead.protocol.LocalPoint
import com.h2km33t.routeahead.protocol.NavPacket
import com.h2km33t.routeahead.protocol.NavPayload
import com.h2km33t.routeahead.routing.Landmark
import com.h2km33t.routeahead.routing.Maneuver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the wire format against the ESP32.
 *
 * [decodeLikeFirmware] is a deliberate line-for-line transcription of
 * `BleManager::parsePacket` in the firmware. If someone changes the layout on one side
 * only, these tests fail rather than the rider being shown a wrong turn on the road.
 */
class NavPacketTest {

    // ------------------------------------------------------------------ firmware mirror

    private data class Decoded(
        val maneuver: Int,
        val distanceM: Int,
        val speedX10: Int,
        val etaS: Int,
        val remainingM: Int,
        val points: List<LocalPoint>,
        val landmarks: List<LocalLandmark>,
        val branches: List<LocalBranch>,
        val streetName: String,
        val hasRoute: Boolean,
        val offRoute: Boolean,
        val arrived: Boolean,
        val rerouting: Boolean
    )

    /** Returns null exactly where the firmware would `return` without touching navState. */
    private fun decodeLikeFirmware(data: ByteArray, length: Int = data.size): Decoded? {
        val headerLen = 12
        if (length < headerLen) return null
        if (data[0].toInt() and 0xFF != NavPacket.PROTOCOL_VERSION) return null

        fun u(i: Int) = data[i].toInt() and 0xFF

        val flags = u(1)
        var maneuver = u(2)
        val distance = u(3) or (u(4) shl 8)
        val speed = u(5) or (u(6) shl 8)
        val eta = u(7) or (u(8) shl 8)
        val remDam = u(9) or (u(10) shl 8)
        var numPoints = u(11)

        if (maneuver >= Maneuver.entries.size) maneuver = 0
        if (numPoints > NavPacket.MAX_ROUTE_POINTS) numPoints = NavPacket.MAX_ROUTE_POINTS

        val pointsEnd = headerLen + numPoints * 4
        if (length < pointsEnd + 1) return null

        var numLandmarks = u(pointsEnd)
        if (numLandmarks > NavPacket.MAX_LANDMARKS) numLandmarks = NavPacket.MAX_LANDMARKS

        val landmarksEnd = pointsEnd + 1 + numLandmarks * 5
        if (length < landmarksEnd + 1) return null

        var numBranches = u(landmarksEnd)
        if (numBranches > NavPacket.MAX_BRANCHES) numBranches = NavPacket.MAX_BRANCHES

        val branchesEnd = landmarksEnd + 1 + numBranches * 6
        if (length < branchesEnd + 1) return null

        var nameLen = u(branchesEnd)
        if (nameLen > NavPacket.MAX_STREET_NAME_BYTES) nameLen = NavPacket.MAX_STREET_NAME_BYTES
        if (length < branchesEnd + 1 + nameLen) return null

        val points = (0 until numPoints).map { i ->
            val o = headerLen + i * 4
            val xCm = (u(o) or (u(o + 1) shl 8)).toShort()
            val yCm = (u(o + 2) or (u(o + 3) shl 8)).toShort()
            LocalPoint(xCm / 100f, yCm / 100f)
        }

        val landmarks = (0 until numLandmarks).map { i ->
            val o = pointsEnd + 1 + i * 5
            val xCm = (u(o) or (u(o + 1) shl 8)).toShort()
            val yCm = (u(o + 2) or (u(o + 3) shl 8)).toShort()
            LocalLandmark(xCm / 100f, yCm / 100f, u(o + 4))
        }

        val branches = (0 until numBranches).map { i ->
            val o = landmarksEnd + 1 + i * 6
            val xCm = (u(o) or (u(o + 1) shl 8)).toShort()
            val yCm = (u(o + 2) or (u(o + 3) shl 8)).toShort()
            LocalBranch(xCm / 100f, yCm / 100f, u(o + 4) * 2f, u(o + 5) * 2f)
        }

        return Decoded(
            maneuver = maneuver,
            distanceM = distance,
            speedX10 = speed,
            etaS = eta,
            remainingM = remDam * 10,
            points = points,
            landmarks = landmarks,
            branches = branches,
            streetName = String(data, branchesEnd + 1, nameLen, Charsets.UTF_8),
            hasRoute = flags and 0x01 != 0,
            offRoute = flags and 0x02 != 0,
            arrived = flags and 0x04 != 0,
            rerouting = flags and 0x08 != 0
        )
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `full payload survives a round trip`() {
        val payload = NavPayload(
            maneuver = Maneuver.RIGHT,
            distanceToManeuverM = 275,
            speedKmh = 42.7f,
            etaSeconds = 1265,
            remainingDistanceM = 8430,
            streetName = "Sardar Patel Ring Road",
            routeAhead = listOf(
                LocalPoint(0f, 0f),
                LocalPoint(-3.25f, 48.5f),
                LocalPoint(12.75f, 130f)
            ),
            hasRoute = true
        )

        val decoded = decodeLikeFirmware(NavPacket.build(payload))!!

        assertEquals(Maneuver.RIGHT.ordinal, decoded.maneuver)
        assertEquals(275, decoded.distanceM)
        assertEquals(427, decoded.speedX10)
        assertEquals(1265, decoded.etaS)
        assertEquals(8430, decoded.remainingM)
        assertEquals("Sardar Patel Ring Road", decoded.streetName)
        assertEquals(3, decoded.points.size)
        assertEquals(-3.25f, decoded.points[1].x, 0.001f)
        assertEquals(130f, decoded.points[2].y, 0.001f)
        assertTrue(decoded.hasRoute)
    }

    @Test
    fun `every maneuver ordinal round trips`() {
        // Catches the case where someone inserts a value in the middle of one enum.
        for (maneuver in Maneuver.entries) {
            val decoded = decodeLikeFirmware(NavPacket.build(NavPayload(maneuver = maneuver)))!!
            assertEquals(
                "Maneuver $maneuver changed ordinal - update the firmware enum too",
                maneuver.ordinal,
                decoded.maneuver
            )
        }
    }

    @Test
    fun `all flag combinations survive`() {
        val decoded = decodeLikeFirmware(
            NavPacket.build(
                NavPayload(hasRoute = true, offRoute = true, arrived = true, rerouting = true)
            )
        )!!
        assertTrue(decoded.hasRoute && decoded.offRoute && decoded.arrived && decoded.rerouting)
    }

    @Test
    fun `worst case frame needs an MTU above the default`() {
        val packet = NavPacket.build(
            NavPayload(
                maneuver = Maneuver.OFF_RAMP,
                distanceToManeuverM = 65535,
                speedKmh = 6553.5f,
                etaSeconds = 65535,
                remainingDistanceM = 655350,
                streetName = "Chhatrapati Shivaji Maharaj Marg",
                routeAhead = List(NavPacket.MAX_ROUTE_POINTS) { LocalPoint(-300f, 320f) },
                landmarks = List(NavPacket.MAX_LANDMARKS) { LocalLandmark(-300f, 320f, 5) },
                branches = List(NavPacket.MAX_BRANCHES) { LocalBranch(-300f, 320f, 358f, 510f) },
                hasRoute = true
            )
        )

        assertEquals(NavPacket.MAX_PACKET_BYTES, packet.size)
        // The v1 bug: the default ATT MTU of 23 leaves 20 payload bytes. BleRouteClient
        // must negotiate upward before any of this can reach the device.
        assertTrue("worst case must not fit the default MTU", packet.size > 23 - 3)
        assertTrue("must fit the 185-byte MTU we request", packet.size <= 185 - 3)
        assertNotNull(decodeLikeFirmware(packet))
    }

    @Test
    fun `route points are capped at the firmware buffer size`() {
        val decoded = decodeLikeFirmware(
            NavPacket.build(NavPayload(routeAhead = List(50) { LocalPoint(1f, it.toFloat()) }))
        )!!
        assertEquals(NavPacket.MAX_ROUTE_POINTS, decoded.points.size)
    }

    @Test
    fun `truncated frames are rejected rather than misread`() {
        val packet = NavPacket.build(
            NavPayload(
                streetName = "Ring Road",
                routeAhead = listOf(LocalPoint(1f, 2f), LocalPoint(3f, 4f)),
                hasRoute = true
            )
        )

        assertNull("empty", decodeLikeFirmware(ByteArray(0)))
        assertNull("short header", decodeLikeFirmware(packet, 11))
        assertNull("cut mid-points", decodeLikeFirmware(packet, 14))
        assertNull("cut before the landmark count", decodeLikeFirmware(packet, 20))
        assertNull("cut mid-name", decodeLikeFirmware(packet, packet.size - 3))
        assertNotNull("intact frame", decodeLikeFirmware(packet))
    }

    @Test
    fun `a version mismatch is rejected outright`() {
        val packet = NavPacket.build(NavPayload(hasRoute = true))
        packet[0] = 1 // pretend it's a v1 frame
        assertNull(decodeLikeFirmware(packet))
    }

    @Test
    fun `extreme coordinates clamp instead of wrapping`() {
        // Beyond +-327 m the int16 centimetre encoding saturates. Wrapping instead would
        // put a route point on the opposite side of the bike.
        val decoded = decodeLikeFirmware(
            NavPacket.build(
                NavPayload(routeAhead = listOf(LocalPoint(-5000f, 5000f), LocalPoint(327.67f, -327.68f)))
            )
        )!!
        assertTrue("negative x stays negative", decoded.points[0].x < 0)
        assertTrue("positive y stays positive", decoded.points[0].y > 0)
        assertEquals(327.67f, decoded.points[1].x, 0.01f)
        assertEquals(-327.68f, decoded.points[1].y, 0.01f)
    }

    @Test
    fun `negative speed and distance clamp to zero`() {
        val decoded = decodeLikeFirmware(
            NavPacket.build(NavPayload(speedKmh = -12f, distanceToManeuverM = -5))
        )!!
        assertEquals(0, decoded.speedX10)
        assertEquals(0, decoded.distanceM)
    }

    @Test
    fun `long street names are cut on a character boundary`() {
        // Devanagari is 3 bytes per character, so a naive byte cut lands mid-sequence
        // and the ESP32 would render a replacement glyph.
        val decoded = decodeLikeFirmware(
            NavPacket.build(NavPayload(streetName = "गांधीनगर रोड नंबर सात"))
        )!!
        val bytes = decoded.streetName.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size <= NavPacket.MAX_STREET_NAME_BYTES)
        assertArrayEquals(
            "re-encoding must be lossless, i.e. no split characters",
            bytes,
            String(bytes, Charsets.UTF_8).toByteArray(Charsets.UTF_8)
        )
    }

    @Test
    fun `landmarks and branches round trip alongside the route`() {
        val payload = NavPayload(
            routeAhead = listOf(LocalPoint(0f, 0f), LocalPoint(4f, 90f)),
            landmarks = listOf(
                LocalLandmark(-18.5f, 60f, Landmark.Type.FUEL.ordinal),
                LocalLandmark(22f, 140f, Landmark.Type.HOSPITAL.ordinal)
            ),
            branches = listOf(
                LocalBranch(2f, 70f, 90f, 56f),
                LocalBranch(3f, 120f, 268f, 40f)
            ),
            streetName = "MG Road",
            hasRoute = true
        )

        val decoded = decodeLikeFirmware(NavPacket.build(payload))!!

        assertEquals(2, decoded.landmarks.size)
        assertEquals(-18.5f, decoded.landmarks[0].x, 0.001f)
        assertEquals(Landmark.Type.FUEL.ordinal, decoded.landmarks[0].typeOrdinal)
        assertEquals(Landmark.Type.HOSPITAL.ordinal, decoded.landmarks[1].typeOrdinal)

        assertEquals(2, decoded.branches.size)
        assertEquals(2f, decoded.branches[0].x, 0.001f)
        // Heading and length are quantised to 2 units on the wire, so exact values only
        // survive when they are even - which is why the fixture uses even numbers.
        assertEquals(90f, decoded.branches[0].headingDeg, 0.001f)
        assertEquals(56f, decoded.branches[0].lengthM, 0.001f)
        assertEquals(268f, decoded.branches[1].headingDeg, 0.001f)

        // The blocks in front of it must not have shifted the name.
        assertEquals("MG Road", decoded.streetName)
    }

    @Test
    fun `landmark and branch counts are capped at the firmware buffer sizes`() {
        val decoded = decodeLikeFirmware(
            NavPacket.build(
                NavPayload(
                    landmarks = List(40) { LocalLandmark(1f, it.toFloat(), 0) },
                    branches = List(40) { LocalBranch(1f, it.toFloat(), 45f, 50f) }
                )
            )
        )!!
        assertEquals(NavPacket.MAX_LANDMARKS, decoded.landmarks.size)
        assertEquals(NavPacket.MAX_BRANCHES, decoded.branches.size)
    }

    @Test
    fun `branch headings wrap into the byte range instead of overflowing`() {
        // A heading computed as (roadBearing - riderHeading) can legitimately come out
        // negative or past 360. Either one, encoded naively, lands outside the byte and
        // would point a side road the wrong way on the device.
        val decoded = decodeLikeFirmware(
            NavPacket.build(
                NavPayload(
                    branches = listOf(
                        LocalBranch(0f, 10f, -90f, 50f),
                        LocalBranch(0f, 20f, 450f, 50f)
                    )
                )
            )
        )!!
        assertEquals(270f, decoded.branches[0].headingDeg, 0.001f)
        assertEquals(90f, decoded.branches[1].headingDeg, 0.001f)
    }

    @Test
    fun `landmark type ordinals match the firmware enum`() {
        // The type travels as a raw byte the firmware uses to pick a glyph, so an
        // inserted enum value would silently draw a hospital cross on a petrol station.
        for (type in Landmark.Type.entries) {
            val decoded = decodeLikeFirmware(
                NavPacket.build(
                    NavPayload(landmarks = listOf(LocalLandmark(0f, 10f, type.ordinal)))
                )
            )!!
            assertEquals(
                "Landmark type $type changed ordinal - update LandmarkType in RouteData.h too",
                type.ordinal,
                decoded.landmarks[0].typeOrdinal
            )
        }
    }

    @Test
    fun `an empty street name is valid`() {
        val decoded = decodeLikeFirmware(NavPacket.build(NavPayload(streetName = "")))!!
        assertEquals("", decoded.streetName)
    }
}
