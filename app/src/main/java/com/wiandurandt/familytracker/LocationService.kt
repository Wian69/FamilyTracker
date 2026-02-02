package com.wiandurandt.familytracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()
        startForeground(1, createNotification())

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    updateFirebase(location)
                }
            }
        }
        requestLocationUpdates()
        startMonitoringFamily()
    }

    private fun startMonitoringFamily() {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseDatabase.getInstance().getReference("users")
        
        // Cache previous places to detect CHANGES (Entry/Exit)
        val previousPlaces = HashMap<String, String>()

        db.get().addOnSuccessListener { snapshot ->
             // Initial load
             snapshot.children.forEach { 
                 if (it.key != myUid) {
                     previousPlaces[it.key!!] = it.child("currentPlace").getValue(String::class.java) ?: ""
                 }
             }
        }
        
        db.addChildEventListener(object : com.google.firebase.database.ChildEventListener {
            override fun onChildChanged(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {
                val uid = snapshot.key ?: return
                if (uid == myUid) return // Don't notify about self
                
                // Check if this user is in my family (Optimally we cache myFamilyId, but checking snapshot is fast enough for <100 users)
                // For MVP: We assume if we are seeing updates, we care. 
                // Better: Check local cache of myFamilyId or fetch it.
                // We'll skip complex filtering for now and assume the "users" node is small or correct.
                // Wait, "users" node contains ALL users. Filtering is needed.
                
                checkIfInMyFamily(uid) { inFamily ->
                    if (inFamily) {
                        val newPlace = snapshot.child("currentPlace").getValue(String::class.java) ?: ""
                        val oldPlace = previousPlaces[uid] ?: ""
                        val email = snapshot.child("email").getValue(String::class.java) ?: "Family Member"
                        val name = email.substringBefore("@")
                        
                        if (newPlace.isNotEmpty() && newPlace != oldPlace) {
                            sendNotification("$name arrived at $newPlace")
                        } else if (oldPlace.isNotEmpty() && newPlace.isEmpty()) {
                            sendNotification("$name left $oldPlace")
                        }
                        
                        previousPlaces[uid] = newPlace
                    }
                }
            }
            override fun onChildAdded(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: com.google.firebase.database.DataSnapshot) {}
            override fun onChildMoved(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        })
    }

    private fun checkIfInMyFamily(targetUid: String, callback: (Boolean) -> Unit) {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseDatabase.getInstance().reference
        
        // This is inefficient (fetches 2 nodes per update).
        // Optimization: Cache myFamilyId in a member variable in LocationService.
        // Assuming we do that in a later refactor.
        db.child("users").child(myUid).child("familyId").get().addOnSuccessListener { mySnapshot ->
             val myFamilyId = mySnapshot.getValue(String::class.java)
             db.child("users").child(targetUid).child("familyId").get().addOnSuccessListener { targetSnapshot ->
                 val targetFamilyId = targetSnapshot.getValue(String::class.java)
                 callback(myFamilyId != null && myFamilyId == targetFamilyId)
             }
        }
    }
    
    private fun sendNotification(message: String) {
        val notification = NotificationCompat.Builder(this, "location_channel")
            .setContentTitle("Family Alert")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_map) // Use a better icon if avail
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun requestLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(5000)
            .setMaxUpdateDelayMillis(15000)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            // Permission should be checked before starting service
        }
    }

    private fun updateFirebase(location: android.location.Location) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = FirebaseDatabase.getInstance().getReference("users").child(uid)
        
        val updates = HashMap<String, Any>()
        updates["latitude"] = location.latitude
        updates["longitude"] = location.longitude
        updates["timestamp"] = System.currentTimeMillis()
        updates["speed"] = location.speed
        updates["accuracy"] = location.accuracy
        
        // Geofence Check
        checkGeofences(location) { placeName ->
            if (placeName != null) {
                updates["currentPlace"] = placeName
            } else {
                updates["currentPlace"] = "" // Clear if outside
            }
             ref.updateChildren(updates)
        }
    }
    
    private fun checkGeofences(location: android.location.Location, callback: (String?) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseDatabase.getInstance().reference
        
        // optimizations: cache familyId and places list instead of fetching every time
        // For MVP, we fetch familyId then places. Ideally cache this.
        
        db.child("users").child(uid).child("familyId").get().addOnSuccessListener { snapshot ->
            val familyId = snapshot.getValue(String::class.java)
            if (familyId != null) {
                db.child("families").child(familyId).child("places").get().addOnSuccessListener { placesSnapshot ->
                    var currentPlace: String? = null
                    
                    for (placeSnapshot in placesSnapshot.children) {
                        val lat = placeSnapshot.child("latitude").getValue(Double::class.java) ?: 0.0
                        val lon = placeSnapshot.child("longitude").getValue(Double::class.java) ?: 0.0
                        val radius = placeSnapshot.child("radius").getValue(Double::class.java) ?: 200.0 // Default 200m
                        val name = placeSnapshot.child("name").getValue(String::class.java) ?: "Unknown"
                        
                        val placeLoc = android.location.Location("place")
                        placeLoc.latitude = lat
                        placeLoc.longitude = lon
                        
                        val distance = location.distanceTo(placeLoc)
                        if (distance <= radius) {
                            currentPlace = name
                            break // Inside one place
                        }
                    }
                    callback(currentPlace)
                }
            } else {
                callback(null)
            }
        }.addOnFailureListener { callback(null) }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "location_channel",
            "Location Tracking",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "location_channel")
            .setContentTitle("Family Tracker Active")
            .setContentText("Sharing location with family...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
