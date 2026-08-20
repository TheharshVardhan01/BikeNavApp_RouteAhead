package com.h2km33t.routeahead.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.h2km33t.routeahead.nav.formatDistance
import com.h2km33t.routeahead.routing.Geo
import com.h2km33t.routeahead.routing.LatLng
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Tap the map to drop a destination pin, then confirm.
 *
 * osmdroid is a classic View, so it is hosted in an AndroidView. Its lifecycle callbacks
 * matter more than usual: MapView keeps a tile-download thread pool alive, and skipping
 * onPause/onDetach leaks it every time the rider opens the picker.
 */
@Composable
fun MapPickerScreen(
    initialCentre: LatLng?,
    onCancel: () -> Unit,
    onConfirm: (LatLng) -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    var picked by remember { mutableStateOf<LatLng?>(null) }

    BackHandler(onBack = onCancel)

    val mapView = remember {
        // osmdroid needs its config loaded before any MapView is constructed, otherwise it
        // has nowhere to put its tile cache and silently renders blank tiles. It only wants
        // somewhere to persist that config, so it gets its own SharedPreferences rather
        // than the app-wide default (whose PreferenceManager API is deprecated).
        Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = context.packageName

        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )
            controller.setZoom(15.0)
            // Centre on the rider when we have a fix; otherwise the map opens somewhere
            // meaningless and they have to pan across the country.
            controller.setCenter(
                initialCentre?.let { GeoPoint(it.lat, it.lng) } ?: GeoPoint(21.1702, 72.8311)
            )
        }
    }

    val marker = remember { Marker(mapView).apply { title = "Destination" } }

    DisposableEffect(Unit) {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                picked = LatLng(point.latitude, point.longitude)
                marker.position = point
                if (!mapView.overlays.contains(marker)) mapView.overlays.add(marker)
                mapView.invalidate()
                return true
            }

            override fun longPressHelper(point: GeoPoint): Boolean = false
        }
        // Index 0 so the tap handler sits below the marker and does not swallow its taps.
        mapView.overlays.add(0, MapEventsOverlay(receiver))
        mapView.onResume()

        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Top bar floats over the map rather than pushing it down, so the rider keeps the
        // full map area while still having a way back.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleIconButton(onClick = onCancel) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            if (initialCentre != null) {
                CircleIconButton(onClick = {
                    mapView.controller.animateTo(GeoPoint(initialCentre.lat, initialCentre.lng))
                    mapView.controller.setZoom(16.0)
                }) {
                    Icon(
                        Icons.Filled.MyLocation,
                        contentDescription = "Centre on me",
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
        ) {
            AppCard(containerColor = colors.surface) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (picked != null) "Destination set" else "Pick a destination",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        picked?.let { pin ->
                            val away = initialCentre?.let { origin ->
                                formatDistance(Geo.distanceM(origin, pin).toInt()) + " away"
                            } ?: "Pin dropped"
                            away
                        } ?: "Tap anywhere on the map",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )

                    Spacer(Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) { Text("Cancel", style = MaterialTheme.typography.labelLarge) }

                        Button(
                            onClick = { picked?.let(onConfirm) },
                            enabled = picked != null,
                            modifier = Modifier
                                .weight(1.4f)
                                .height(50.dp),
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary,
                                contentColor = colors.onPrimary,
                                disabledContainerColor = colors.surfaceVariant,
                                disabledContentColor = colors.onSurfaceVariant
                            )
                        ) {
                            Text("Navigate here", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CircleIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            // Nearly opaque: this sits on top of bright map tiles, and a translucent
            // chip over a pale road is unreadable in daylight.
            .background(colors.surface.copy(alpha = 0.94f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
