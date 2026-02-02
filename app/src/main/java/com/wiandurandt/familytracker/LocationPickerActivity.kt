package com.wiandurandt.familytracker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

class LocationPickerActivity : AppCompatActivity() {

    private lateinit var map: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // OSM Configuration
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        
        setContentView(R.layout.activity_location_picker)

        map = findViewById(R.id.mapPicker)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0) // Reverted zoom for precision
        
        // Default to a known location or try to get last known?
        // Ideally we pass in the current user location if available via Intent
        val lat = intent.getDoubleExtra("LAT", 0.0)
        val lon = intent.getDoubleExtra("LON", 0.0)
        
        if (lat != 0.0 && lon != 0.0) {
            map.controller.setCenter(GeoPoint(lat, lon))
        } else {
            // Default center if nothing passed (e.g. User location)
            // Just center on Pretoria roughly or generic
            map.controller.setCenter(GeoPoint(-26.2041, 28.0473)) // JHB default
        }

        findViewById<Button>(R.id.btnConfirmLocation).setOnClickListener {
            val center = map.mapCenter
            val resultIntent = Intent()
            resultIntent.putExtra("LAT", center.latitude)
            resultIntent.putExtra("LON", center.longitude)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}
