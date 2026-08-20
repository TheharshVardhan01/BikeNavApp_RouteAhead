package com.h2km33t.routeahead.protocol

import com.h2km33t.routeahead.routing.Maneuver
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A point in the device's coordinate frame: metres relative to the bike, rotated so
 * +Y is straight ahead (current heading) and +X is to the right of travel.
 * Mirrors `RoutePoint` in the ESP32 firmware.
 */
data class LocalPoint(val x: Float, val y: Float)

/**
 * A place to draw beside the route, in the bike-relative frame.
 * [typeOrdinal] must match the firmware's LandmarkType enum.
 */
data class LocalLandmark(val x: Float, val y: Float, val typeOrdinal: Int)

/**
 * A road branching off the route, in the bike-relative frame.
 *
 * Carried as an anchor plus a direction rather than a polyline: a side road only needs
 * to show *where* it leaves the route and *which way* it heads. That fits in 6 bytes
 * against the 8 a two-point line would cost, which is the difference between six of
 * them fitting in a frame and four.
 *
 * [headingDeg] is clockwise from straight-ahead, so 0 = away from the bike, 90 = to the
 * right. Quantised to 2 degrees and [lengthM] to 2 metres on the wire.
 */
data class LocalBranch(val x: Float, val y: Float, val headingDeg: Float, val lengthM: Float)

/**
 * Everything the device needs to draw one frame.
 */
data class NavPayload(
    val maneuver: Maneuver = Maneuver.STRAIGHT,
    val distanceToManeuverM: Int = 0,
    val speedKmh: Float = 0f,
    val etaSeconds: Int = 0,
    val remainingDistanceM: Int = 0,
    val streetName: String = "",
    val routeAhead: List<LocalPoint> = emptyList(),
    /** Nearby places, in the same bike-relative frame as [routeAhead]. */
    val landmarks: List<LocalLandmark> = emptyList(),
    /** Neighbouring roads, in the same bike-relative frame as [routeAhead]. */
    val branches: List<LocalBranch> = emptyList(),
    val hasRoute: Boolean = false,
    val offRoute: Boolean = false,
    val arrived: Boolean = false,
    val rerouting: Boolean = false
)

/**
 * Serialises [NavPayload] into the binary frame the ESP32 parses.
 *
 * Wire format (little-endian). Byte 0 is a version tag so old firmware rejects frames
 * it doesn't understand instead of misreading them:
 *
 * ```
 *  0       version = 4
 *  1       flags        bit0 hasRoute, bit1 offRoute, bit2 arrived, bit3 rerouting
 *  2       maneuver     Maneuver ordinal
 *  3..4    uint16       distance to the maneuver, metres
 *  5..6    uint16       speed, 0.1 km/h units (so 42.7 km/h -> 427)
 *  7..8    uint16       ETA, seconds (saturates at 18h12m)
 *  9..10   uint16       remaining distance, decametres (10 m units, so up to 655 km)
 *  11      uint8        numPoints (0..MAX_ROUTE_POINTS)
 *  12..    numPoints    * { int16 x cm, int16 y cm }
 *  then    uint8        numLandmarks
 *  then    numLandmarks * { int16 x cm, int16 y cm, uint8 type }
 *  then    uint8        numBranches
 *  then    numBranches  * { int16 x cm, int16 y cm, uint8 heading/2 deg, uint8 length/2 m }
 *  then    uint8        streetName length in bytes
 *  then    UTF-8 street name, truncated to MAX_STREET_NAME_BYTES
 * ```
 *
 * Every variable-length block is length-prefixed and appears in a fixed order, so the
 * firmware can validate the whole frame before it touches its display state.
 */
object NavPacket {

    const val PROTOCOL_VERSION = 4

    /**
     * Must equal MAX_ROUTE_POINTS in the firmware's RouteData.h.
     *
     * Dropped from 24 to 20 in v4 to make room for the branch block. Douglas-Peucker
     * keeps the corners regardless of the budget, so the visible cost is a slightly
     * coarser curve on long straights - and the side roads that budget bought are worth
     * far more to a rider than four extra vertices on a straight line.
     */
    const val MAX_ROUTE_POINTS = 20

    const val MAX_STREET_NAME_BYTES = 22

    /** Must equal MAX_LANDMARKS in the firmware's RouteData.h. */
    const val MAX_LANDMARKS = 5

    /** Must equal MAX_BRANCHES in the firmware's RouteData.h. */
    const val MAX_BRANCHES = 6

    /**
     * Largest frame this builder can emit: 178 bytes.
     *
     * The ceiling that matters is the negotiated ATT MTU minus 3 bytes of header, so at
     * the MTU 185 the client asks for there are 182 usable - see BleRouteClient. Anything
     * added here has to be paid for out of one of the blocks above.
     */
    const val MAX_PACKET_BYTES =
        12 +
            MAX_ROUTE_POINTS * 4 +
            1 + MAX_LANDMARKS * 5 +
            1 + MAX_BRANCHES * 6 +
            1 + MAX_STREET_NAME_BYTES

    private const val FLAG_HAS_ROUTE = 1 shl 0
    private const val FLAG_OFF_ROUTE = 1 shl 1
    private const val FLAG_ARRIVED = 1 shl 2
    private const val FLAG_REROUTING = 1 shl 3

    fun build(payload: NavPayload): ByteArray {
        val points = payload.routeAhead.take(MAX_ROUTE_POINTS)
        val places = payload.landmarks.take(MAX_LANDMARKS)
        val roads = payload.branches.take(MAX_BRANCHES)
        val nameBytes = truncateUtf8(payload.streetName, MAX_STREET_NAME_BYTES)

        val buffer = ByteBuffer
            .allocate(
                12 + points.size * 4 +
                    1 + places.size * 5 +
                    1 + roads.size * 6 +
                    1 + nameBytes.size
            )
            .order(ByteOrder.LITTLE_ENDIAN)

        var flags = 0
        if (payload.hasRoute) flags = flags or FLAG_HAS_ROUTE
        if (payload.offRoute) flags = flags or FLAG_OFF_ROUTE
        if (payload.arrived) flags = flags or FLAG_ARRIVED
        if (payload.rerouting) flags = flags or FLAG_REROUTING

        buffer.put(PROTOCOL_VERSION.toByte())
        buffer.put(flags.toByte())
        buffer.put(payload.maneuver.ordinal.toByte())
        buffer.putShort(payload.distanceToManeuverM.coerceIn(0, 65535).toShort())
        buffer.putShort((payload.speedKmh * 10f).toInt().coerceIn(0, 65535).toShort())
        buffer.putShort(payload.etaSeconds.coerceIn(0, 65535).toShort())
        buffer.putShort((payload.remainingDistanceM / 10).coerceIn(0, 65535).toShort())
        buffer.put(points.size.toByte())

        for (p in points) {
            buffer.putShort(cm(p.x))
            buffer.putShort(cm(p.y))
        }

        // v3: landmarks.
        buffer.put(places.size.toByte())
        for (p in places) {
            buffer.putShort(cm(p.x))
            buffer.putShort(cm(p.y))
            buffer.put(p.typeOrdinal.coerceIn(0, 255).toByte())
        }

        // v4: neighbouring roads. Heading is normalised into 0..358 before halving so a
        // negative or wrapped angle can't land outside the byte.
        buffer.put(roads.size.toByte())
        for (b in roads) {
            buffer.putShort(cm(b.x))
            buffer.putShort(cm(b.y))
            val heading = ((b.headingDeg % 360f) + 360f) % 360f
            buffer.put((heading / 2f).toInt().coerceIn(0, 179).toByte())
            buffer.put((b.lengthM / 2f).toInt().coerceIn(1, 255).toByte())
        }

        buffer.put(nameBytes.size.toByte())
        buffer.put(nameBytes)

        return buffer.array()
    }

    /** Metres to the int16 centimetres the wire format uses, saturating rather than wrapping. */
    private fun cm(metres: Float): Short =
        (metres * 100f).toInt().coerceIn(-32768, 32767).toShort()

    /**
     * Truncates to a byte budget without splitting a multi-byte UTF-8 character.
     * Street names come straight from OSM, so they can contain Devanagari, accents,
     * or anything else - cutting mid-sequence would put invalid bytes on the wire.
     */
    private fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val full = text.trim().toByteArray(Charsets.UTF_8)
        if (full.size <= maxBytes) return full

        var end = maxBytes
        // Walk back off any continuation byte (10xxxxxx) to land on a character boundary.
        while (end > 0 && (full[end].toInt() and 0xC0) == 0x80) end--
        return full.copyOf(end)
    }
}
