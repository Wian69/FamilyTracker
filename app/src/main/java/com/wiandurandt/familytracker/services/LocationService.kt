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
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(3000)
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
        
        // CHECK SELF GEOFENCE
        val myEmail = FirebaseAuth.getInstance().currentUser?.email ?: "Me"
        val myName = myEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
        checkGeofenceEntry(uid, myName, location.latitude, location.longitude)
        
        // SAVE HISTORY (Every 5 mins)
        saveHistoryToFirebase(uid, location)
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
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
