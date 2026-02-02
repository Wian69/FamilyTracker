package com.wiandurandt.familytracker

import android.graphics.BitmapFactory
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Base64
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.*

class MemberDetailActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private var userId: String? = null
    private val DB_URL = "https://familiy-tracker-default-rtdb.firebaseio.com/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContentView(R.layout.activity_member_detail)

        userId = intent.getStringExtra("UID")
        if (userId == null) {
            finish()
            return
        }

        setupUI()
        setupMap()
        listenToUserUpdates()
        loadHistory()
    }

    private fun setupUI() {
        // Back Button
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun setupMap() {
        map = findViewById(R.id.mapHistory)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0) // Reverted zoom for detail
    }

    private fun listenToUserUpdates() {
        val ref = FirebaseDatabase.getInstance(DB_URL).getReference("users").child(userId!!)
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val email = snapshot.child("email").getValue(String::class.java) ?: "Unknown"
                val profileBase64 = snapshot.child("profileBase64").getValue(String::class.java)
                val battery = snapshot.child("batteryLevel").getValue(Int::class.java) ?: -1
                val speed = snapshot.child("speed").getValue(Float::class.java) ?: 0f
                val lastUp = snapshot.child("lastUpdated").getValue(Long::class.java) ?: 0L
                val currentPlace = snapshot.child("currentPlace").getValue(String::class.java) ?: "Unknown"
                
                findViewById<TextView>(R.id.tvDetailName).text = email.substringBefore("@").replaceFirstChar { it.uppercase() }
                findViewById<TextView>(R.id.tvDetailEmail).text = email
                findViewById<TextView>(R.id.tvDetailBattery).text = if (battery >= 0) "$battery%" else "..."
                findViewById<TextView>(R.id.tvDetailSpeed).text = String.format("%.0f km/h", speed * 3.6)
                
                // Status Text
                val timeDiff = (System.currentTimeMillis() - lastUp) / 60000
                val status = if (timeDiff < 5) "Online Now" else "Last seen ${timeDiff}m ago"
                findViewById<TextView>(R.id.tvDetailStatus).text = "$status • $currentPlace"

                // Profile Pic
                val ivAvatar = findViewById<ImageView>(R.id.ivDetailAvatar)
                if (!profileBase64.isNullOrEmpty()) {
                    try {
                        val imageBytes = Base64.decode(profileBase64, Base64.DEFAULT)
                        val decodedImage = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        Glide.with(this@MemberDetailActivity)
                            .load(decodedImage)
                            .circleCrop()
                            .placeholder(R.drawable.ic_avatar_placeholder)
                            .error(R.drawable.ic_avatar_placeholder)
                            .into(ivAvatar)
                    } catch (e: Exception) {
                        ivAvatar.setImageResource(R.drawable.ic_avatar_placeholder)
                    }
                } else {
                    ivAvatar.setImageResource(R.drawable.ic_avatar_placeholder)
                    ivAvatar.setColorFilter(android.graphics.Color.LTGRAY)
                }
                
                // Update Map Position (Current)
                val lat = snapshot.child("latitude").getValue(Double::class.java)
                val lon = snapshot.child("longitude").getValue(Double::class.java)
                if (lat != null && lon != null) {
                    // We'll let history handle most markers, but ensure we center if it's the first load
                    if (map.overlays.isEmpty()) {
                        map.controller.setCenter(GeoPoint(lat, lon))
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun loadHistory() {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val dateParams = sdf.format(Date())
        
        val historyRef = FirebaseDatabase.getInstance(DB_URL).getReference("history").child(userId!!).child(dateParams)
        
        // Change to real-time updates so path updates while viewing
        historyRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val points = ArrayList<GeoPoint>()
                for (child in snapshot.children) {
                    val lat = child.child("lat").getValue(Double::class.java)
                    val lon = child.child("lon").getValue(Double::class.java)
                    if (lat != null && lon != null) {
                        points.add(GeoPoint(lat, lon))
                    }
                }
                
                if (points.isNotEmpty()) {
                    // Clear previous path/markers before redraw
                    map.overlays.clear()

                    // Draw Path
                    val line = Polyline()
                    line.setPoints(points)
                    line.outlinePaint.color = android.graphics.Color.parseColor("#00E5FF") // Cyan Path
                    line.outlinePaint.strokeWidth = 10f
                    map.overlays.add(line)
                    
                   map.controller.setCenter(points.last())
                   
                   // Add Start/End Markers
                   val startMarker = Marker(map)
                   startMarker.position = points.first()
                   startMarker.title = "Start of Day"
                   startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                   map.overlays.add(startMarker)
                   
                   val endMarker = Marker(map)
                   endMarker.position = points.last()
                   endMarker.title = "Current Position"
                   endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                   map.overlays.add(endMarker)
                   
                   map.invalidate()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
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
