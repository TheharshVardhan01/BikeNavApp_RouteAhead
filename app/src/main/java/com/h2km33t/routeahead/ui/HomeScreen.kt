package com.h2km33t.routeahead.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.h2km33t.routeahead.ble.BleConnectionState
import com.h2km33t.routeahead.nav.NavigationState
import com.h2km33t.routeahead.routing.Place

@Composable
fun HomeScreen(
    navState: NavigationState,
    bleState: BleConnectionState,
    search: SearchUiState,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPickOnMap: () -> Unit,
    onNavigateTo: (Place) -> Unit,
    onClearRecents: () -> Unit,
    onDismissError: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            // safeDrawing rather than a fixed top spacer: this phone has a punch-hole
            // camera and a gesture bar, and hardcoded padding gets one of them wrong.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Header()
        Spacer(Modifier.height(22.dp))

        DeviceCard(state = bleState, onConnect = onConnect, onDisconnect = onDisconnect)

        Spacer(Modifier.height(16.dp))

        SearchField(
            query = search.query,
            onQueryChanged = onQueryChanged,
            onClear = onClearQuery,
            onSearch = { keyboard?.hide() }
        )

        Spacer(Modifier.height(10.dp))

        MapPickerButton(onClick = onPickOnMap)

        navState.error?.let { message ->
            Spacer(Modifier.height(14.dp))
            ErrorBanner(message = message, onDismiss = onDismissError)
        }

        Spacer(Modifier.height(22.dp))

        // Crossfade between the result list, the recents list and the empty hint. Without
        // it, typing makes the whole lower half of the screen snap between states.
        AnimatedContent(
            targetState = search.contentKey(),
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "homeContent"
        ) { key ->
            when (key) {
                HomeContent.SEARCHING -> SearchingRow()
                HomeContent.RESULTS -> PlaceList(
                    label = "Results",
                    places = search.results,
                    icon = Icons.Filled.Place,
                    onClick = onNavigateTo
                )
                HomeContent.RECENTS -> PlaceList(
                    label = "Recent",
                    places = search.recents,
                    icon = Icons.Filled.History,
                    onClick = onNavigateTo,
                    trailing = { TextButton(onClick = onClearRecents) { Text("Clear") } }
                )
                HomeContent.TYPE_MORE -> Hint("Keep typing to search")
                HomeContent.NO_RESULTS -> Hint("Nothing found for that search")
                HomeContent.EMPTY -> EmptyState()
            }
        }
    }
}

private enum class HomeContent { SEARCHING, RESULTS, RECENTS, TYPE_MORE, NO_RESULTS, EMPTY }

private fun SearchUiState.contentKey(): HomeContent = when {
    searching -> HomeContent.SEARCHING
    results.isNotEmpty() -> HomeContent.RESULTS
    query.length in 1..2 -> HomeContent.TYPE_MORE
    query.isNotEmpty() -> HomeContent.NO_RESULTS
    recents.isNotEmpty() -> HomeContent.RECENTS
    else -> HomeContent.EMPTY
}

// ---------------------------------------------------------------------------- header

@Composable
private fun Header() {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(MaterialTheme.shapes.small)
                .background(colors.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.NearMe,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "RouteAhead",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onBackground
            )
            Text(
                "Turn-by-turn on your handlebars",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

// ----------------------------------------------------------------------- device card

@Composable
private fun DeviceCard(
    state: BleConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    // Labels are kept short deliberately: the button and icon leave this column only
    // about half the card width, so a long title ellipsises to nonsense ("Device no...").
    val (label, detail, tint) = when (state) {
        is BleConnectionState.Idle ->
            Triple("Not connected", "Tap connect with the device on", colors.onSurfaceVariant)
        is BleConnectionState.Scanning ->
            Triple("Scanning", "Looking for your device nearby", colors.secondary)
        is BleConnectionState.Connecting ->
            Triple("Connecting", "Pairing with the display", colors.secondary)
        is BleConnectionState.Connected ->
            Triple(state.deviceName, "Connected - MTU ${state.mtu} bytes", colors.primary)
        is BleConnectionState.Reconnecting ->
            Triple("Reconnecting", "Attempt ${state.attempt} - signal dropped", colors.secondary)
        is BleConnectionState.Failed ->
            Triple("Not connected", state.reason, colors.error)
    }

    val busy = state is BleConnectionState.Scanning ||
            state is BleConnectionState.Connecting ||
            state is BleConnectionState.Reconnecting

    AppCard(
        containerColor = colors.surface,
        borderColor = if (state.isReady) colors.primary.copy(alpha = 0.35f) else colors.outlineVariant
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (state.isReady) Icons.Filled.BluetoothConnected
                    else Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = tint, pulsing = busy)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(10.dp))

            if (state.isReady) {
                // An icon, not a "Disconnect" label: the word is wide enough that the
                // device name next to it ellipsises to "BikeNav-Rout...", and the name
                // is the more useful of the two.
                IconButton(onClick = onDisconnect) {
                    Icon(
                        Icons.Filled.BluetoothDisabled,
                        contentDescription = "Disconnect",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                Button(
                    onClick = onConnect,
                    enabled = !busy,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                        disabledContainerColor = colors.surfaceVariant,
                        disabledContentColor = colors.onSurfaceVariant
                    )
                ) {
                    Text(
                        if (busy) "Wait" else "Connect",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------- search

@Composable
private fun SearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Where to?", style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedBorderColor = colors.primary.copy(alpha = 0.55f),
            unfocusedBorderColor = colors.outlineVariant,
            cursorColor = colors.primary,
            focusedTextColor = colors.onSurface,
            unfocusedTextColor = colors.onSurface,
            focusedLeadingIconColor = colors.primary,
            unfocusedLeadingIconColor = colors.onSurfaceVariant,
            focusedPlaceholderColor = colors.onSurfaceVariant,
            unfocusedPlaceholderColor = colors.onSurfaceVariant
        )
    )
}

@Composable
private fun MapPickerButton(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AppCard(onClick = onClick) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Map,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Drop a pin on the map",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface
            )
        }
    }
}

// ------------------------------------------------------------------------ list states

@Composable
private fun PlaceList(
    label: String,
    places: List<Place>,
    icon: ImageVector,
    onClick: (Place) -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel(label)
            trailing?.invoke()
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(places) { place ->
                PlaceRow(place = place, icon = icon, onClick = { onClick(place) })
            }
        }
    }
}

@Composable
private fun PlaceRow(place: Place, icon: ImageVector, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AppCard(onClick = onClick) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    place.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (place.address.isNotBlank()) {
                    Text(
                        place.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchingRow() {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = colors.primary
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Searching...",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EmptyState() {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(colors.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.NearMe,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("Ready to ride", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            "Search for somewhere, or drop a pin on the map to start navigating.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AppCard(
        containerColor = colors.error.copy(alpha = 0.10f),
        borderColor = colors.error.copy(alpha = 0.30f)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = "Dismiss",
                    tint = colors.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
