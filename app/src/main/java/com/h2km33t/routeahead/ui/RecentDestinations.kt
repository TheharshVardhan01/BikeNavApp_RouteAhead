package com.h2km33t.routeahead.ui

import android.content.Context
import com.h2km33t.routeahead.routing.LatLng
import com.h2km33t.routeahead.routing.Place
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers the last few destinations so a rider's regular trips are one tap away
 * instead of a fresh search every time.
 *
 * SharedPreferences with a JSON blob rather than Room: it's a capped list of at most
 * eight entries read once at startup, and a database would be more moving parts than
 * the feature is worth.
 */
class RecentDestinations(context: Context) {

    private companion object {
        const val PREFS = "route_ahead_recents"
        const val KEY = "recent_places"
        const val MAX_ENTRIES = 8
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<Place> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Place(
                    name = o.optString("name"),
                    address = o.optString("address"),
                    position = LatLng(o.optDouble("lat"), o.optDouble("lng"))
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(place: Place) {
        // De-duplicate by coordinate rather than name: the same shop can come back from
        // Nominatim with a slightly different display name between searches.
        val updated = (listOf(place) + load().filterNot { it.isSameSpotAs(place) })
            .take(MAX_ENTRIES)

        val array = JSONArray()
        updated.forEach { p ->
            array.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("address", p.address)
                    put("lat", p.position.lat)
                    put("lng", p.position.lng)
                }
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun Place.isSameSpotAs(other: Place): Boolean =
        kotlin.math.abs(position.lat - other.position.lat) < 1e-4 &&
                kotlin.math.abs(position.lng - other.position.lng) < 1e-4
}
