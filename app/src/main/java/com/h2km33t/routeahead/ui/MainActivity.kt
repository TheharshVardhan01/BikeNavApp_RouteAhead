package com.h2km33t.routeahead.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.h2km33t.routeahead.nav.NavPhase
import com.h2km33t.routeahead.ui.theme.RouteAheadTheme

/** Which screen is showing. Small enough that a sealed class beats a Navigation graph. */
private enum class Screen { HOME, MAP_PICKER, NAVIGATING }

class MainActivity : ComponentActivity() {

    /** Everything requested up front, including the optional notification permission. */
    private val requestedPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars. The app is dark-only, so both bars get light icons
        // regardless of the system theme - passing the dark style for each pins that.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        // The phone usually sits in a mount while riding, so let the screen stay on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            RouteAheadTheme {
                var hasPermissions by remember { mutableStateOf(hasRequiredPermissions()) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    // POST_NOTIFICATIONS being denied is survivable - navigation still runs,
                    // the rider just loses the ongoing notification. Location and Bluetooth
                    // are not, which is why only those gate the UI.
                    hasPermissions = hasRequiredPermissions()
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (hasPermissions) {
                        RouteAheadApp()
                    } else {
                        PermissionGate(onGrant = { permissionLauncher.launch(requestedPermissions) })
                    }
                }
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val required = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

@Composable
private fun RouteAheadApp(viewModel: NavViewModel = viewModel()) {
    val navState by viewModel.navState.collectAsStateWithLifecycle()
    val bleState by viewModel.bleState.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf(Screen.HOME) }

    // The navigation screen follows the session rather than being pushed manually, so it
    // also comes back when a ride is already running and the rider reopens the app.
    val effective = when (navState.phase) {
        NavPhase.ROUTING, NavPhase.NAVIGATING, NavPhase.REROUTING, NavPhase.ARRIVED ->
            Screen.NAVIGATING
        NavPhase.IDLE -> screen
    }

    AnimatedContent(
        targetState = effective,
        transitionSpec = {
            (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.98f)) togetherWith
                    (fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 1.02f))
        },
        label = "screen"
    ) { current ->
        when (current) {
            Screen.HOME -> HomeScreen(
                navState = navState,
                bleState = bleState,
                search = search,
                onQueryChanged = viewModel::onQueryChanged,
                onClearQuery = viewModel::clearQuery,
                onConnect = viewModel::connectDevice,
                onDisconnect = viewModel::disconnectDevice,
                onPickOnMap = { screen = Screen.MAP_PICKER },
                onNavigateTo = { place ->
                    viewModel.clearQuery()
                    viewModel.startNavigation(place)
                },
                onClearRecents = viewModel::clearRecents,
                onDismissError = viewModel::clearError
            )

            Screen.MAP_PICKER -> MapPickerScreen(
                initialCentre = navState.position,
                onCancel = { screen = Screen.HOME },
                onConfirm = { position ->
                    screen = Screen.HOME
                    viewModel.startNavigationTo(position)
                }
            )

            Screen.NAVIGATING -> NavigationScreen(
                state = navState,
                bleState = bleState,
                onStop = {
                    viewModel.stopNavigation()
                    screen = Screen.HOME
                }
            )
        }
    }
}

@Composable
private fun PermissionGate(onGrant: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.NearMe,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(Modifier.height(28.dp))

        Text(
            "A couple of permissions",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Location to follow the route, and Bluetooth to talk to the display on your " +
                    "handlebars. Nothing leaves your phone except the two points needed to " +
                    "look up a route.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onGrant,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary
            )
        ) {
            Text("Continue", style = MaterialTheme.typography.labelLarge)
        }
    }
}
