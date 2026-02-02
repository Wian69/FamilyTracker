package com.wiandurandt.familytracker.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.wiandurandt.familytracker.R

class PlacesFragment : Fragment() {

    private var currentFamilyId: String? = null
    private var familyListener: ValueEventListener? = null
    private var placesListener: ValueEventListener? = null
    private var familyRef: DatabaseReference? = null
    private var placesRef: DatabaseReference? = null

    private lateinit var container: LinearLayout
    private var manualOverride = false
    
    private val DB_URL = "https://familiy-tracker-default-rtdb.firebaseio.com/"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_places, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        container = view.findViewById(R.id.containerPlaces)
        val btnAdd = view.findViewById<Button>(R.id.btnAddPlaceCurrent)
        
        setupFamilyListener()
        
        btnAdd.setOnClickListener {
            showAddPlaceDialog()
        }
        
        // Monitor Connection State
        FirebaseDatabase.getInstance(DB_URL).getReference(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    Toast.makeText(context, "🔥 Firebase CONNECTED", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "❌ Firebase DISCONNECTED", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        familyRef?.removeEventListener(familyListener!!)
        placesRef?.removeEventListener(placesListener!!)
    }

    private fun setupFamilyListener() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseDatabase.getInstance(DB_URL).reference
        
        familyRef = db.child("users").child(uid).child("familyId")
        familyListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newFamilyId = snapshot.getValue(String::class.java)
                
                // If we are in manual override mode (e.g. forced Durandt), ignore nulls from DB
                if (manualOverride && newFamilyId == null) return
                
                if (newFamilyId != currentFamilyId) {
                    // Only update if we aren't overriding, OR if the DB actually has a value (confirming our write)
                    if (!manualOverride || newFamilyId != null) {
                         currentFamilyId = newFamilyId
                         attachPlacesListener(currentFamilyId)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        familyRef!!.addValueEventListener(familyListener!!)
    }

    private fun attachPlacesListener(familyId: String?) {
        val db = FirebaseDatabase.getInstance(DB_URL).reference
        
        // Remove old listener
        if (placesListener != null && placesRef != null) {
            placesRef!!.removeEventListener(placesListener!!)
        }
        
        if (familyId != null) {
            placesRef = db.child("families").child(familyId).child("places")
            placesListener = object : ValueEventListener {
                override fun onDataChange(placesSnapshot: DataSnapshot) {
                    if (context == null) return
                    container.removeAllViews()
                    
                    val count = placesSnapshot.childrenCount
                    
                    if (!placesSnapshot.exists()) {
                        val tv = TextView(context)
                        tv.text = "No safe places yet for Family '$familyId'."
                        tv.setPadding(16, 16, 16, 16)
                        container.addView(tv)
                        return
                    }
                    
                    for (place in placesSnapshot.children) {
                        val name = place.child("name").getValue(String::class.java) ?: "Unknown"
                        val radius = place.child("radius").getValue(Double::class.java) ?: 200.0
                        
                        val itemView = LayoutInflater.from(context).inflate(R.layout.item_place, container, false)
                        
                        val tvName = itemView.findViewById<TextView>(R.id.tvPlaceName)
                        tvName.text = name
                        
                        val tvDetails = itemView.findViewById<TextView>(R.id.tvPlaceRadius)
                        tvDetails.text = "Radius: ${radius.toInt()}m"
                        
                        // Click to Edit
                        itemView.setOnClickListener {
                            showEditDialog(place.key, name, radius)
                        }
                        
                        // Long press to Delete
                        itemView.setOnLongClickListener {
                            android.app.AlertDialog.Builder(requireContext())
                                .setTitle("Delete $name?")
                                .setPositiveButton("Delete") { _, _ ->
                                    place.ref.removeValue()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                            true
                        }

                        // Add margin - CardView has internal margin, but valid params on container are needed
                        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        params.setMargins(0, 0, 0, 16)
                        itemView.layoutParams = params
                        
                        container.addView(itemView)
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(context, "Error loading places: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            }
            placesRef!!.addValueEventListener(placesListener!!)
        } else {
            container.removeAllViews()
            val tv = TextView(context)
            tv.text = "Please join a family to see safe places."
            container.addView(tv)
        }
    }


    private fun showAddPlaceDialog() {
        if (androidx.core.app.ActivityCompat.checkSelfPermission(requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), "Location permission needed.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "Getting GPS location...", Toast.LENGTH_SHORT).show()
        
        val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(requireActivity())
        
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                showDialog(null, location.latitude, location.longitude, "", 200)
            } else {
                fusedLocationClient.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            showDialog(null, loc.latitude, loc.longitude, "", 200)
                        } else {
                            Toast.makeText(requireContext(), "Could not determine location.", Toast.LENGTH_LONG).show()
                        }
                    }
            }
        }
    }
    
    // Unified Dialog for Add (placeId=null) and Edit (placeId=String)
    private fun showDialog(placeId: String?, lat: Double, lon: Double, currentName: String, currentRadius: Int) {
        val context = requireContext()
        val builder = android.app.AlertDialog.Builder(context)
        builder.setTitle(if (placeId == null) "Add Safe Place" else "Edit Safe Place")
        
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)
        
        val input = EditText(context)
        input.hint = "Name (e.g. Home)"
        input.setText(currentName)
        layout.addView(input)
        
        val radiusLabel = TextView(context)
        radiusLabel.text = "Radius: $currentRadius m"
        radiusLabel.setPadding(0, 30, 0, 10)
        layout.addView(radiusLabel)
        
        val radiusBar = SeekBar(context)
        radiusBar.max = 1000
        radiusBar.progress = currentRadius
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            radiusBar.min = 50
        }
        
        radiusBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                 val r = if (progress < 50) 50 else progress
                 radiusLabel.text = "Radius: $r m"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        layout.addView(radiusBar)
        
        if (placeId == null) {
            val locText = TextView(context)
            locText.text = "Loc: ${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}"
            locText.textSize = 12f
            locText.setPadding(0, 10, 0, 0)
            layout.addView(locText)
        }

        builder.setView(layout)

        builder.setPositiveButton("Save") { _, _ ->
            val name = input.text.toString().trim()
            var radius = radiusBar.progress
            if (radius < 50) radius = 50
            
            if (name.isNotEmpty()) {
                if (placeId == null) {
                    savePlaceToFirebase(name, lat, lon, radius.toDouble())
                } else {
                    updatePlaceInFirebase(placeId, name, radius.toDouble())
                }
            } else {
                Toast.makeText(context, "Error: Name is required.", Toast.LENGTH_LONG).show()
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }
    
    private fun showEditDialog(placeId: String?, currentName: String, currentRadius: Double) {
        showDialog(placeId, 0.0, 0.0, currentName, currentRadius.toInt())
    }

    private fun updatePlaceInFirebase(placeId: String, name: String, radius: Double) {
        val fid = currentFamilyId ?: "Durandt"
        val ref = FirebaseDatabase.getInstance(DB_URL).getReference("families").child(fid).child("places").child(placeId)
        
        val updates = mapOf<String, Any>(
            "name" to name,
            "radius" to radius
        )
        
        ref.updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Place Updated!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "Update Failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }
    
    private fun savePlaceToFirebase(name: String, lat: Double, lon: Double, radius: Double) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Toast.makeText(requireContext(), "Error: User not logged in.", Toast.LENGTH_SHORT).show()
            return
        }

        val db = FirebaseDatabase.getInstance(DB_URL).reference

        // Fast path: Use cached ID
        if (currentFamilyId != null) {
            savePlaceToFamily(db, currentFamilyId!!, name, lat, lon, radius)
            return
        }

        // Slow path: Fetch or Create
        Toast.makeText(requireContext(), "Step 1: Checking for existing family (10s timeout)...", Toast.LENGTH_SHORT).show()
        
        // Force connection refresh
        FirebaseDatabase.getInstance(DB_URL).goOnline()
        
        val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            Toast.makeText(requireContext(), "Connection slow. Defaulting to 'Durandt'...", Toast.LENGTH_SHORT).show()
            manualOverride = true
            currentFamilyId = "Durandt"
            attachPlacesListener("Durandt") 
            savePlaceToFamily(db, "Durandt", name, lat, lon, radius)
            db.child("users").child(uid).child("familyId").setValue("Durandt")
        }
        timeoutHandler.postDelayed(timeoutRunnable, 5000) 
        
        db.child("users").child(uid).child("familyId").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                val fetchedId = snapshot.getValue(String::class.java)
                
                if (fetchedId != null) {
                    Toast.makeText(requireContext(), "Found Family: $fetchedId", Toast.LENGTH_SHORT).show()
                    currentFamilyId = fetchedId
                    // ALWAYS attach ensure the UI is in sync
                    attachPlacesListener(fetchedId)
                    savePlaceToFamily(db, fetchedId, name, lat, lon, radius)
                } else {
                    Toast.makeText(requireContext(), "Creating Family 'Durandt'...", Toast.LENGTH_SHORT).show()
                    val newId = "Durandt"
                    manualOverride = true
                    db.child("users").child(uid).child("familyId").setValue(newId)
                        .addOnSuccessListener {
                             currentFamilyId = newId
                             attachPlacesListener(newId)
                             savePlaceToFamily(db, newId, name, lat, lon, radius)
                        }
                        .addOnFailureListener {
                            currentFamilyId = newId
                            attachPlacesListener(newId)
                            savePlaceToFamily(db, newId, name, lat, lon, radius)
                        }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                manualOverride = true
                currentFamilyId = "Durandt"
                attachPlacesListener("Durandt")
                savePlaceToFamily(db, "Durandt", name, lat, lon, radius)
            }
        })
    }

    private fun savePlaceToFamily(db: DatabaseReference, familyId: String, name: String, lat: Double, lon: Double, radius: Double) {
        val targetId = if (familyId.isEmpty()) "Durandt" else familyId
        Toast.makeText(requireContext(), "Attempting save to '$targetId'...", Toast.LENGTH_SHORT).show()
        
        val pushRef = db.child("families").child(targetId).child("places").push()
        val placeId = pushRef.key ?: return
        
        val placeData = mapOf(
            "name" to name,
            "latitude" to lat,
            "longitude" to lon,
            "radius" to radius
        )
        
        pushRef.setValue(placeData)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(requireContext(), "SUCCESS: Saved to $targetId", Toast.LENGTH_LONG).show()
                    attachPlacesListener(targetId)
                } else {
                    Toast.makeText(requireContext(), "SAVE FAIL: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }
}
