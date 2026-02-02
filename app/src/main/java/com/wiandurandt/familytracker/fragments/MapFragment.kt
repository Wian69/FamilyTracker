package com.wiandurandt.familytracker.fragments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.wiandurandt.familytracker.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MapFragment : Fragment() {

    private lateinit var map: MapView
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private var currentFamilyId: String? = null
    private val markersMap = HashMap<String, Marker>()
    private val userBitmaps = HashMap<String, Bitmap>() // Cache for other users
    private lateinit var locationOverlay: MyLocationNewOverlay
    
    // Cache the raw profile bitmap so we can re-badge it efficiently
    private var myRawProfileBitmap: Bitmap? = null

    // Listeners to clean up
    private var familyListener: ValueEventListener? = null
    private var familyRef: DatabaseReference? = null
    
    private var usersListener: ChildEventListener? = null
    private var placesListener: ValueEventListener? = null
    private var placesRef: DatabaseReference? = null

    private val DB_URL = "https://familiy-tracker-default-rtdb.firebaseio.com/"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Configuration.getInstance().load(requireContext(), PreferenceManager.getDefaultSharedPreferences(requireContext()))
        val view = inflater.inflate(R.layout.fragment_map, container, false)
        database = FirebaseDatabase.getInstance(DB_URL).getReference("users") // initialized here
        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Cleanup listeners
        if (familyListener != null && familyRef != null) familyRef!!.removeEventListener(familyListener!!)
        if (usersListener != null) database.removeEventListener(usersListener!!)
        if (placesListener != null && placesRef != null) placesRef!!.removeEventListener(placesListener!!)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        map = view.findViewById(R.id.map)
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance(DB_URL).getReference("users")

        setupMap()
        fetchUserFamilyId() // This was already here, keep it.

        view.findViewById<View>(R.id.btnCenter).setOnClickListener {
            val location = locationOverlay.myLocation
            if (location != null) {
                locationOverlay.enableFollowLocation()
                map.controller.animateTo(location)
                focusedMemberUid = null // Reset focus to self
                clearHistory() // Clear history when recentering
            } else {
                Toast.makeText(requireContext(), "Waiting for location...", Toast.LENGTH_SHORT).show()
            }
        }
        
        view.findViewById<View>(R.id.btnHistory).setOnClickListener {
            toggleHistory()
        }

        view.findViewById<View>(R.id.btnPanic).setOnClickListener {
            triggerPanic()
        }
    }

    private fun triggerPanic() {
        val uid = auth.currentUser?.uid ?: return
        val userRef = database.child(uid)
        
        val updates = HashMap<String, Any>()
        updates["panicActive"] = true
        updates["panicTimestamp"] = ServerValue.TIMESTAMP
        
        userRef.updateChildren(updates).addOnSuccessListener {
            Toast.makeText(context, "🚨 SOS SENT! EVERYONE NOTIFIED.", Toast.LENGTH_LONG).show()
            // Optional: reset after 10 seconds?
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                userRef.child("panicActive").setValue(false)
            }, 30000) // 30 seconds of alert
        }.addOnFailureListener {
            Toast.makeText(context, "Crisis! SOS Failed to send.", Toast.LENGTH_SHORT).show()
        }
    }
    
    private var focusedMemberUid: String? = null
    private var historyOverlay: org.osmdroid.views.overlay.Polyline? = null
    private var isHistoryVisible = false
    
    private fun toggleHistory() {
        if (isHistoryVisible) {
            clearHistory()
            return
        }
        
        val uidToCheck = focusedMemberUid ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        
        // Fetch History for TODAY
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val dateKey = sdf.format(java.util.Date())
        
        val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://familiy-tracker-default-rtdb.firebaseio.com/")
        val ref = db.getReference("history").child(uidToCheck).child(dateKey)
        
        Toast.makeText(context, "Loading history...", Toast.LENGTH_SHORT).show()
        
        ref.addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val points = ArrayList<GeoPoint>()
                for (child in snapshot.children) {
                    val lat = child.child("lat").getValue(Double::class.java)
                    val lon = child.child("lon").getValue(Double::class.java)
                    if (lat != null && lon != null) {
                        points.add(GeoPoint(lat, lon))
                    }
                }
                
                if (points.isNotEmpty()) {
                    drawHistoryLine(points)
                } else {
                    Toast.makeText(context, "No history found for today.", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        })
    }
    
    private fun drawHistoryLine(points: ArrayList<GeoPoint>) {
        clearHistory() // Remove old lines
        
        historyOverlay = org.osmdroid.views.overlay.Polyline()
        historyOverlay?.setPoints(points)
        historyOverlay?.outlinePaint?.color = android.graphics.Color.BLUE
        historyOverlay?.outlinePaint?.strokeWidth = 10f
        
        map.overlays.add(0, historyOverlay) // Add at bottom
        map.invalidate()
        isHistoryVisible = true
        
        view?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnHistory)?.backgroundTintList = 
            android.content.res.ColorStateList.valueOf(android.graphics.Color.BLUE)
        view?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnHistory)?.setColorFilter(android.graphics.Color.WHITE)
    }
    
    private fun clearHistory() {
        if (historyOverlay != null) {
            map.overlays.remove(historyOverlay)
            map.invalidate()
            historyOverlay = null
        }
        isHistoryVisible = false
        val surfaceColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.surface)
        val primaryColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primary)
        
        val btnHistory = view?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnHistory)
        btnHistory?.backgroundTintList = android.content.res.ColorStateList.valueOf(surfaceColor)
        btnHistory?.setColorFilter(primaryColor)
    }

    private fun savePlaceToFirebase(name: String, lat: Double, lon: Double, radius: Double) {
        if (currentFamilyId == null) {
            // Fallback: Try to fetch it directly
            val uid = auth.currentUser?.uid
            if (uid != null) {
                database.child(uid).child("familyId").get().addOnSuccessListener { snapshot ->
                    val fetchedId = snapshot.getValue(String::class.java)
                    if (fetchedId != null) {
                        currentFamilyId = fetchedId
                        // Retry save
                        savePlaceToFamily(fetchedId, name, lat, lon, radius)
                    } else {
                        Toast.makeText(requireContext(), "Error: No Family ID found. Please check Profile.", Toast.LENGTH_LONG).show()
                    }
                }.addOnFailureListener {
                    Toast.makeText(requireContext(), "Error checking family status.", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        
        savePlaceToFamily(currentFamilyId!!, name, lat, lon, radius)
    }

    private fun savePlaceToFamily(familyId: String, name: String, lat: Double, lon: Double, radius: Double) {
        val familyRef = FirebaseDatabase.getInstance(DB_URL).getReference("families").child(familyId)
        // Note: familyRef is never null from getReference()
        
        val placeId = familyRef.child("places").push().key ?: return
        
        val placeData = mapOf(
            "name" to name,
            "latitude" to lat,
            "longitude" to lon,
            "radius" to radius
        )
        
        familyRef.child("places").child(placeId).setValue(placeData)
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Safe Place Added!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "Failed to save: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(20.0) // Higher zoom for more detail (stores/businesses)

        // Use Custom Overlay
        locationOverlay = CustomMyLocationOverlay(GpsMyLocationProvider(requireContext()), map)
        locationOverlay.enableMyLocation()
        locationOverlay.enableFollowLocation() // Auto-center
        map.overlays.add(locationOverlay)
        
        // Tap to Close Sheet
        val eventsOverlay = org.osmdroid.views.overlay.MapEventsOverlay(object : org.osmdroid.events.MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                view?.findViewById<View>(R.id.cardUserDetails)?.visibility = View.GONE
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                return false
            }
        })
        map.overlays.add(eventsOverlay)
    }

    // Custom Overlay: ONLY used to receive location updates and move 'meMarker'.
    // We suppress the default drawing to avoid "Double Profile" issues.
    inner class CustomMyLocationOverlay(provider: GpsMyLocationProvider, mapView: MapView) 
        : MyLocationNewOverlay(provider, mapView) {
        
        private var myMeMarker: Marker? = null

        override fun onLocationChanged(location: android.location.Location?, source: org.osmdroid.views.overlay.mylocation.IMyLocationProvider?) {
            // Do NOT call super.onLocationChanged logic that triggers redraws of the default icon
            // But we DO need to update the location for 'follow location' logic if enabled
            super.onLocationChanged(location, source)
            
            if (location != null) {
                // Update "Me" Marker
                if (myMeMarker == null) {
                    myMeMarker = Marker(map)
                    myMeMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    myMeMarker?.id = "ME_MARKER"
                    
                    // Allow clicking me -> Show Bottom Sheet
                    myMeMarker?.setOnMarkerClickListener { marker, mapView -> 
                        showBottomSheetForMarker(marker)
                        true
                    }
                    
                    map.overlays.add(myMeMarker)
                }
                
                myMeMarker?.position = GeoPoint(location.latitude, location.longitude)
                myMeMarker?.title = "Me"
                
                // Update Icon
                if (myRawProfileBitmap != null) {
                    val speedKmh = (location.speed * 3.6).toInt()
                    val emoji = getActivityEmoji(speedKmh)
                    try {
                         val badged = drawBadgeOnBitmap(myRawProfileBitmap!!, emoji)
                         myMeMarker?.icon = BitmapDrawable(resources, badged)
                    } catch (e: Exception) {}
                }
                
                // Live Data Construction
                val batteryPct = (context?.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager)?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val batteryText = if (batteryPct > 0) "$batteryPct%" else "..."
                val speedKmh = (location.speed * 3.6).toInt()
                val statusText = if (speedKmh > 2) "Moving" else "At Location"
                
                // Store data for click
                val data = HashMap<String, Any>()
                data["name"] = "Me"
                data["battery"] = batteryText
                data["speed"] = "$speedKmh km/h"
                data["time"] = "Now"
                data["isMe"] = true
                if (myRawProfileBitmap != null) {
                     // We can't easily put bitmap in map, but we can re-use the imageview source or global var
                }
                
                // We attach the data map to the marker
                myMeMarker?.relatedObject = data
                
                // Fetch Address
                fetchAddressForMarker(myMeMarker!!, location.latitude, location.longitude, data)

                map.postInvalidate()
            }
        }
        
        // DISABLE default drawing
        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) { }
    }
    
    // Generalized Address Fetcher
    private fun fetchAddressForMarker(marker: Marker, lat: Double, lon: Double, data: HashMap<String, Any>) {
         Thread {
            try {
                val geocoder = android.location.Geocoder(requireContext(), java.util.Locale.getDefault())
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                
                var addressText = "Unknown Location"
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val street = address.thoroughfare ?: ""
                    val num = address.subThoroughfare ?: ""
                    val suburb = address.locality ?: address.subLocality ?: ""
                    addressText = if (street.isNotEmpty()) "$num $street, $suburb".trim() else suburb
                }
                
                data["address"] = addressText
                marker.relatedObject = data // Update
                
                // If currently showing this marker, update UI
                activity?.runOnUiThread {
                    if (view?.findViewById<View>(R.id.cardUserDetails)?.visibility == View.VISIBLE) {
                        // Check if sheet is showing THIS marker? simpler: just refresh if clicked
                        // For now we just update
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun showBottomSheetForMarker(marker: Marker) {
        val sheet = view?.findViewById<View>(R.id.cardUserDetails) ?: return
        val data = marker.relatedObject as? HashMap<String, Any> ?: return
        
        val txtName = sheet.findViewById<android.widget.TextView>(R.id.txtSheetName)
        val txtAddress = sheet.findViewById<android.widget.TextView>(R.id.txtSheetAddress)
        val txtBat = sheet.findViewById<android.widget.TextView>(R.id.txtSheetBattery)
        val txtSpeed = sheet.findViewById<android.widget.TextView>(R.id.txtSheetSpeed)
        val txtTime = sheet.findViewById<android.widget.TextView>(R.id.txtSheetTime)
        val imgProfile = sheet.findViewById<android.widget.ImageView>(R.id.imgSheetProfile)
        
        txtName.text = data["name"] as? String ?: "Unknown"
        txtAddress.text = data["address"] as? String ?: "Loading address..."
        txtBat.text = "🔋 " + (data["battery"] as? String ?: "--%")
        
        // Dynamic Emoji for Speed
        val speedStr = data["speed"] as? String ?: "0 km/h"
        val speedVal = speedStr.replace(" km/h", "").toIntOrNull() ?: 0
        val activityEmoji = getActivityEmoji(speedVal)
        val finalEmoji = if (activityEmoji.isNotEmpty()) activityEmoji else "🚗" // Fallback or Stationary icon? Maybe 🛑 or just Car for default
        
        txtSpeed.text = "$finalEmoji " + speedStr
        txtTime.text = "🕒 " + (data["time"] as? String ?: "Now")
        
        // Icon logic
        if (data["isMe"] == true && myRawProfileBitmap != null) {
            imgProfile.setImageBitmap(myRawProfileBitmap)
        } else {
            // For others, we might need to fetch or use the marker icon
             imgProfile.setImageDrawable(marker.icon)
        }
        
        sheet.visibility = View.VISIBLE
        
        // Center Map on User
        map.controller.animateTo(marker.position)
    }
    
    private fun getActivityEmoji(speedKmh: Int): String {
        return when {
            speedKmh > 200 -> "✈️" // Flying
            speedKmh > 35 -> "🚗" // Driving
            speedKmh > 10 -> "🚴" // Cycling/Running
            speedKmh > 2 -> "🚶" // Walking/Slow Move
            else -> "" // Stationary
        }
    }

    private fun fetchUserFamilyId() {
        val uid = auth.currentUser?.uid ?: return
        
        // Force refresh
        FirebaseDatabase.getInstance(DB_URL).goOnline()
        
        familyRef = database.child(uid).child("familyId")
        
        // Timeout/Fallback Logic for Map
        val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (currentFamilyId == null) {
                Toast.makeText(context, "Map: Defaulting to 'Durandt'", Toast.LENGTH_SHORT).show()
                updateFamilyId("Durandt")
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable, 5000)

        familyListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                val newFamilyId = snapshot.getValue(String::class.java)
                
                if (newFamilyId != null) {
                    updateFamilyId(newFamilyId)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                timeoutHandler.removeCallbacks(timeoutRunnable)
            }
        }
        familyRef!!.addValueEventListener(familyListener!!)
        
        // Also fetch profile pic
        database.child(uid).child("profileBase64").get().addOnSuccessListener { snapshot ->
            val base64 = snapshot.getValue(String::class.java)
            if (base64 != null) {
                try {
                    val imageBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    val decodedImage = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    val circularBitmap = getCircularBitmap(decodedImage)
                    myRawProfileBitmap = android.graphics.Bitmap.createScaledBitmap(circularBitmap, 150, 150, true)
                    
                    // Initial Set
                    locationOverlay.setPersonIcon(myRawProfileBitmap)
                    locationOverlay.setDirectionIcon(myRawProfileBitmap) 
                    locationOverlay.setPersonHotspot(myRawProfileBitmap!!.width / 2f, myRawProfileBitmap!!.height / 2f)
                } catch (e: Exception) {}
            }
        }
    }
    
    private fun updateFamilyId(newId: String) {
        if (newId != currentFamilyId) {
            currentFamilyId = newId
            
            // Clear old data
            markersMap.values.forEach { map.overlays.remove(it) }
            markersMap.clear()
            
            val toRemove = ArrayList<org.osmdroid.views.overlay.Overlay>()
            map.overlays.forEach { if (it is org.osmdroid.views.overlay.Polygon) toRemove.add(it) }
            map.overlays.removeAll(toRemove)
            map.invalidate()

            // Cleanup old listeners
            if (usersListener != null) database.removeEventListener(usersListener!!)
            if (placesListener != null && placesRef != null) placesRef!!.removeEventListener(placesListener!!)

            // Start new listeners
            listenForUpdates() 
            listenForPlaces() 
        }
    }

    private fun listenForUpdates() {
        usersListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                updateUserMarker(snapshot)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                updateUserMarker(snapshot)
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val uid = snapshot.key
                if (uid != null && markersMap.containsKey(uid)) {
                    val marker = markersMap[uid]
                    map.overlays.remove(marker)
                    markersMap.remove(uid)
                    map.invalidate()
                }
            }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        database.addChildEventListener(usersListener!!)
    }

    private fun updateUserMarker(snapshot: DataSnapshot) {
        val uid = snapshot.key ?: return
        if (uid == auth.currentUser?.uid) return

        val userFamilyId = snapshot.child("familyId").getValue(String::class.java)
        if (userFamilyId != currentFamilyId) return

        val lat = snapshot.child("latitude").getValue(Double::class.java)
        val lon = snapshot.child("longitude").getValue(Double::class.java)

        if (lat != null && lon != null) {
            val point = GeoPoint(lat, lon)
            
            // Restore missing variables
            val profileBase64 = snapshot.child("profileBase64").getValue(String::class.java)
            val speed = snapshot.child("speed").getValue(Float::class.java) ?: 0f
            
            val batteryLevel = snapshot.child("batteryLevel").getValue(Int::class.java) ?: -1
            val lastUpdated = snapshot.child("lastUpdated").getValue(Long::class.java) ?: 0L
            
            val speedKmh = (speed * 3.6).toInt()
            val activityEmoji = getActivityEmoji(speedKmh)
            
            // Time Ago Logic
            val timeDiff = System.currentTimeMillis() - lastUpdated
            val minutesAgo = timeDiff / (1000 * 60)
            val timeText = if (minutesAgo < 1) "Now" else "${minutesAgo}m ago"
            val isStale = minutesAgo > 15
            
            val statusIcon = if (isStale) "⚠️" else if (speedKmh > 2) "🚗" else "📍"

            val marker: Marker
            if (markersMap.containsKey(uid)) {
                marker = markersMap[uid]!!
                marker.position = point
            } else {
                marker = Marker(map)
                marker.position = point
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.title = snapshot.child("email").getValue(String::class.java) ?: "Family Member"
                marker.icon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_launcher_foreground)
                
                // Click Listener -> Bottom Sheet
                marker.setOnMarkerClickListener { m, _ -> 
                    showBottomSheetForMarker(m)
                    true
                }
                
                map.overlays.add(marker)
                markersMap[uid] = marker
            }
            
            // Initial simple snippet
            val batteryText = if (batteryLevel > 0) "$batteryLevel%" else "..."
            
            // Build Data Map
            val data = HashMap<String, Any>()
            val name = snapshot.child("email").getValue(String::class.java) ?: "Family Member"
            // Start shortening email to name if possible, or just use email for now
            val shortName = name.substringBefore("@")
            
            data["name"] = shortName
            data["battery"] = batteryText
            data["speed"] = "$speedKmh km/h"
            data["time"] = timeText
            data["isMe"] = false
            
            // Attach to marker
            marker.relatedObject = data 
            
            // Async Address Fetch (Reuse common function)
            fetchAddressForMarker(marker, lat, lon, data)

            if (profileBase64 != null) {
                if (!userBitmaps.containsKey(uid)) {
                    try {
                        val imageBytes = android.util.Base64.decode(profileBase64, android.util.Base64.DEFAULT)
                        val decodedImage = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        val circularBitmap = getCircularBitmap(decodedImage)
                        val scaled = android.graphics.Bitmap.createScaledBitmap(circularBitmap, 150, 150, true)
                        userBitmaps[uid] = scaled
                    } catch (e: Exception) {}
                }

                val cachedBitmap = userBitmaps[uid]
                if (cachedBitmap != null) {
                    // Note: We are using relatedObject for DATA now.
                    // We need a way to store the emoji state separate from the data map?
                    // Or just add "lastEmoji" to the map data.
                    val lastEmoji = data["lastEmoji"] as? String
                    
                    if (lastEmoji != activityEmoji) {
                        val badgedBitmap = drawBadgeOnBitmap(cachedBitmap, activityEmoji)
                        marker.icon = BitmapDrawable(resources, badgedBitmap)
                        data["lastEmoji"] = activityEmoji // Update state
                        map.invalidate()
                    }
                }
            }
            map.invalidate()
        } else {
             // Debug: User is in family but has no location
             val email = snapshot.child("email").getValue(String::class.java) ?: "Member"
             val hasLat = snapshot.hasChild("latitude")
             
             if (!hasLat) {
                 android.util.Log.d("MapFragment", "User $email is in family but has no location yet.")
                 // Only toast if it's likely a new addition to avoid spam loop
                 if (context != null) {
                     // Toast.makeText(context, "$email joined but needs to enable Location.", Toast.LENGTH_SHORT).show()
                 }
             }
        }
    }

    private fun listenForPlaces() {
        if (currentFamilyId == null) return
        placesRef = FirebaseDatabase.getInstance(DB_URL).getReference("families").child(currentFamilyId!!).child("places")
        
        placesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Clear old Place overlays (Polygons + Place Markers)
                val toRemove = ArrayList<org.osmdroid.views.overlay.Overlay>()
                map.overlays.forEach { 
                    if (it is org.osmdroid.views.overlay.Polygon) toRemove.add(it)
                    if (it is Marker && it.id != null && it.id.startsWith("PLACE_")) toRemove.add(it)
                }
                map.overlays.removeAll(toRemove)

                for (place in snapshot.children) {
                    val name = place.child("name").getValue(String::class.java) ?: "Place"
                    val lat = place.child("latitude").getValue(Double::class.java)
                    val lon = place.child("longitude").getValue(Double::class.java)
                    val radius = place.child("radius").getValue(Double::class.java) ?: 200.0
                    
                    if (lat != null && lon != null) {
                        val center = GeoPoint(lat, lon)
                        
                        // 1. Draw Circle (The Geofence)
                        val circle = org.osmdroid.views.overlay.Polygon()
                        circle.points = org.osmdroid.views.overlay.Polygon.pointsAsCircle(center, radius)
                        circle.fillPaint.color = android.graphics.Color.parseColor("#3300E5FF") // 20% Neon Cyan
                        circle.fillPaint.style = android.graphics.Paint.Style.FILL
                        circle.outlinePaint.color = android.graphics.Color.parseColor("#00E5FF") // Solid Neon Cyan
                        circle.outlinePaint.strokeWidth = 2f
                        map.overlays.add(0, circle)
                        
                        // 2. Draw Icon (The Label)
                        val marker = Marker(map)
                        marker.position = center
                        marker.id = "PLACE_${place.key}" // Tag it so we can remove it later
                        marker.title = name
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        
                        val iconBitmap = getPlaceIcon(name)
                        marker.icon = BitmapDrawable(resources, iconBitmap)
                        
                        // Optional: Small label window or just title
                        marker.infoWindow = null // No popup for places, just visual
                        
                        map.overlays.add(marker)
                    }
                }
                map.invalidate()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        placesRef!!.addValueEventListener(placesListener!!)
    }
    
    private fun getPlaceIcon(name: String): Bitmap {
        val size = 80
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = android.graphics.Paint()
        
        // 1. Neon Cyan Background Circle
        paint.color = android.graphics.Color.parseColor("#00E5FF")
        paint.style = android.graphics.Paint.Style.FILL
        paint.isAntiAlias = true
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        
        // 2. Determine Emoji
        val lowerName = name.toLowerCase(java.util.Locale.ROOT)
        val emoji = when {
            lowerName.contains("home") -> "🏠"
            lowerName.contains("work") -> "💼"
            lowerName.contains("school") -> "🎓"
            lowerName.contains("gym") -> "🏋️"
            else -> "📍"
        }
        
        // 3. Draw Emoji in White
        paint.color = android.graphics.Color.WHITE
        paint.textSize = size * 0.5f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        // Vertically center text
        val textHeight = paint.descent() - paint.ascent()
        val textOffset = (textHeight / 2) - paint.descent()
        
        canvas.drawText(emoji, size / 2f, size / 2f + textOffset, paint)
        
        return bitmap
    }

    private fun drawBadgeOnBitmap(bitmap: Bitmap, emoji: String): Bitmap {
        if (emoji.isEmpty()) return bitmap
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val paint = android.graphics.Paint()
        paint.color = android.graphics.Color.WHITE
        paint.style = android.graphics.Paint.Style.FILL
        paint.isAntiAlias = true
        val badgeRadius = bitmap.width * 0.15f
        val cx = bitmap.width - badgeRadius - 5
        val cy = bitmap.height - badgeRadius - 5
        canvas.drawCircle(cx, cy, badgeRadius, paint)
        paint.color = android.graphics.Color.BLACK
        paint.textSize = badgeRadius * 1.5f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        val textHeight = paint.descent() - paint.ascent()
        val textOffset = (textHeight / 2) - paint.descent()
        canvas.drawText(emoji, cx, cy + textOffset, paint)
        return mutableBitmap
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = android.graphics.Paint()
        val rect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(bitmap.width / 2f, bitmap.height / 2f, bitmap.width / 2f, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        return output
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
