package com.h2km33t.routeahead

import android.app.Application
import com.h2km33t.routeahead.ble.BleRouteClient
import com.h2km33t.routeahead.nav.NavigationController

/**
 * Holds the two objects that must outlive any Activity: the BLE link and the
 * navigation session. Hand-rolled rather than pulling in Hilt - there are exactly
 * two singletons here and a DI framework would be more machinery than the app has
 * dependencies.
 */
class RouteAheadApplication : Application() {

    val bleClient: BleRouteClient by lazy { BleRouteClient(this) }

    val navigationController: NavigationController by lazy {
        NavigationController(this, bleClient)
    }
}
