package com.h2km33t.routeahead.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the public OSRM demo server. No API key, free for light personal use.
 *
 * The demo server has no uptime guarantee and rate-limits aggressively - if you ship
 * this to more than a handful of riders, self-host OSRM or switch BASE_URL to a paid
 * provider with the same response shape (Mapbox Directions is close but not identical).
 */
object OsrmClient {

    private const val BASE_URL = "https://router.project-osrm.org/route/v1"

    /** OSRM routing profile. "driving" is closest to a motorbike on public roads. */
    enum class Profile(val slug: String) { DRIVING("driving"), CYCLING("cycling"), WALKING("walking") }

    sealed interface Result {
        data class Success(val route: Route) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Fetches a route with full geometry AND turn-by-turn steps.
     *
     * `steps=true` is what makes real instructions possible - without it OSRM returns
     * only the polyline and every maneuver would have to be inferred from geometry.
     */
    suspend fun getRoute(
        origin: LatLng,
        destination: LatLng,
        profile: Profile = Profile.DRIVING
    ): Result = withContext(Dispatchers.IO) {
        // OSRM expects lng,lat order (opposite of the usual lat,lng) - easy mistake, watch this.
        val url = "$BASE_URL/${profile.slug}/" +
                "${origin.lng},${origin.lat};${destination.lng},${destination.lat}" +
                "?overview=full&geometries=geojson&steps=true&alternatives=false"

        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "RouteAhead/2.0 (github.com/H2kM33t)")
            }

            val body = try {
                if (connection.responseCode != 200) {
                    return@withContext Result.Failure("Routing server returned ${connection.responseCode}")
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            parse(body)
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Could not reach the routing server")
        }
    }

    private fun parse(json: String): Result {
        val root = JSONObject(json)

        when (val code = root.optString("code")) {
            "Ok" -> Unit
            "NoRoute" -> return Result.Failure("No road route exists between those points")
            "NoSegment" -> return Result.Failure("Start or destination is too far from any road")
            else -> return Result.Failure("Routing failed ($code)")
        }

        val routes = root.optJSONArray("routes") ?: return Result.Failure("Malformed routing response")
        if (routes.length() == 0) return Result.Failure("No route found")

        val route = routes.getJSONObject(0)

        val coordinates = route.getJSONObject("geometry").getJSONArray("coordinates")
        val polyline = ArrayList<LatLng>(coordinates.length())
        for (i in 0 until coordinates.length()) {
            val pair = coordinates.getJSONArray(i) // [lng, lat]
            polyline.add(LatLng(pair.getDouble(1), pair.getDouble(0)))
        }
        if (polyline.size < 2) return Result.Failure("Route geometry too short to follow")

        // OSRM's steps tile the route end to end, so accumulating their distances gives
        // each step's start offset without having to match step geometry back onto the
        // full polyline (which is fiddly and fails on self-overlapping routes).
        val steps = ArrayList<RouteStep>()
        var alongM = 0.0
        val legs = route.optJSONArray("legs")
        if (legs != null) {
            for (l in 0 until legs.length()) {
                val stepArray = legs.getJSONObject(l).optJSONArray("steps") ?: continue
                for (s in 0 until stepArray.length()) {
                    val step = stepArray.getJSONObject(s)
                    val maneuverObj = step.getJSONObject("maneuver")

                    val distanceM = step.optDouble("distance", 0.0)
                    steps.add(
                        RouteStep(
                            maneuver = Maneuver.fromOsrm(
                                type = maneuverObj.optString("type"),
                                modifier = maneuverObj.optString("modifier").takeIf { it.isNotEmpty() }
                            ),
                            // `name` is empty on unnamed service roads; `ref` (road number) is
                            // a decent fallback and often what a rider recognises anyway.
                            streetName = step.optString("name")
                                .ifEmpty { step.optString("ref") },
                            distanceM = distanceM,
                            durationS = step.optDouble("duration", 0.0),
                            distanceAlongRouteM = alongM,
                            roundaboutExit = maneuverObj.optInt("exit", 0).takeIf { it > 0 }
                        )
                    )
                    alongM += distanceM
                }
            }
        }

        return Result.Success(
            Route(
                polyline = polyline,
                steps = steps,
                // Prefer the summed step distance when steps exist: it matches the
                // cumulative offsets above exactly, so progress never disagrees with ETA.
                totalDistanceM = if (steps.isNotEmpty()) alongM else route.optDouble("distance", 0.0),
                totalDurationS = route.optDouble("duration", 0.0)
            )
        )
    }
}
