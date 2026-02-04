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
    private var selectedCalendar = Calendar.getInstance()

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
        loadHistory(selectedCalendar)
        listenToDrivingEvents()
    }

    private fun listenToDrivingEvents() {
        val ref = FirebaseDatabase.getInstance(DB_URL).getReference("driving_events").child(userId!!)
        ref.orderByKey().limitToLast(5).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val container = findViewById<android.widget.LinearLayout>(R.id.containerAlerts)
                container.removeAllViews()
                
                // Add Header (Re-add because removeAllViews clears it too, or we can just add views below header if we structured differently. 
                // Actually layout has header OUTSIDE container? No, inside.
                // Wait, "containerAlerts" in XML has "No Alerts" text inside.
                // My XML:
                // <LinearLayout id="@+id/containerAlerts"> 
                //    <TextView "Recent Safety Events" ... /> 
                //    <TextView "No recent..." />
                // </LinearLayout>
                // So removeAllViews WILL remove the header.
                // I should probably have put the header outside or re-add it. 
                // Re-adding header programmatically is annoying. 
                // I'll assume header is outside or I'll re-add it. 
                // Let me check XML again.
                // XML: CardView -> LinearLayout(containerAlerts) -> [Header, NoAlerts].
                // Okay, I will re-add the "Recent Safety Events" header first.
                
                val header = TextView(this@MemberDetailActivity)
                header.text = "⚠️ Recent Safety Events"
                header.setTextColor(android.graphics.Color.parseColor("#FF5252"))
                header.textSize = 14f
                header.typeface = android.graphics.Typeface.DEFAULT_BOLD
                header.setPadding(0,0,0,16)
                container.addView(header)

                if (!snapshot.exists()) {
                     val tv = TextView(this@MemberDetailActivity)
                     tv.text = "No recent speeding or harsh braking detected."
                     tv.setTextColor(android.graphics.Color.GRAY)
                     container.addView(tv)
                     return
                }
                
                // Get children and reverse (Oldest is first in Firebase)
                val events = snapshot.children.toList().reversed()
                
                for (event in events) {
                    val type = event.child("type").getValue(String::class.java) ?: "Event"
                    val value = event.child("value").getValue(String::class.java) ?: ""
                    val timestamp = event.child("timestamp").getValue(Long::class.java) ?: 0L
                    
                    val timeDiff = (System.currentTimeMillis() - timestamp) / 60000 // Minutes
                    val timeText = when {
                        timeDiff < 1 -> "Just now"
                        timeDiff < 60 -> "${timeDiff}m ago"
                        else -> "${timeDiff/60}h ago"
                    }
                    
                    val tv = TextView(this@MemberDetailActivity)
                    val icon = if (type == "SPEEDING") "⚠️" else "🛑"
                    val niceType = if (type == "SPEEDING") "Speeding" else "Harsh Braking"
                    
                    tv.text = "$icon $niceType ($value) • $timeText"
                    tv.setTextColor(android.graphics.Color.WHITE)
                    tv.textSize = 14f
                    tv.setPadding(0, 8, 0, 8)
                    container.addView(tv)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }
    
    private fun setupUI() {
        // Back Button
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        
        // Date Picker
        findViewById<TextView>(R.id.btnDate).setOnClickListener {
            showDatePicker()
        }
    }

    private fun setupMap() {
        map = findViewById(R.id.mapHistory)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.isTilesScaledToDpi = true // Makes text larger and easier to read
        map.setMultiTouchControls(true)
        map.controller.setZoom(18.0) // Zoom in closer for "places" feel
    }

    private var liveMarker: Marker? = null

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
                val lat = snapshot.child("latitude").getValue(Double::class.java)
                val lon = snapshot.child("longitude").getValue(Double::class.java)
                
                findViewById<TextView>(R.id.tvDetailName).text = email.substringBefore("@").replaceFirstChar { it.uppercase() }
                findViewById<TextView>(R.id.tvDetailEmail).text = email
                findViewById<TextView>(R.id.tvDetailBattery).text = if (battery >= 0) "$battery%" else "..."
                findViewById<TextView>(R.id.tvDetailSpeed).text = String.format("%.0f km/h", speed * 3.6)
                
                val address = snapshot.child("address").getValue(String::class.java)
                
                // Status Text
                val timeDiff = (System.currentTimeMillis() - lastUp) / 60000
                val statusTime = if (timeDiff < 5) "Online Now" else "Last seen ${timeDiff}m ago"
                
                val locationText = when {
                    !currentPlace.isNullOrEmpty() -> "At $currentPlace"
                    speed > 200/3.6 -> "Flying ✈️"
                    speed > 35/3.6 -> "Driving 🚗"
                    !address.isNullOrEmpty() -> address
                    speed > 10/3.6 -> "Cycling 🚴"
                    speed > 2 -> "Walking 🚶"
                    else -> "Stationary"
                }
                
                findViewById<TextView>(R.id.tvDetailStatus).text = "$statusTime • $locationText"

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
                        ivAvatar.clearColorFilter()
                    } catch (e: Exception) {
                        ivAvatar.setImageResource(R.drawable.ic_avatar_placeholder)
                    }
                } else {
                    ivAvatar.setImageResource(R.drawable.ic_avatar_placeholder)
                    ivAvatar.setColorFilter(android.graphics.Color.LTGRAY)
                }
                
                // LIVE MAP UPDATE (Life360 Style)
                if (lat != null && lon != null) {
                    val geoPoint = GeoPoint(lat, lon)
                    
                    if (liveMarker == null) {
                         liveMarker = Marker(map)
                         liveMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                         liveMarker?.icon = androidx.core.content.ContextCompat.getDrawable(this@MemberDetailActivity, R.drawable.ic_avatar_placeholder) // Fallback
                         // Start center
                         map.controller.setCenter(geoPoint)
                         map.overlays.add(liveMarker)
                    }
                    
                    liveMarker?.position = geoPoint
                    liveMarker?.title = locationText
                    
                    // Smoothly animate to new position if valid
                     map.controller.animateTo(geoPoint)
                    
                    map.invalidate()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun showDatePicker() {
        val datePickerDialog = android.app.DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                selectedCalendar.set(Calendar.YEAR, year)
                selectedCalendar.set(Calendar.MONTH, month)
                selectedCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                loadHistory(selectedCalendar)
            },
            selectedCalendar.get(Calendar.YEAR),
            selectedCalendar.get(Calendar.MONTH),
            selectedCalendar.get(Calendar.DAY_OF_MONTH)
        )
        datePickerDialog.datePicker.maxDate = System.currentTimeMillis() // Can't pick future
        datePickerDialog.show()
    }

    // Helper to reference the listener so we can remove it when switching dates
    private var historyListener: ValueEventListener? = null
    private var historyRef: com.google.firebase.database.DatabaseReference? = null

    private fun loadHistory(calendar: Calendar) {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val dateParams = sdf.format(calendar.time)
        
        // Update Title
        val prettyFormat = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
        val dateTitle = if (android.text.format.DateUtils.isToday(calendar.timeInMillis)) "Today's Journey" else prettyFormat.format(calendar.time)
        findViewById<TextView>(R.id.lblHistory).text = dateTitle

        // Clean up previous listener
        if (historyRef != null && historyListener != null) {
            historyRef!!.removeEventListener(historyListener!!)
        }
        
        historyRef = FirebaseDatabase.getInstance(DB_URL).getReference("history").child(userId!!).child(dateParams)
        
        // Create new listener
        historyListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val points = ArrayList<GeoPoint>()
                for (child in snapshot.children) {
                    val lat = child.child("lat").getValue(Double::class.java)
                    val lon = child.child("lon").getValue(Double::class.java)
                    if (lat != null && lon != null) {
                        points.add(GeoPoint(lat, lon))
                    }
                }
                
                map.overlays.clear() // Always clear even if empty
                
                if (points.isNotEmpty()) {
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
                   startMarker.title = "Start: " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(snapshot.children.first().child("time").getValue(Long::class.java) ?: 0))
                   startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                   map.overlays.add(startMarker)
                   
                   val endMarker = Marker(map)
                   endMarker.position = points.last()
                   endMarker.title = "End: " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(snapshot.children.last().child("time").getValue(Long::class.java) ?: 0))
                   endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                   map.overlays.add(endMarker)
                } 
                map.invalidate()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        
        historyRef!!.addValueEventListener(historyListener!!)
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
