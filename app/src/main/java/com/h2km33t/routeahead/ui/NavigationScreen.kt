package com.h2km33t.routeahead.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.h2km33t.routeahead.ble.BleConnectionState
import com.h2km33t.routeahead.nav.NavPhase
import com.h2km33t.routeahead.nav.NavigationState
import com.h2km33t.routeahead.nav.formatDistance
import com.h2km33t.routeahead.nav.formatDuration
import com.h2km33t.routeahead.nav.instructionText
import com.h2km33t.routeahead.protocol.LocalBranch
import com.h2km33t.routeahead.protocol.LocalLandmark
import com.h2km33t.routeahead.protocol.LocalPoint
import com.h2km33t.routeahead.routing.Landmark
import com.h2km33t.routeahead.routing.Maneuver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The riding screen.
 *
 * Laid out for a glance from a handlebar mount at speed, so the hierarchy is strict:
 * the turn and its distance own the top third, the road ahead fills the middle, and
 * everything that can wait sits at the bottom.
 */
@Composable
fun NavigationScreen(
    state: NavigationState,
    bleState: BleConnectionState,
    onStop: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(10.dp))

        TopStatus(state = state, bleState = bleState)

        Spacer(Modifier.height(12.dp))

        when (state.phase) {
            NavPhase.ROUTING -> Box(Modifier.weight(1f), Alignment.Center) { RoutingPanel() }
            NavPhase.ARRIVED -> Box(Modifier.weight(1f), Alignment.Center) { ArrivedPanel(state) }
            else -> {
                InstructionCard(state)
                state.nextManeuver?.let { next ->
                    Spacer(Modifier.height(8.dp))
                    ThenHint(next, state.distanceToNextManeuverM)
                }
                Spacer(Modifier.height(14.dp))
                RouteAheadPreview(
                    points = state.routeAhead,
                    branches = state.branchesAhead,
                    landmarks = state.landmarksAhead,
                    distanceToTurnM = state.distanceToManeuverM,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        StatRow(state)

        Spacer(Modifier.height(12.dp))

        StopButton(arrived = state.phase == NavPhase.ARRIVED, onStop = onStop)

        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------------ top status

@Composable
private fun TopStatus(state: NavigationState, bleState: BleConnectionState) {
    val colors = MaterialTheme.colorScheme

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                state.destination?.name?.let { "To $it" } ?: "Navigating",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(10.dp))
            when {
                state.offRoute -> StatusPill("Rerouting", colors.secondary)
                !bleState.isReady -> StatusPill("Phone only", colors.onSurfaceVariant)
                else -> StatusPill("Device live", colors.primary)
            }
        }

        val route = state.route
        if (route != null && route.totalDistanceM > 0) {
            Spacer(Modifier.height(10.dp))
            val progress by animateFloatAsState(
                targetValue = (1f - state.remainingDistanceM / route.totalDistanceM.toFloat())
                    .coerceIn(0f, 1f),
                animationSpec = tween(600),
                label = "routeProgress"
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape),
                color = colors.primary,
                trackColor = colors.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
        }
    }
}

// ------------------------------------------------------------------ instruction card

@Composable
private fun InstructionCard(state: NavigationState) {
    val colors = MaterialTheme.colorScheme

    // The card goes green inside 80 m so the imminent turn is obvious from peripheral
    // vision alone, before the rider has actually read the number.
    val imminent = state.distanceToManeuverM in 1..80
    val container by animateColorAsState(
        if (imminent) colors.primary.copy(alpha = 0.16f) else colors.surface,
        animationSpec = tween(400), label = "instructionBg"
    )
    val accent by animateColorAsState(
        if (imminent) colors.primary else colors.onSurface,
        animationSpec = tween(400), label = "instructionFg"
    )

    AppCard(
        containerColor = container,
        borderColor = if (imminent) colors.primary.copy(alpha = 0.4f) else colors.outlineVariant
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ManeuverIcon(
                maneuver = state.maneuver,
                modifier = Modifier.size(72.dp),
                color = accent
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    formatDistance(state.distanceToManeuverM),
                    style = MaterialTheme.typography.displaySmall,
                    color = accent,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    instructionText(state.maneuver, state.maneuverStreet, state.roundaboutExit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ThenHint(next: Maneuver, distanceM: Int) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("then", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        ManeuverIcon(
            maneuver = next,
            modifier = Modifier.size(20.dp),
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "in ${formatDistance(distanceM)}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}

// ----------------------------------------------------------------- route ahead canvas

/**
 * Colours for the map canvas.
 *
 * Deliberately not the app's green: on a map, blue-for-your-route and grey-for-everything
 * else is a convention every rider already knows, and borrowing it means the canvas reads
 * as a map at a glance instead of as a chart. The brand green stays where it belongs -
 * on the turn itself.
 */
private object MapPalette {
    val route = Color(0xFF4C8DFF)
    val routeCasing = Color(0xFF17356B)
    val road = Color(0xFF5A6472)
    val roadCasing = Color(0xFF2A3038)
    val ground = Color(0xFF10141A)
}

/**
 * Heading-up map of the road ahead, drawn from the same bike-relative geometry that goes
 * to the ESP32 - so the phone and the handlebar display show the identical junction
 * rather than two different pictures of it.
 *
 * The bike is *anchored*, not fitted. Earlier versions fitted the geometry's bounding box
 * and centred that, which let the bike itself drift around the canvas whenever the road
 * bent; now it sits on the horizontal centre line every frame and the zoom is chosen to
 * fit the geometry around it.
 */
@Composable
private fun RouteAheadPreview(
    points: List<LocalPoint>,
    branches: List<LocalBranch>,
    landmarks: List<LocalLandmark>,
    distanceToTurnM: Int,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme

    // Chevrons marching along the route. A static line tells you the road bends; moving
    // chevrons tell you which way you are going along it.
    val transition = rememberInfiniteTransition(label = "map")
    val chevronPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "chevrons"
    )
    // A slow breath on the turn marker, so an approaching turn is visible in peripheral
    // vision before the number has been read.
    val markerPulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1100, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "markerPulse"
    )

    // How far the geometry reaches from the bike, in metres. Recomputed off the points
    // rather than animated directly, then the resulting zoom is eased - so a reroute
    // glides to its new scale instead of snapping.
    val reach = remember(points, branches) { reachOf(points, branches) }
    val imminent = distanceToTurnM in 1..120

    AppCard(modifier = modifier, containerColor = MapPalette.ground) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            val anchorX = size.width / 2f
            val anchorY = size.height * 0.80f
            val pad = 14f

            val scale = min(
                min((size.width - anchorX - pad) / reach.right, (anchorX - pad) / reach.left),
                min((anchorY - pad) / reach.up, (size.height - anchorY - 4f) / reach.down)
            ).coerceIn(0.05f, 12f)

            fun project(x: Float, y: Float) = Offset(anchorX + x * scale, anchorY - y * scale)

            // --- distance rings, the only thing that gives the zoom a sense of size ---
            val ringStepM = when {
                reach.up > 600f -> 250
                reach.up > 250f -> 100
                else -> 50
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 12f))
            var ring = ringStepM
            while (ring <= reach.up) {
                val y = project(0f, ring.toFloat()).y
                if (y in 0f..size.height) {
                    drawLine(
                        color = colors.outlineVariant.copy(alpha = 0.5f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f,
                        pathEffect = dash
                    )
                }
                ring += ringStepM
            }

            // --- neighbouring roads, underneath everything ---
            // A route line alone in the dark gives no sense of place; the side roads are
            // what make a junction look like a junction.
            for (b in branches) {
                val rad = Math.toRadians(b.headingDeg.toDouble())
                val from = project(b.x, b.y)
                val to = project(
                    b.x + (sin(rad) * b.lengthM).toFloat(),
                    b.y + (cos(rad) * b.lengthM).toFloat()
                )
                drawLine(MapPalette.roadCasing, from, to, 18f, StrokeCap.Round)
                drawLine(MapPalette.road, from, to, 11f, StrokeCap.Round)
            }

            // --- the route itself, cased the way a map app draws one ---
            if (points.size >= 2) {
                val path = Path().apply {
                    val first = project(points.first().x, points.first().y)
                    moveTo(first.x, first.y)
                    points.drop(1).forEach { p ->
                        val o = project(p.x, p.y)
                        lineTo(o.x, o.y)
                    }
                }
                drawPath(
                    path,
                    color = MapPalette.routeCasing,
                    style = Stroke(width = 30f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path,
                    color = MapPalette.route,
                    style = Stroke(width = 20f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                drawChevrons(points, chevronPhase, ::project)
            }

            // --- places beside the road ---
            for (l in landmarks) {
                val at = project(l.x, l.y)
                if (at.x !in 0f..size.width || at.y !in 0f..size.height) continue
                drawLandmark(at, l.typeOrdinal, colors.onSurfaceVariant)
            }

            // --- where the turn actually happens ---
            // Without it the rider can see the road bend but not which bend the countdown
            // refers to.
            if (distanceToTurnM > 0 && points.size >= 2) {
                pointAlong(points, distanceToTurnM.toFloat())?.let { local ->
                    val at = project(local.x, local.y)
                    if (imminent) {
                        drawCircle(
                            color = colors.primary.copy(alpha = 0.30f * (1f - markerPulse)),
                            radius = 16f + 14f * markerPulse,
                            center = at
                        )
                    }
                    drawCircle(MapPalette.ground, radius = 11f, center = at)
                    drawCircle(colors.primary, radius = 8f, center = at)
                }
            }

            // --- the bike, on the anchor, always pointing up ---
            // The slice arrives already rotated heading-up, so "up" is the direction of
            // travel by construction.
            val bike = Offset(anchorX, anchorY)
            drawCircle(MapPalette.ground, radius = 20f, center = bike)
            val marker = Path().apply {
                moveTo(bike.x, bike.y - 18f)
                lineTo(bike.x + 13f, bike.y + 13f)
                lineTo(bike.x, bike.y + 4f)
                lineTo(bike.x - 13f, bike.y + 13f)
                close()
            }
            drawPath(marker, color = Color.White)
            drawPath(
                marker,
                color = MapPalette.routeCasing,
                style = Stroke(width = 2f, join = StrokeJoin.Round)
            )
        }
    }
}

/** How far the drawn geometry reaches from the bike in each direction, in metres. */
private data class Reach(val left: Float, val right: Float, val up: Float, val down: Float)

private fun reachOf(points: List<LocalPoint>, branches: List<LocalBranch>): Reach {
    // Floors of 1 m so a direction the route never goes places no constraint on the
    // zoom, rather than dividing by zero.
    var left = 1f
    var right = 1f
    var up = 1f
    var down = 1f

    fun consider(x: Float, y: Float) {
        right = max(right, x)
        left = max(left, -x)
        up = max(up, y)
        down = max(down, -y)
    }

    points.forEach { consider(it.x, it.y) }
    // Landmarks deliberately do not count: a petrol station 200 m off the road would zoom
    // the whole map out to include it, and it is simply clipped away instead.
    branches.forEach {
        val rad = Math.toRadians(it.headingDeg.toDouble())
        consider(it.x, it.y)
        consider(
            it.x + (sin(rad) * it.lengthM).toFloat(),
            it.y + (cos(rad) * it.lengthM).toFloat()
        )
    }
    return Reach(left, right, up, down)
}

/** Walks the polyline until [distanceM] of it has been covered. Null if it is shorter. */
private fun pointAlong(points: List<LocalPoint>, distanceM: Float): LocalPoint? {
    var travelled = 0f
    for (i in 1 until points.size) {
        val dx = points[i].x - points[i - 1].x
        val dy = points[i].y - points[i - 1].y
        val segLen = hypot(dx, dy)
        if (segLen < 0.01f) continue
        if (travelled + segLen >= distanceM) {
            val t = (distanceM - travelled) / segLen
            return LocalPoint(points[i - 1].x + dx * t, points[i - 1].y + dy * t)
        }
        travelled += segLen
    }
    return null
}

/**
 * Directional chevrons spaced evenly along the drawn route.
 *
 * Spacing is measured in *pixels* rather than metres on purpose: at a 2 km zoom a
 * metre-spaced chevron would be invisible, and at a 40 m zoom there would be three of
 * them. Pixel spacing keeps them looking the same however far the map is zoomed.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChevrons(
    points: List<LocalPoint>,
    phase: Float,
    project: (Float, Float) -> Offset
) {
    val spacing = 46f
    val projected = points.map { project(it.x, it.y) }
    var nextAt = phase * spacing
    var walked = 0f

    for (i in 1 until projected.size) {
        val dx = projected[i].x - projected[i - 1].x
        val dy = projected[i].y - projected[i - 1].y
        val segLen = hypot(dx, dy)
        if (segLen < 1f) continue

        while (nextAt <= walked + segLen) {
            val t = (nextAt - walked) / segLen
            val at = Offset(projected[i - 1].x + dx * t, projected[i - 1].y + dy * t)
            val ux = dx / segLen
            val uy = dy / segLen
            val size = 7f
            val chevron = Path().apply {
                moveTo(at.x - uy * size - ux * size * 0.4f, at.y + ux * size - uy * size * 0.4f)
                lineTo(at.x + ux * size, at.y + uy * size)
                lineTo(at.x + uy * size - ux * size * 0.4f, at.y - ux * size - uy * size * 0.4f)
            }
            drawPath(
                chevron,
                color = Color.White.copy(alpha = 0.85f),
                style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            nextAt += spacing
        }
        walked += segLen
    }
}

/**
 * Glyphs for the places beside the route, matching the ones the firmware draws.
 *
 * Kept small and monochrome: they exist to say "there is a petrol station here", not to
 * compete with the road for attention.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLandmark(
    at: Offset,
    typeOrdinal: Int,
    color: Color
) {
    val type = Landmark.Type.entries.getOrNull(typeOrdinal) ?: Landmark.Type.PLACE
    val stroke = Stroke(width = 2.5f, cap = StrokeCap.Round)

    // A dark disc behind every glyph, so one landing on the route line stays readable.
    drawCircle(MapPalette.ground.copy(alpha = 0.85f), radius = 11f, center = at)

    when (type) {
        Landmark.Type.FUEL -> {
            drawPath(
                Path().apply {
                    moveTo(at.x, at.y - 7f)
                    lineTo(at.x + 5f, at.y + 2f)
                    lineTo(at.x - 5f, at.y + 2f)
                    close()
                },
                color = color
            )
            drawCircle(color, radius = 4f, center = Offset(at.x, at.y + 2f))
        }
        Landmark.Type.FOOD -> {
            drawLine(color, Offset(at.x - 3f, at.y - 6f), Offset(at.x - 3f, at.y + 6f), 2.5f)
            drawLine(color, Offset(at.x + 3f, at.y - 6f), Offset(at.x + 3f, at.y + 6f), 2.5f)
            drawLine(color, Offset(at.x - 3f, at.y), Offset(at.x + 3f, at.y), 2.5f)
        }
        Landmark.Type.HOSPITAL -> {
            drawLine(color, Offset(at.x, at.y - 6f), Offset(at.x, at.y + 6f), 3f)
            drawLine(color, Offset(at.x - 6f, at.y), Offset(at.x + 6f, at.y), 3f)
        }
        Landmark.Type.ATM -> {
            drawPath(
                Path().apply {
                    moveTo(at.x - 6f, at.y - 4f)
                    lineTo(at.x + 6f, at.y - 4f)
                    lineTo(at.x + 6f, at.y + 4f)
                    lineTo(at.x - 6f, at.y + 4f)
                    close()
                },
                color = color,
                style = stroke
            )
            drawCircle(color, radius = 1.6f, center = at)
        }
        Landmark.Type.JUNCTION -> {
            drawPath(
                Path().apply {
                    moveTo(at.x, at.y - 6f)
                    lineTo(at.x + 6f, at.y)
                    lineTo(at.x, at.y + 6f)
                    lineTo(at.x - 6f, at.y)
                    close()
                },
                color = color,
                style = stroke
            )
        }
        Landmark.Type.PLACE -> {
            drawCircle(color, radius = 4f, center = Offset(at.x, at.y - 2f), style = stroke)
            drawLine(color, Offset(at.x, at.y + 1f), Offset(at.x, at.y + 6f), 2.5f)
        }
    }
}

// ------------------------------------------------------------------- other panels

@Composable
private fun RoutingPanel() {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = colors.primary, strokeWidth = 3.dp)
        Spacer(Modifier.height(20.dp))
        Text("Finding a route", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "Working out the best way there",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
    }
}

@Composable
private fun ArrivedPanel(state: NavigationState) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            ManeuverIcon(
                maneuver = Maneuver.ARRIVE,
                modifier = Modifier.size(64.dp),
                color = colors.primary
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "You have arrived",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.primary
        )
        state.destination?.name?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ---------------------------------------------------------------------------- stats

@Composable
private fun StatRow(state: NavigationState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatTile("Speed", "${state.speedKmh.toInt()}", "km/h", Modifier.weight(1f))
        StatTile("Left", formatDistance(state.remainingDistanceM), "", Modifier.weight(1f))
        StatTile(
            "Arrive",
            arrivalClock(state),
            formatDuration(state.remainingSeconds),
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    AppCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SectionLabel(label)
            Spacer(Modifier.height(6.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface,
                maxLines = 1
            )
            Text(
                unit.ifEmpty { " " },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StopButton(arrived: Boolean, onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onStop,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (arrived) colors.primary else colors.error.copy(alpha = 0.14f),
            contentColor = if (arrived) colors.onPrimary else colors.error
        )
    ) {
        if (!arrived) {
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            if (arrived) "Done" else "Stop navigation",
            style = MaterialTheme.typography.labelLarge
        )
    }
}

private fun arrivalClock(state: NavigationState): String {
    if (state.remainingSeconds <= 0) return "--:--"
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(state.arrivalEpochMs))
}
