package com.h2km33t.routeahead.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * A place worth showing on the device's route preview.
 *
 * The ordinal order of [Type] is frozen: it travels over BLE as a raw byte and the
 * firmware's `LandmarkType` enum indexes the same list. Append only.
 */
data class Landmark(
    val position: LatLng,
    val type: Type,
    val name: String
) {
    enum class Type { PLACE, FUEL, FOOD, HOSPITAL, ATM, JUNCTION }
}

/**
 * A road that meets the route, reduced to the one thing a small screen can show: where
 * it leaves the route and which way it goes.
 *
 * [bearingDeg] is a compass bearing (0 = north) pointing away from the route, and
 * [lengthM] is how far to draw the stub - not the road's real length, just enough for
 * the rider to see an arm at the junction.
 */
data class NearbyRoad(
    val position: LatLng,
    val bearingDeg: Double,
    val lengthM: Double
)

/**
 * Fetches map context from OpenStreetMap via Overpass: nearby points of interest, and
 * the roads that branch off the route.
 *
 * These exist to give the rider a sense of place. A bare line tells you the road bends;
 * a fuel pump, a hospital cross and three side streets tell you *where you are*.
 * Overpass rather than Nominatim because both of these are radius searches over many
 * small features, which is what Overpass is built for.
 *
 * Free and key-less, but the public instances are shared and rate-limited, so both
 * queries run once per route rather than continuously - the results are cached for the
 * whole trip and re-projected locally as the rider moves.
 */
object OverpassClient {

    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private const val USER_AGENT = "RouteAhead/2.0 (github.com/H2kM33t)"

    /** Keeps the POI query cheap; Overpass bills by area and element count. */
    private const val SEARCH_RADIUS_M = 400

    /** Roads are searched in a tighter corridor - only ones that actually meet the route. */
    private const val ROAD_RADIUS_M = 120

    /** A road vertex further than this from the route isn't a junction with it. */
    private const val JUNCTION_TOLERANCE_M = 30.0

    /** How much of each side road to draw. Long enough to read as an arm, short enough
     *  not to dominate a 160px map. */
    private const val STUB_LENGTH_M = 55.0

    /** Two stubs closer than this, heading the same way, are the same junction arm. */
    private const val DEDUPE_RADIUS_M = 25.0

    /**
     * Looks for POIs near a handful of points sampled along the route.
     *
     * Sampling rather than querying the whole corridor keeps the request small enough
     * for the public instance to answer quickly. Returns empty on any failure -
     * landmarks are decoration, and a slow or down Overpass must never hold up
     * navigation.
     */
    suspend fun landmarksAlong(route: Route, maxSamples: Int = 6): List<Landmark> =
        withContext(Dispatchers.IO) {
            val around = aroundClause(route, maxSamples) ?: return@withContext emptyList()

            // One query covering every sample point. `nwr` catches nodes, ways and
            // relations, since a petrol station may be mapped as any of the three.
            val query = """
                [out:json][timeout:20];
                (
                  nwr(around:$SEARCH_RADIUS_M,$around)[amenity~"^(fuel|restaurant|cafe|fast_food|hospital|clinic|atm|bank)$"];
                  nwr(around:$SEARCH_RADIUS_M,$around)[highway=motorway_junction];
                );
                out center 40;
            """.trimIndent()

            val body = post(query) ?: return@withContext emptyList()
            parseLandmarks(body)
        }

    /**
     * Finds the roads that branch off the route.
     *
     * Asks for the full geometry (`out geom`) rather than centres, because the whole
     * point is *where* a road touches the route - a shopping street's centroid can sit
     * half a kilometre from the junction the rider is about to ride through.
     *
     * The filtering is done here rather than in the query because Overpass can't express
     * "within 30 m of this polyline"; it can only give us a corridor, and we pick the
     * junctions out of it locally.
     */
    suspend fun roadsAlong(route: Route, maxSamples: Int = 8): List<NearbyRoad> =
        withContext(Dispatchers.IO) {
            val around = aroundClause(route, maxSamples) ?: return@withContext emptyList()

            val query = """
                [out:json][timeout:25];
                way(around:$ROAD_RADIUS_M,$around)[highway~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|motorway_link|trunk_link|primary_link|secondary_link)$"];
                out geom 120;
            """.trimIndent()

            val body = post(query) ?: return@withContext emptyList()
            junctionsWith(route, parseWayGeometries(body))
        }

    // ------------------------------------------------------------------ HTTP

    /** POSTs an Overpass QL query. Null on any failure, by design - see the class docs. */
    private fun post(query: String): String? = try {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        connection.outputStream.use {
            it.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray())
        }
        try {
            if (connection.responseCode != 200) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** The `around:` coordinate list shared by both queries, or null if there's no route. */
    private fun aroundClause(route: Route, maxSamples: Int): String? {
        if (route.isEmpty) return null
        val samples = sampleRoute(route, maxSamples)
        if (samples.isEmpty()) return null
        return samples.joinToString(",") { "%.5f,%.5f".format(it.lat, it.lng) }
    }

    /** Evenly spaced points along the route, so samples cover the whole trip. */
    private fun sampleRoute(route: Route, count: Int): List<LatLng> {
        val total = route.totalDistanceM
        if (total <= 0) return listOf(route.polyline.first())

        return (0 until count).mapNotNull { i ->
            val target = total * (i + 0.5) / count
            val index = route.cumulativeM.indexOfFirst { it >= target }
            route.polyline.getOrNull(if (index >= 0) index else route.polyline.lastIndex)
        }.distinct()
    }

    // ------------------------------------------------------------------ parsing

    private fun parseLandmarks(json: String): List<Landmark> {
        val elements = elementsOf(json) ?: return emptyList()
        val out = ArrayList<Landmark>(elements.length())

        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue

            // Ways and relations report their position under "center" rather than
            // directly, which is why the query asks for `out center`.
            val centre = e.optJSONObject("center")
            val lat = centre?.optDouble("lat") ?: e.optDouble("lat", Double.NaN)
            val lng = centre?.optDouble("lon") ?: e.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) continue

            val tags = e.optJSONObject("tags") ?: continue
            val amenity = tags.optString("amenity")
            val type = when {
                tags.optString("highway") == "motorway_junction" -> Landmark.Type.JUNCTION
                amenity == "fuel" -> Landmark.Type.FUEL
                amenity in setOf("restaurant", "cafe", "fast_food") -> Landmark.Type.FOOD
                amenity in setOf("hospital", "clinic") -> Landmark.Type.HOSPITAL
                amenity in setOf("atm", "bank") -> Landmark.Type.ATM
                else -> Landmark.Type.PLACE
            }

            out.add(Landmark(LatLng(lat, lng), type, tags.optString("name")))
        }
        return out
    }

    /** Pulls the `geometry` arrays out of an `out geom` response. */
    private fun parseWayGeometries(json: String): List<List<LatLng>> {
        val elements = elementsOf(json) ?: return emptyList()
        val out = ArrayList<List<LatLng>>(elements.length())

        for (i in 0 until elements.length()) {
            val geometry = elements.optJSONObject(i)?.optJSONArray("geometry") ?: continue
            val points = ArrayList<LatLng>(geometry.length())
            for (j in 0 until geometry.length()) {
                val node = geometry.optJSONObject(j) ?: continue
                points.add(LatLng(node.optDouble("lat"), node.optDouble("lon")))
            }
            if (points.size >= 2) out.add(points)
        }
        return out
    }

    private fun elementsOf(json: String): JSONArray? = try {
        JSONObject(json).optJSONArray("elements")
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ junctions

    /**
     * Reduces raw way geometry to the stubs worth drawing.
     *
     * For each way, finds the vertex closest to the route. If that vertex is close
     * enough to count as a junction, the stub runs from there along whichever end of the
     * way leads *away* from the route.
     *
     * Two things get discarded here, and both matter:
     *  - arms running roughly parallel to the route, which are the route's own road
     *    coming back at us under a different OSM way id;
     *  - duplicates, since a crossroads is usually four separate ways all anchored on
     *    the same node.
     */
    private fun junctionsWith(route: Route, ways: List<List<LatLng>>): List<NearbyRoad> {
        val out = ArrayList<NearbyRoad>()

        for (way in ways) {
            var bestIndex = -1
            var bestDistance = Double.MAX_VALUE
            for (i in way.indices) {
                val d = distanceToRoute(route, way[i])
                if (d < bestDistance) {
                    bestDistance = d
                    bestIndex = i
                }
            }
            if (bestIndex < 0 || bestDistance > JUNCTION_TOLERANCE_M) continue

            val anchor = way[bestIndex]

            // Walk out from the anchor in whichever direction has more road left, and
            // take the bearing towards a point roughly a stub-length away. Using a far
            // point rather than the immediate neighbour keeps a curved side road from
            // being drawn along its first two-metre segment.
            val forward = way.size - 1 - bestIndex
            val step = if (forward >= bestIndex) 1 else -1
            val far = walkFrom(way, bestIndex, step, STUB_LENGTH_M) ?: continue
            if (Geo.distanceM(anchor, far) < 8.0) continue

            val bearing = Geo.bearingDeg(anchor, far)
            val routeBearing = routeBearingNear(route, anchor)
            val offAxis = abs(Geo.angleDiffDeg(routeBearing, bearing))

            // Within 25 degrees of the route (either way along it) this is the route's
            // own road, not a branch.
            if (offAxis < 25.0 || offAxis > 155.0) continue

            if (out.any {
                    Geo.distanceM(it.position, anchor) < DEDUPE_RADIUS_M &&
                        abs(Geo.angleDiffDeg(it.bearingDeg, bearing)) < 30.0
                }
            ) continue

            out.add(NearbyRoad(anchor, bearing, STUB_LENGTH_M))
        }
        return out
    }

    /** Follows [way] from [index] in direction [step] until [distanceM] has been covered. */
    private fun walkFrom(way: List<LatLng>, index: Int, step: Int, distanceM: Double): LatLng? {
        var travelled = 0.0
        var i = index
        while (i + step in way.indices) {
            travelled += Geo.distanceM(way[i], way[i + step])
            i += step
            if (travelled >= distanceM) return way[i]
        }
        // Ran out of road - the far end is the best direction we have.
        return if (i != index) way[i] else null
    }

    private fun distanceToRoute(route: Route, point: LatLng): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until route.polyline.size - 1) {
            val (_, perp) = Geo.projectOntoSegment(point, route.polyline[i], route.polyline[i + 1])
            if (perp < best) best = perp
        }
        return best
    }

    /** Bearing of the route at its closest approach to [point]. */
    private fun routeBearingNear(route: Route, point: LatLng): Double {
        var best = Double.MAX_VALUE
        var bestIndex = 0
        for (i in 0 until route.polyline.size - 1) {
            val (_, perp) = Geo.projectOntoSegment(point, route.polyline[i], route.polyline[i + 1])
            if (perp < best) {
                best = perp
                bestIndex = i
            }
        }
        return Geo.bearingDeg(route.polyline[bestIndex], route.polyline[bestIndex + 1])
    }
}
