package com.wiandurandt.familytracker.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.wiandurandt.familytracker.R
import java.util.concurrent.ConcurrentHashMap

class LocationService : Service() {

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val DB_URL = "https://familiy-tracker-default-rtdb.firebaseio.com/"
    
    // Geofencing State
    private var myFamilyId: String? = null
    private val placesMap = ConcurrentHashMap<String, PlaceData>()
    private val lastNotificationTime = ConcurrentHashMap<String, Long>() // Key: "userId_placeId" -> timestamp
    private val DEBOUNCE_TIME_MS = 60 * 1000L // 1 Minute to avoid jitter spam
    
    data class PlaceData(val name: String, val lat: Double, val lon: Double, val radius: Double)

    override fun onCreate() {
        super.onCreate()
        
        // 1. Acquire WakeLock to keep CPU running
        val powerManager = getSystemService(android.os.PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "FamilyTracker::LocationWakeLock")
        wakeLock?.acquire()
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // 2. Keep Firebase Synced
        try {
            FirebaseDatabase.getInstance(DB_URL).getReference("users").keepSynced(true)
            FirebaseDatabase.getInstance(DB_URL).getReference("families").keepSynced(true)
        } catch (e: Exception) {}
        
        createNotificationChannel()
        startForeground(12345, createNotification())
        
        // 3. Instant Background Update Listener
        com.wiandurandt.familytracker.utils.UpdateManager.listenForUpdates(this)
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    updateFirebase(location)
                }
            }
        }
        
        startLocationUpdates()
        setupGeofences()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Tells the OS to restart this service if it gets killed (e.g. low memory or swipe away)
        return START_STICKY
    }

    private fun setupGeofences() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseDatabase.getInstance(DB_URL)
        
        // 1. Get My Family ID
        db.getReference("users").child(uid).child("familyId").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newFamilyId = snapshot.getValue(String::class.java)
                if (newFamilyId != null && newFamilyId != myFamilyId) {
                    myFamilyId = newFamilyId
                    listenToPlaces(newFamilyId)
                    listenToFamilyMembers(uid)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }
    
    private fun listenToPlaces(familyId: String) {
        val db = FirebaseDatabase.getInstance(DB_URL)
        db.getReference("families").child(familyId).child("places").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                placesMap.clear()
                for (child in snapshot.children) {
                    val name = child.child("name").getValue(String::class.java)
                    val lat = child.child("latitude").getValue(Double::class.java)
                    val lon = child.child("longitude").getValue(Double::class.java)
                    val radius = child.child("radius").getValue(Double::class.java) ?: 100.0
                    
                    if (name != null && lat != null && lon != null) {
                        placesMap[child.key!!] = PlaceData(name, lat, lon, radius)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }
    
    private fun listenToFamilyMembers(myUid: String) {
        val db = FirebaseDatabase.getInstance(DB_URL)
        // Note: Listening to ALL users is inefficient for scaling, but fine for a small family app prototype.
        // A better approach in production is to denormalize a "family_members/{familyId}" list.
        db.getReference("users").addChildEventListener(object : ChildEventListener {
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                processUserUpdate(snapshot, myUid)
            }
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                processUserUpdate(snapshot, myUid)
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }
    
    private fun processUserUpdate(snapshot: DataSnapshot, myUid: String) {
        val userId = snapshot.key ?: return
        if (userId == myUid) return // Don't notify about myself
        
        val userFamilyId = snapshot.child("familyId").getValue(String::class.java)
        if (userFamilyId != myFamilyId) return // Not in my family
        
        val lat = snapshot.child("latitude").getValue(Double::class.java)
        val lon = snapshot.child("longitude").getValue(Double::class.java)
        val userEmail = snapshot.child("email").getValue(String::class.java) ?: "Family Member"
        
        // Modern name extract
        val userName = userEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
        
        // 1. SOS / Panic Check
        val panicActive = snapshot.child("panicActive").getValue(Boolean::class.java) ?: false
        if (panicActive) {
            val lastPanicNotify = lastNotificationTime["${userId}_panic"] ?: 0L
            if (System.currentTimeMillis() - lastPanicNotify > 30000) { // Notify every 30s top
                lastNotificationTime["${userId}_panic"] = System.currentTimeMillis()
                sendHighPriorityNotification("🆘 EMERGENCY: $userName needs help!", "SOS Panic Button pressed. Check their location immediately.")
            }
        }
        
        // 2. Low Battery Check
        val batteryLevel = snapshot.child("batteryLevel").getValue(Int::class.java) ?: -1
        if (batteryLevel in 1..15) {
            val lastBatNotify = lastNotificationTime["${userId}_battery"] ?: 0L
            if (System.currentTimeMillis() - lastBatNotify > 120 * 60 * 1000) { // Every 2 hours
                lastNotificationTime["${userId}_battery"] = System.currentTimeMillis()
                sendHighPriorityNotification("🔋 Low Battery: $userName", "$userName's phone is at $batteryLevel%. They might go offline soon.")
            }
        }
        
        if (lat != null && lon != null) {
            checkGeofenceEntry(userId, userName, lat, lon)
        }
    }

    private fun sendHighPriorityNotification(title: String, message: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, "GEOFENCE_CHANNEL")
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 500, 200, 500)) // Heavy vibration
            .setAutoCancel(true)
            .build()
            
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
    
    private val userInsideState = ConcurrentHashMap<String, Boolean>() // Key: "userId_placeId" -> isInside

    private fun checkGeofenceEntry(userId: String, userName: String, lat: Double, lon: Double) {
        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean("notifications_enabled", true)) return

        val result = FloatArray(1)
        
        for ((placeId, place) in placesMap) {
            Location.distanceBetween(lat, lon, place.lat, place.lon, result)
            val distanceInMeters = result[0]
            val key = "${userId}_${placeId}"
            val isInside = distanceInMeters <= place.radius
            
            android.util.Log.d("GeofenceCheck", "User:$userName Place:${place.name} Dist:${distanceInMeters.toInt()}m isInside:$isInside")
            
            val wasInside = userInsideState[key] ?: false
            
            // 1. ENTRY DETECTED (Outside -> Inside)
            if (isInside && !wasInside) {
                // Check if this is the FIRST run for this key (Startup)
                if (!processedInitialState.containsKey(key)) {
                     // Startup: Just sync state, DO NOT NOTIFY
                     android.util.Log.d("GeofenceCheck", "Startup Sync: $userName @ ${place.name} (Silent)")
                     processedInitialState[key] = true
                } else {
                    // Normal Operation: Notify
                    if (shouldNotify(key)) {
                        android.util.Log.d("GeofenceCheck", "ENTRY Triggered for $userName @ ${place.name}")
                        if (userId != FirebaseAuth.getInstance().currentUser?.uid) {
                            sendGeofenceNotification("$userName arrived at ${place.name}")
                        }
                        FirebaseDatabase.getInstance(DB_URL).getReference("users").child(userId).child("currentPlace").setValue(place.name)
                    }
                }
                userInsideState[key] = true
            }
            // 2. EXIT DETECTED (Inside -> Outside)
            else if (!isInside && wasInside) {
                // If they move > 30m outside the radius to confirm exit (increased buffer to prevent jitter)
                if (distanceInMeters > place.radius + 100) {
                     processedInitialState[key] = true // Mark as processed
                     
                     if (shouldNotify(key)) {
                        android.util.Log.d("GeofenceCheck", "EXIT Triggered for $userName left ${place.name}")
                        
                        if (userId != FirebaseAuth.getInstance().currentUser?.uid) {
                             sendGeofenceNotification("$userName left ${place.name}")
                        }
                        
                        FirebaseDatabase.getInstance(DB_URL).getReference("users").child(userId).child("currentPlace").setValue(null)
                    } else {
                        android.util.Log.d("GeofenceCheck", "EXIT Debounced for $userName left ${place.name}")
                    }
                    userInsideState[key] = false
                }
            }
            // 3. Just passing through (mark as processed even if outside)
            else {
                if (!processedInitialState.containsKey(key)) {
                     processedInitialState[key] = true
                }
            }
        }
    }

    
    private fun shouldNotify(key: String): Boolean {
        val lastTime = lastNotificationTime[key] ?: 0L
        val now = System.currentTimeMillis()
        if (now - lastTime > DEBOUNCE_TIME_MS) {
            lastNotificationTime[key] = now
            return true
        }
        return false
    }

    private fun sendGeofenceNotification(message: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, "GEOFENCE_CHANNEL")
            .setContentTitle("Family Alert")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL) // Create sound/vibration
            .setAutoCancel(true)
            .build()
            
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun startLocationUpdates() {
        // High accuracy, faster updates for "Live" feel
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(2000)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // Permission lost
        }
    }

    private fun updateFirebase(location: android.location.Location) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = FirebaseDatabase.getInstance(DB_URL).getReference("users").child(uid)
        
        val updates = HashMap<String, Any>()
        updates["latitude"] = location.latitude
        updates["longitude"] = location.longitude
        updates["speed"] = location.speed // m/s
        updates["lastUpdated"] = System.currentTimeMillis()
        updates["email"] = FirebaseAuth.getInstance().currentUser?.email ?: "" // Fix for "Unknown" name
        
        // Add Battery Level
        val batteryPct = getBatteryLevel()
        updates["batteryLevel"] = batteryPct
        

        
        ref.updateChildren(updates)
        
        // CHECK DRIVING EVENTS (Speeding & Braking)
        checkDrivingEvents(uid, location)
        
        // CHECK SELF GEOFENCE
        val myEmail = FirebaseAuth.getInstance().currentUser?.email ?: "Me"
        val myName = myEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
        checkGeofenceEntry(uid, myName, location.latitude, location.longitude)
        
        // ADDRESS UPDATE Logic
        // If we are NOT known to be inside a place, fetch the street address
        // But only do it every ~2 minutes or if moved > 500m to save data/battery
        val isInsideAnyPlace = userInsideState.entries.any { it.key.startsWith(uid) && it.value }
        if (!isInsideAnyPlace) {
             val now = System.currentTimeMillis()
             if (now - lastAddressTime > 10000) { // 10 Seconds for debugging (was 2 min)
                 lastAddressTime = now
                 Thread {
                     try {
                         var address = getAddressFromLocation(location.latitude, location.longitude)
                         
                         // Fallback to Nominatim if Native fails
                         if (address.isNullOrEmpty()) {
                             android.util.Log.d("LocationService", "Native Geocoder failed, trying Nominatim...")
                             address = getNominatimAddress(location.latitude, location.longitude)
                         }
                         
                         if (!address.isNullOrEmpty()) {
                             ref.child("address").setValue(address)
                         } else {
                             android.util.Log.e("LocationService", "All Geocoders returned null")
                         }
                     } catch (e: Exception) {
                         android.util.Log.e("LocationService", "Geocoder logic failed: ${e.message}")
                         e.printStackTrace()
                     }
                 }.start()
             }
        } else {
             // If inside place, we can clear address or keep it. 
             // Let's keep it but maybe it will be overridden by "At Home" in UI.
             // Ideally we clear it to avoid confusion or old data.
             ref.child("address").setValue(null)
        }
        
        // SAVE HISTORY (Every 5 mins)
        saveHistoryToFirebase(uid, location)
    }

    private var lastAddressTime = 0L
    
    private fun getAddressFromLocation(lat: Double, lon: Double): String? {
        if (!android.location.Geocoder.isPresent()) return null
        
        try {
            val geocoder = android.location.Geocoder(this, java.util.Locale.getDefault())
            // Android 13+ has a listener based API, but sticking to sync for simplicity in Service for now
            // or handling deprecation if needed. For simple use case:
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                // Return "Street, Suburb" or similar
                // getMaxAddressLineIndex is 0-based
                return if (addr.maxAddressLineIndex >= 0) {
                     addr.getAddressLine(0)
                } else {
                    "${addr.thoroughfare ?: ""}, ${addr.locality ?: ""}".trim(',',' ')
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    private fun getNominatimAddress(lat: Double, lon: Double): String? {
        try {
            val urlStr = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=18&addressdetails=1"
            val url = java.net.URL(urlStr)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "FamilyTracker/1.0") // Required by OSM
            
            if (connection.responseCode == 200) {
                val stream = connection.inputStream
                val reader = java.io.BufferedReader(java.io.InputStreamReader(stream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                
                val json = org.json.JSONObject(response.toString())
                return json.optString("display_name", "")
            }
        } catch (e: Exception) {
            android.util.Log.e("LocationService", "Nominatim Warning: ${e.message}")
        }
        return null
    }
    
    // Fix: Prevent Notification Spam on Startup
    private val processedInitialState = ConcurrentHashMap<String, Boolean>() 
    
    private fun getBatteryLevel(): Int {
        val bm = getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private var lastHistorySaveTime = 0L
    private val HISTORY_INTERVAL_MS = 5 * 60 * 1000L // 5 Minutes

    private fun saveHistoryToFirebase(uid: String, location: android.location.Location) {
        val now = System.currentTimeMillis()
        if (now - lastHistorySaveTime > HISTORY_INTERVAL_MS) {
            lastHistorySaveTime = now
            
            // Format Date: yyyyMMdd (Compact)
            val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
            val dateKey = sdf.format(java.util.Date(now))
            
            val historyRef = FirebaseDatabase.getInstance(DB_URL).getReference("history")
                .child(uid).child(dateKey).push() // Auto-ID for timestamp sorting
                
            val point = HashMap<String, Any>()
            point["lat"] = location.latitude
            point["lon"] = location.longitude
            point["time"] = now
            point["speed"] = location.speed
            
            historyRef.setValue(point)
        }
    }
    
    // --- DRIVING SAFETY LOGIC ---
    private var lastSpeed = 0f
    private var lastSpeedTime = 0L
    private val SPEEDING_THRESHOLD_MS = 33.3f // ~120 km/h
    private val BRAKING_THRESHOLD_MSS = 4.5f // 4.5 m/s^2 (Standard harsh braking)
    private val EVENT_DEBOUNCE_MS = 60 * 1000L // 1 Minute per event type
    
    private fun checkDrivingEvents(uid: String, location: Location) {
        val currentSpeed = location.speed // m/s
        val currentTime = location.time
        
        // 1. SPEEDING CHECK
        if (currentSpeed > SPEEDING_THRESHOLD_MS) {
            val lastSpeeding = lastNotificationTime["${uid}_speeding"] ?: 0L
            if (System.currentTimeMillis() - lastSpeeding > EVENT_DEBOUNCE_MS * 5) { // 5 Min debounce for speeding
                 lastNotificationTime["${uid}_speeding"] = System.currentTimeMillis()
                 saveDrivingEvent(uid, "SPEEDING", "${(currentSpeed * 3.6).toInt()} km/h")
            }
        }
        
        // 2. HARSH BRAKING CHECK
        // Requirements: Must have been moving fast before (>30km/h) to count as "Harsh"
        if (lastSpeedTime > 0 && lastSpeed > 8.3f) { // >30km/h
            val timeDiff = (currentTime - lastSpeedTime) / 1000f // Seconds
            if (timeDiff > 0 && timeDiff < 10) { // Valid short interval
                val speedDiff = lastSpeed - currentSpeed // Positive if slowing down
                val deceleration = speedDiff / timeDiff
                
                if (deceleration > BRAKING_THRESHOLD_MSS) {
                    val lastBraking = lastNotificationTime["${uid}_braking"] ?: 0L
                    if (System.currentTimeMillis() - lastBraking > EVENT_DEBOUNCE_MS) {
                        lastNotificationTime["${uid}_braking"] = System.currentTimeMillis()
                        saveDrivingEvent(uid, "HARSH_BRAKING", "Decel: ${String.format("%.1f", deceleration)} m/s²")
                    }
                }
            }
        }
        
        lastSpeed = currentSpeed
        lastSpeedTime = currentTime
    }
    
    private fun saveDrivingEvent(uid: String, type: String, value: String) {
        val ref = FirebaseDatabase.getInstance(DB_URL).getReference("driving_events").child(uid).push()
        val event = mapOf(
            "type" to type,
            "value" to value,
            "timestamp" to System.currentTimeMillis()
        )
        ref.setValue(event)
        
        // Optional: Send local notification if it's ME driving (Feedback)
        // sendHighPriorityNotification("Driving Alert", "$type detected! ($value)")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Channel 1: Foreground Service
            val serviceChannel = NotificationChannel(
                "LOCATION_CHANNEL_SILENT",
                "Location Tracking",
                NotificationManager.IMPORTANCE_MIN
            )
            // Channel 2: Geofence Alerts
            val alertChannel = NotificationChannel(
                "GEOFENCE_CHANNEL",
                "Family Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "LOCATION_CHANNEL_SILENT")
            .setContentTitle("Family Tracker")
            .setContentText("Running in background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }


    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
        
        // SELF-RESURRECTION: Try to restart immediately if killed
        val restartIntent = Intent("com.wiandurandt.familytracker.RESTART_SERVICE")
        restartIntent.setClassName(this, "com.wiandurandt.familytracker.receivers.BootReceiver")
        sendBroadcast(restartIntent)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
