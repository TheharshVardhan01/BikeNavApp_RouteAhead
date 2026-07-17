package com.h2km33t.routeahead.route

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the public OSRM server to get a route polyline between two points.
 * No API key needed. Free for personal/light use.
 */
data class LatLng(val lat: Double, val lng: Double)

object OsrmClient {

    // Public OSRM demo server. For "driving" profile (closest to motorbike/bike-on-road use).
    private const val BASE_URL = "https://router.project-osrm.org/route/v1/driving"

    /**
     * Fetches a route from origin to destination.
     * Runs network I/O - call this from a background thread / coroutine, never the main thread.
     *
     * Returns null if the request fails (no internet, OSRM down, no route found).
     */
    fun getRoute(origin: LatLng, destination: LatLng): List<LatLng>? {
        // OSRM expects lng,lat order (opposite of the usual lat,lng!) - easy mistake, watch this.
        val url = "$BASE_URL/${origin.lng},${origin.lat};${destination.lng},${destination.lat}" +
                "?overview=full&geometries=geojson"

        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                connection.disconnect()
                return null
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()
            connection.disconnect()

            parseRouteResponse(response)
        } catch (e: Exception) {
            // Network failure, malformed response, etc. Caller should handle null (e.g. show "no route found").
            null
        }
    }

    private fun parseRouteResponse(json: String): List<LatLng>? {
        val root = JSONObject(json)

        if (root.getString("code") != "Ok") return null

        val routes = root.getJSONArray("routes")
        if (routes.length() == 0) return null

        val geometry = routes.getJSONObject(0).getJSONObject("geometry")
        val coordinates = geometry.getJSONArray("coordinates") // array of [lng, lat] pairs

        val points = mutableListOf<LatLng>()
        for (i in 0 until coordinates.length()) {
            val pair = coordinates.getJSONArray(i)
            val lng = pair.getDouble(0)
            val lat = pair.getDouble(1)
            points.add(LatLng(lat, lng))
        }
        return points
    }
}
