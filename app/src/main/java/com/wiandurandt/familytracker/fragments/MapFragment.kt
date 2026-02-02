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
        fetchUserFamilyId()

        view.findViewById<View>(R.id.btnCenterLocation).setOnClickListener {
            val location = locationOverlay.myLocation
            if (location != null) {
                locationOverlay.enableFollowLocation()
                map.controller.animateTo(location)
            } else {
                Toast.makeText(requireContext(), "Waiting for location...", Toast.LENGTH_SHORT).show()
            }
        }
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
        map.controller.setZoom(18.0)

        // Use Custom Overlay
        locationOverlay = CustomMyLocationOverlay(GpsMyLocationProvider(requireContext()), map)
        locationOverlay.enableMyLocation()
        locationOverlay.enableFollowLocation() // Auto-center
        map.overlays.add(locationOverlay)
    }

    // Custom Overlay to prevent rotation and update badges
    inner class CustomMyLocationOverlay(provider: GpsMyLocationProvider, mapView: MapView) 
        : MyLocationNewOverlay(provider, mapView) {
        
        private var myIcon: Bitmap? = null

        override fun onLocationChanged(location: android.location.Location?, source: org.osmdroid.views.overlay.mylocation.IMyLocationProvider?) {
            super.onLocationChanged(location, source)
            
            // Update Icon with Badge if we have the profile pic
            if (location != null && myRawProfileBitmap != null) {
                val speedKmh = (location.speed * 3.6).toInt()
                val emoji = getActivityEmoji(speedKmh)
                
                try {
                     val badged = drawBadgeOnBitmap(myRawProfileBitmap!!, emoji)
                     myIcon = badged // Cache for drawing
                     setPersonIcon(badged)
                     setDirectionIcon(badged) 
                     map.postInvalidate()
                } catch (e: Exception) {}
            }
        }
        
        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            
            val fix = this.lastFix ?: return
            val icon = myIcon
            
            if (icon != null) {
                val mapPoint = GeoPoint(fix.latitude, fix.longitude)
                val screenPoint = android.graphics.Point()
                mapView.projection.toPixels(mapPoint, screenPoint)
                
                val paint = android.graphics.Paint()
                paint.isAntiAlias = true
                paint.isFilterBitmap = true
                
                // Center the icon
                val x = screenPoint.x - (icon.width / 2f)
                val y = screenPoint.y - (icon.height / 2f)
                
                canvas.drawBitmap(icon, x, y, paint)
            } else {
                // Fallback to default behavior if no custom icon yet
                super.draw(canvas, mapView, shadow)
            }
        }
    }
    
    private fun getActivityEmoji(speedKmh: Int): String {
        return when {
            speedKmh > 100 -> "✈️"
            speedKmh > 25 -> "🚗"
            speedKmh > 7 -> "🚴"
            speedKmh > 2 -> "🚶"
            else -> ""
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
            val profileBase64 = snapshot.child("profileBase64").getValue(String::class.java)
            val speed = snapshot.child("speed").getValue(Float::class.java) ?: 0f
            val currentPlace = snapshot.child("currentPlace").getValue(String::class.java)
            
            val speedKmh = (speed * 3.6).toInt()
            val activityEmoji = getActivityEmoji(speedKmh)
            
            val activityText = if (!currentPlace.isNullOrEmpty()) {
                "At $currentPlace" 
            } else {
                if(speedKmh <= 2) "Stationary" else "Moving" 
            }

            val marker: Marker
            if (markersMap.containsKey(uid)) {
                marker = markersMap[uid]!!
                marker.position = point
            } else {
                marker = Marker(map)
                marker.position = point
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.title = snapshot.child("email").getValue(String::class.java) ?: "Family Member"
                marker.icon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_launcher_foreground) // Default
                map.overlays.add(marker)
                markersMap[uid] = marker
            }
            
            marker.setSnippet("$activityText: $speedKmh km/h")

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
                    val lastEmoji = marker.relatedObject as? String
                    if (lastEmoji != activityEmoji) {
                        val badgedBitmap = drawBadgeOnBitmap(cachedBitmap, activityEmoji)
                        marker.icon = BitmapDrawable(resources, badgedBitmap)
                        marker.relatedObject = activityEmoji
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
                val toRemove = ArrayList<org.osmdroid.views.overlay.Overlay>()
                map.overlays.forEach { 
                    if (it is org.osmdroid.views.overlay.Polygon) toRemove.add(it)
                }
                map.overlays.removeAll(toRemove)

                for (place in snapshot.children) {
                    val lat = place.child("latitude").getValue(Double::class.java)
                    val lon = place.child("longitude").getValue(Double::class.java)
                    val radius = place.child("radius").getValue(Double::class.java) ?: 200.0
                    
                    if (lat != null && lon != null) {
                        val circle = org.osmdroid.views.overlay.Polygon()
                        circle.points = org.osmdroid.views.overlay.Polygon.pointsAsCircle(GeoPoint(lat, lon), radius)
                        
                        circle.fillPaint.color = android.graphics.Color.parseColor("#4D00E5FF") // 30% Neon Cyan
                        circle.fillPaint.style = android.graphics.Paint.Style.FILL
                        circle.outlinePaint.color = android.graphics.Color.parseColor("#00E5FF") // Full Neon Cyan
                        circle.outlinePaint.strokeWidth = 2f
                        
                        map.overlays.add(0, circle)
                    }
                }
                map.invalidate()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        placesRef!!.addValueEventListener(placesListener!!)
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
