package com.h2km33t.routeahead.route

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Full-screen map. User taps anywhere to drop a destination pin, then confirms.
 * Returns the picked lat/lng via activity result.
 *
 * Usage from your existing UI:
 *   val intent = Intent(this, DestinationPickerActivity::class.java)
 *   startActivityForResult(intent, REQUEST_PICK_DESTINATION)
 *
 * And handle the result:
 *   override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
 *       if (requestCode == REQUEST_PICK_DESTINATION && resultCode == Activity.RESULT_OK) {
 *           val lat = data?.getDoubleExtra("lat", 0.0) ?: return
 *           val lng = data?.getDoubleExtra("lng", 0.0) ?: return
 *           // now call OsrmClient.getRoute(currentLocation, LatLng(lat, lng)) from a background thread
 *       }
 *   }
 */
class DestinationPickerActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var selectedPoint: GeoPoint? = null
    private var selectedMarker: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid requires this before creating any MapView - loads/saves its own tile cache config
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))

        val root = FrameLayout(this)
        mapView = MapView(this)
        mapView.setTileSource(TileSourceFactory.MAPNIK) // free OSM tile source, no key needed
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(14.0)

        // Default view center - replace with the user's actual current location once GPS is wired in
        mapView.controller.setCenter(GeoPoint(21.1702, 72.8311)) // Surat, adjust as needed

        // Tap-to-pick-destination logic
        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                selectedPoint = point
                placeMarker(point)
                return true
            }

            override fun longPressHelper(point: GeoPoint): Boolean = false
        }
        mapView.overlays.add(MapEventsOverlay(mapEventsReceiver))

        root.addView(mapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val confirmButton = Button(this).apply {
            text = "Confirm destination"
            setOnClickListener { confirmSelection() }
        }
        val buttonParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = 48
        }
        root.addView(confirmButton, buttonParams)

        setContentView(root)
    }

    private fun placeMarker(point: GeoPoint) {
        selectedMarker?.let { mapView.overlays.remove(it) }
        val marker = Marker(mapView)
        marker.position = point
        marker.title = "Destination"
        mapView.overlays.add(marker)
        selectedMarker = marker
        mapView.invalidate()
    }

    private fun confirmSelection() {
        val point = selectedPoint
        if (point == null) {
            // No destination tapped yet - could show a Toast here, kept minimal for now
            return
        }
        val resultIntent = Intent().apply {
            putExtra("lat", point.latitude)
            putExtra("lng", point.longitude)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
}
