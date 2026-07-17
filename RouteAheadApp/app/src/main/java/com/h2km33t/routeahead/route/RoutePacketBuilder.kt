package com.h2km33t.routeahead.route

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the binary BLE payload the ESP32 firmware parses.
 *
 * Packet layout (little-endian):
 *   Byte 0:      maneuverType   (0-8, matches ManeuverType enum on ESP32)
 *   Byte 1-2:    distanceToTurn (uint16, metres)
 *   Byte 3:      numPoints      (0-12)
 *   Byte 4+:     for each point: x (int16, cm), y (int16, cm)  -> 4 bytes per point
 *
 * Max size: 4 + 12*4 = 52 bytes.
 */
object RoutePacketBuilder {

    // Keep this enum's ORDER identical to ManeuverType on the ESP32 side - it's sent as a raw index.
    enum class ManeuverType {
        STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
        SHARP_LEFT, SHARP_RIGHT, UTURN_RIGHT, ROUNDABOUT
    }

    fun buildPacket(
        maneuver: ManeuverType,
        distanceToTurnMetres: Int,
        routePoints: List<LocalPoint>
    ): ByteArray {
        val points = routePoints.take(12) // hard cap, matches ESP32 buffer size
        val bufferSize = 4 + points.size * 4
        val buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(maneuver.ordinal.toByte())
        buffer.putShort(distanceToTurnMetres.coerceIn(0, 65535).toShort())
        buffer.put(points.size.toByte())

        for (p in points) {
            val xCm = (p.x * 100).toInt().coerceIn(-32768, 32767)
            val yCm = (p.y * 100).toInt().coerceIn(-32768, 32767)
            buffer.putShort(xCm.toShort())
            buffer.putShort(yCm.toShort())
        }

        return buffer.array()
    }
}
