package com.h2km33t.routeahead.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One geocoding hit - what the user picks from the search list. */
data class Place(
    val name: String,
    val address: String,
    val position: LatLng
)

/**
 * Address search via OpenStreetMap Nominatim. Free, no API key.
 *
 * Nominatim's usage policy caps this at 1 request/second and REQUIRES a real
 * User-Agent identifying the app - they block clients that omit it. The UI debounces
 * typing before calling this; don't call it per keystroke.
 */
object NominatimClient {

    private const val BASE_URL = "https://nominatim.openstreetmap.org/search"
    private const val USER_AGENT = "RouteAhead/2.0 (github.com/H2kM33t/BikeNavApp_RouteAhead)"

    suspend fun search(query: String, near: LatLng? = null, limit: Int = 8): List<Place> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()

            val url = buildString {
                append(BASE_URL)
                append("?q=").append(URLEncoder.encode(query, "UTF-8"))
                append("&format=jsonv2&addressdetails=1&limit=").append(limit)
                // Bias results toward the rider rather than returning the same-named
                // town on another continent. Nominatim treats this as a soft hint.
                if (near != null) {
                    val d = 0.7 // roughly 75 km box
                    append("&viewbox=")
                    append(near.lng - d).append(',').append(near.lat + d).append(',')
                    append(near.lng + d).append(',').append(near.lat - d)
                }
            }

            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept-Language", "en")
                }

                val body = try {
                    if (connection.responseCode != 200) return@withContext emptyList()
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }

                parse(JSONArray(body))
            } catch (e: Exception) {
                emptyList()
            }
        }

    private fun parse(array: JSONArray): List<Place> {
        val results = ArrayList<Place>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val lat = item.optDouble("lat", Double.NaN)
            val lng = item.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) continue

            val display = item.optString("display_name")
            // display_name is a long comma-separated chain; the head reads as the place
            // name and the tail as its address, which is how a search list should look.
            val name = item.optString("name").ifEmpty { display.substringBefore(',') }
            val address = display.substringAfter(',', "").trim()

            results.add(Place(name = name, address = address, position = LatLng(lat, lng)))
        }
        return results
    }
}
