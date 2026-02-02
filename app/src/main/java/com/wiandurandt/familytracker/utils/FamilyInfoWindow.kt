package com.wiandurandt.familytracker.utils

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.views.overlay.Marker
import com.wiandurandt.familytracker.R

class FamilyInfoWindow(layoutResId: Int, mapView: MapView) : InfoWindow(layoutResId, mapView) {

    override fun onOpen(item: Any?) {
        val marker = item as? Marker ?: return
        val view = view

        val txtName = view.findViewById<TextView>(R.id.txtName)
        val txtAddress = view.findViewById<TextView>(R.id.txtAddress)
        val txtBattery = view.findViewById<TextView>(R.id.txtBattery)
        val txtSpeed = view.findViewById<TextView>(R.id.txtSpeed)
        val txtTime = view.findViewById<TextView>(R.id.txtTime)
        val imgProfile = view.findViewById<ImageView>(R.id.imgProfile)

        // Name
        txtName.text = marker.title

        // Snippet Format: "📍 123 Street... \n 🚗 45 km/h • 5m ago 🔋85%"
        // We need to parse this or pass a richer object. 
        // For simplicity, let's parse the string we created in MapFragment.
        
        val snippet = marker.snippet ?: ""
        
        // Split by newline to separate Address from Stats
        val parts = snippet.split("\n")
        val addressPart = if (parts.isNotEmpty()) parts[0] else ""
        val statsPart = if (parts.size > 1) parts[1] else ""
        
        txtAddress.text = addressPart.replace("📍", "").trim()

        // Parse Stats: "🚗 45 km/h • 5m ago 🔋85%"
        // Simple heuristic splitting
        if (statsPart.contains("🔋")) {
            val battery = statsPart.substringAfter("🔋").trim()
            txtBattery.text = "🔋 $battery"
        } else {
             txtBattery.text = "🔋 --%"
        }
        
        // Speed
        if (statsPart.contains("km/h")) {
             // Extract something like "🚗 45 km/h"
             val speed = statsPart.substringBefore("•").trim()
             txtSpeed.text = speed
        } else {
            txtSpeed.text = "Stationary"
        }

        // Time
        if (statsPart.contains("•")) {
             val time = statsPart.substringAfter("•").substringBefore("🔋").trim()
             txtTime.text = "🕒 $time"
        } else {
             txtTime.text = "🕒 Now"
        }
        
        // Profile Image: The marker icon is already the circular bitmap!
        // So we can just reuse the marker's icon drawable provided it is a BitmapDrawable
        val icon = marker.icon
        if (icon != null) {
            imgProfile.setImageDrawable(icon)
        }
    }

    override fun onClose() {
        // Cleanup if needed
    }
}
