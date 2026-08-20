package com.h2km33t.routeahead.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.h2km33t.routeahead.RouteAheadApplication
import com.h2km33t.routeahead.ble.BleConnectionState
import com.h2km33t.routeahead.nav.NavigationService
import com.h2km33t.routeahead.nav.NavigationState
import com.h2km33t.routeahead.routing.LatLng
import com.h2km33t.routeahead.routing.NominatimClient
import com.h2km33t.routeahead.routing.Place
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the search sheet is currently doing. */
data class SearchUiState(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val searching: Boolean = false,
    val recents: List<Place> = emptyList()
)

class NavViewModel(app: Application) : AndroidViewModel(app) {

    private val routeAheadApp = app as RouteAheadApplication
    private val controller = routeAheadApp.navigationController
    private val recentStore = RecentDestinations(app)

    val navState: StateFlow<NavigationState> = controller.state
    val bleState: StateFlow<BleConnectionState> = routeAheadApp.bleClient.state

    private val _search = MutableStateFlow(SearchUiState(recents = recentStore.load()))
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        // Start tracking position immediately so the map opens on the rider rather than
        // on a hardcoded default, and so routing has an origin the instant they pick one.
        controller.startLocationUpdates()
        observeQuery()
    }

    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        viewModelScope.launch {
            queryFlow
                .debounce(400) // Nominatim's usage policy caps this at 1 req/sec
                .distinctUntilChanged()
                .collect { query ->
                    if (query.length < 3) {
                        _search.update { it.copy(results = emptyList(), searching = false) }
                        return@collect
                    }
                    _search.update { it.copy(searching = true) }
                    val results = NominatimClient.search(query, near = navState.value.position)
                    _search.update { it.copy(results = results, searching = false) }
                }
        }
    }

    // ---------------------------------------------------------------- BLE

    fun connectDevice() = routeAheadApp.bleClient.connect()

    fun disconnectDevice() = routeAheadApp.bleClient.disconnect()

    fun forgetDevice() {
        routeAheadApp.bleClient.disconnect()
        routeAheadApp.bleClient.forgetDevice()
    }

    // ---------------------------------------------------------------- search

    fun onQueryChanged(query: String) {
        _search.update { it.copy(query = query) }
        queryFlow.value = query
    }

    fun clearQuery() {
        _search.update { it.copy(query = "", results = emptyList(), searching = false) }
        queryFlow.value = ""
    }

    // ---------------------------------------------------------------- navigation

    fun startNavigation(place: Place) {
        recentStore.add(place)
        _search.update { it.copy(recents = recentStore.load()) }

        controller.startNavigation(place)
        // The service must be running before the screen locks, so start it alongside the
        // route request rather than waiting for the route to come back.
        NavigationService.start(routeAheadApp)
    }

    /** Starts navigation to a raw coordinate picked from the map. */
    fun startNavigationTo(position: LatLng, label: String = "Dropped pin") {
        startNavigation(Place(name = label, address = "", position = position))
    }

    fun stopNavigation() {
        controller.stopNavigation()
        NavigationService.stop(routeAheadApp)
    }

    fun clearError() = controller.clearError()

    fun clearRecents() {
        recentStore.clear()
        _search.update { it.copy(recents = emptyList()) }
    }
}
