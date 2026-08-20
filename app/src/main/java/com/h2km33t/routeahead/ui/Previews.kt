package com.h2km33t.routeahead.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.h2km33t.routeahead.ble.BleConnectionState
import com.h2km33t.routeahead.nav.NavPhase
import com.h2km33t.routeahead.nav.NavigationState
import com.h2km33t.routeahead.protocol.LocalPoint
import com.h2km33t.routeahead.routing.Geo
import com.h2km33t.routeahead.routing.LatLng
import com.h2km33t.routeahead.routing.Maneuver
import com.h2km33t.routeahead.routing.Place
import com.h2km33t.routeahead.routing.Route
import com.h2km33t.routeahead.routing.RouteStep
import com.h2km33t.routeahead.ui.theme.RouteAheadTheme

/**
 * Design-time previews. These render in Android Studio's split view with no phone, no
 * emulator and no ESP32 attached, which is the quickest way to check a layout change or
 * see every turn arrow at once.
 *
 * Previews are stripped from the release APK, so this file costs nothing at runtime.
 */

private val surat = LatLng(21.1702, 72.8311)

private val sampleDestination = Place(
    name = "Dumas Beach",
    address = "Dumas, Surat, Gujarat",
    position = LatLng(21.0760, 72.7150)
)

private val sampleRoute = Route(
    polyline = (0..40).map { Geo.offset(surat, 90.0, it * 50.0) },
    steps = listOf(
        RouteStep(Maneuver.DEPART, "Ghod Dod Road", 800.0, 90.0, 0.0),
        RouteStep(Maneuver.RIGHT, "Sardar Patel Ring Road", 1400.0, 130.0, 800.0),
        RouteStep(Maneuver.ARRIVE, "Dumas Beach", 0.0, 0.0, 2200.0)
    ),
    totalDistanceM = 2200.0,
    totalDurationS = 220.0
)

private val navigating = NavigationState(
    phase = NavPhase.NAVIGATING,
    destination = sampleDestination,
    route = sampleRoute,
    position = surat,
    headingDeg = 90f,
    speedKmh = 46.4f,
    maneuver = Maneuver.RIGHT,
    distanceToManeuverM = 275,
    maneuverStreet = "Sardar Patel Ring Road",
    remainingDistanceM = 8430,
    remainingSeconds = 1265,
    // A road bending right, so the preview canvas has something to draw at design time.
    routeAhead = listOf(
        LocalPoint(0f, 0f), LocalPoint(2f, 60f), LocalPoint(6f, 120f),
        LocalPoint(18f, 170f), LocalPoint(46f, 205f), LocalPoint(88f, 225f)
    ),
    nextManeuver = Maneuver.SLIGHT_LEFT,
    distanceToNextManeuverM = 1400
)

// ---------------------------------------------------------------- navigation screen

@Preview(name = "Navigating - turn ahead", showBackground = true, widthDp = 393, heightDp = 830)
@Composable
private fun PreviewNavigating() {
    RouteAheadTheme {
        NavigationScreen(
            state = navigating,
            bleState = BleConnectionState.Connected("BikeNav-RouteAhead", 185),
            onStop = {}
        )
    }
}

@Preview(name = "Navigating - turn imminent", showBackground = true, widthDp = 393, heightDp = 830)
@Composable
private fun PreviewTurnImminent() {
    // Under 80 m the arrow goes accent-coloured and grows - this is the frame that
    // matters most, so it's worth being able to eyeball it without riding anywhere.
    RouteAheadTheme {
        NavigationScreen(
            state = navigating.copy(maneuver = Maneuver.SHARP_LEFT, distanceToManeuverM = 40),
            bleState = BleConnectionState.Connected("BikeNav-RouteAhead", 185),
            onStop = {}
        )
    }
}

@Preview(name = "Navigating - off route", showBackground = true, widthDp = 393, heightDp = 830)
@Composable
private fun PreviewOffRoute() {
    RouteAheadTheme {
        NavigationScreen(
            state = navigating.copy(phase = NavPhase.REROUTING, offRoute = true),
            bleState = BleConnectionState.Connected("BikeNav-RouteAhead", 185),
            onStop = {}
        )
    }
}

@Preview(name = "Navigating - device dropped", showBackground = true, widthDp = 393, heightDp = 830)
@Composable
private fun PreviewDeviceDropped() {
    RouteAheadTheme {
        NavigationScreen(
            state = navigating,
            bleState = BleConnectionState.Reconnecting(2),
            onStop = {}
        )
    }
}

@Preview(name = "Arrived", showBackground = true, widthDp = 393, heightDp = 830)
@Composable
private fun PreviewArrived() {
    RouteAheadTheme {
        NavigationScreen(
            state = navigating.copy(
                phase = NavPhase.ARRIVED,
                maneuver = Maneuver.ARRIVE,
                remainingDistanceM = 0,
                remainingSeconds = 0
            ),
            bleState = BleConnectionState.Connected("BikeNav-RouteAhead", 185),
            onStop = {}
        )
    }
}

@Preview(name = "Routing", showBackground = true, widthDp = 393, heightDp = 830)
@Composable
private fun PreviewRouting() {
    RouteAheadTheme {
        NavigationScreen(
            state = NavigationState(phase = NavPhase.ROUTING, destination = sampleDestination),
            bleState = BleConnectionState.Connected("BikeNav-RouteAhead", 185),
            onStop = {}
        )
    }
}

// ---------------------------------------------------------------- home screen

@Composable
private fun PreviewHome(bleState: BleConnectionState, search: SearchUiState, error: String? = null) {
    RouteAheadTheme {
        HomeScreen(
            navState = NavigationState(error = error),
            bleState = bleState,
            search = search,
            onQueryChanged = {},
            onClearQuery = {},
            onConnect = {},
            onDisconnect = {},
            onPickOnMap = {},
            onNavigateTo = {},
            onClearRecents = {},
            onDismissError = {}
        )
    }
}

@Preview(name = "Home - not connected", showBackground = true, widthDp = 393, heightDp = 830)
@Composable
private fun PreviewHomeIdle() =
    PreviewHome(BleConnectionState.Idle, SearchUiState())

@Preview(name = "Home - connected with recents", showBackground = true, widthDp = 393, heightDp = 830)
@Composable
private fun PreviewHomeConnected() = PreviewHome(
    BleConnectionState.Connected("BikeNav-RouteAhead", 185),
    SearchUiState(
        recents = listOf(
            sampleDestination,
            Place("Surat Railway Station", "Railway Station Road, Surat", LatLng(21.2049, 72.8411)),
            Place("VR Mall", "Dumas Road, Surat", LatLng(21.1458, 72.7708))
        )
    )
)

@Preview(name = "Home - search results", showBackground = true, widthDp = 393, heightDp = 830)
@Composable
private fun PreviewHomeSearching() = PreviewHome(
    BleConnectionState.Connected("BikeNav-RouteAhead", 185),
    SearchUiState(
        query = "dumas",
        results = listOf(
            sampleDestination,
            Place("Dumas Road", "Piplod, Surat, Gujarat", LatLng(21.1458, 72.7708))
        )
    )
)

@Preview(name = "Home - scanning", showBackground = true, widthDp = 393, heightDp = 830)
@Composable
private fun PreviewHomeScanning() =
    PreviewHome(BleConnectionState.Scanning, SearchUiState())

@Preview(name = "Home - error", showBackground = true, widthDp = 393, heightDp = 830)
@Composable
private fun PreviewHomeError() = PreviewHome(
    BleConnectionState.Failed("No device found - is it powered on?"),
    SearchUiState(),
    error = "No road route exists between those points"
)

// ---------------------------------------------------------------- icons

@Preview(name = "All turn arrows", showBackground = true, widthDp = 393, heightDp = 340)
@Composable
private fun PreviewAllManeuvers() {
    RouteAheadTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Maneuver.entries.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { maneuver ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ManeuverIcon(
                                maneuver = maneuver,
                                modifier = Modifier.size(56.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                maneuver.name.replace('_', ' ').lowercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
