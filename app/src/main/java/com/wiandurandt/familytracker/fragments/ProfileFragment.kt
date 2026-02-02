package com.wiandurandt.familytracker.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.wiandurandt.familytracker.R
import com.wiandurandt.familytracker.auth.AuthActivity

class ProfileFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    private val pickMedia = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            uploadImage(uri)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        val ivProfile = view.findViewById<android.widget.ImageView>(R.id.ivProfilePicture)
        
        view.findViewById<TextView>(R.id.tvEmail).text = user?.email
        
        user?.uid?.let { uid ->
            val ref = FirebaseDatabase.getInstance().getReference("users").child(uid)
            // Real-time listener for Family Code
            ref.child("familyId").addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val code = snapshot.getValue(String::class.java)
                    val tvCode = view.findViewById<TextView>(R.id.tvFamilyCode)
                    
                    if (code != null) {
                        tvCode.text = "Family Code: $code (Tap to copy)"
                        tvCode.setOnClickListener {
                            val clipboard = androidx.core.content.ContextCompat.getSystemService(requireContext(), android.content.ClipboardManager::class.java)
                            val clip = android.content.ClipData.newPlainText("Family Code", code)
                            clipboard?.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, "Code copied!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        tvCode.text = "No Family Group (Add a place to create one)"
                        tvCode.setOnClickListener(null)
                    }
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })
            
            // Listen for profile changes in real-time
            ref.child("profileBase64").addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val base64 = snapshot.getValue(String::class.java)
                    if (base64 != null) {
                        try {
                            val imageBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                            val decodedImage = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                            // Use view context to avoid Fragment detachment issues
                            if (isAdded && context != null) {
                                com.bumptech.glide.Glide.with(ivProfile.context)
                                    .load(decodedImage)
                                    .circleCrop()
                                    .into(ivProfile)
                            }
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Error loading image", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                    android.widget.Toast.makeText(context, "Failed to load profile", android.widget.Toast.LENGTH_SHORT).show()
                }
            })
        }
        
        ivProfile.setOnClickListener {
            pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        view.findViewById<Button>(R.id.btnEnableLocation).setOnClickListener {
            (requireActivity() as? com.wiandurandt.familytracker.MainActivity)?.checkPermissions(true)
        }

        view.findViewById<Button>(R.id.btnLogout).setOnClickListener {
            auth.signOut()
            startActivity(Intent(requireContext(), AuthActivity::class.java))
            requireActivity().finish()
        }
        
        view.findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
             com.wiandurandt.familytracker.utils.UpdateManager.checkForUpdates(requireContext(), false)
        }
        
        // Notification Settings
        val switch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchNotifications)
        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        switch.isChecked = prefs.getBoolean("notifications_enabled", true)
        switch.isEnabled = true // Force enable UI to avoid it looking "greyed out"
        
        switch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notifications_enabled", isChecked).apply()
        }
        
        // SET VERSION
        view.findViewById<TextView>(R.id.tvVersion).text = "Version v${com.wiandurandt.familytracker.BuildConfig.VERSION_NAME}"
    }

    private fun uploadImage(uri: android.net.Uri) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        
        android.widget.Toast.makeText(context, "Processing...", android.widget.Toast.LENGTH_SHORT).show()
        
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            
            // Resize to standard icon size (approx 256x256) to keep DB small
            val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(originalBitmap, 256, 256, true)
            
            val outputStream = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
            val byteArray = outputStream.toByteArray()
            val base64String = android.util.Base64.encodeToString(byteArray, android.util.Base64.DEFAULT)
            
            saveBase64ToDatabase(base64String)
            
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Failed to process image: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBase64ToDatabase(base64: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseDatabase.getInstance().getReference("users").child(uid)
            .child("profileBase64").setValue(base64)
            .addOnSuccessListener {
                android.widget.Toast.makeText(context, "Profile Updated!", android.widget.Toast.LENGTH_SHORT).show()
                view?.findViewById<android.widget.ImageView>(R.id.ivProfilePicture)?.let { iv ->
                    val imageBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    val decodedImage = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    com.bumptech.glide.Glide.with(this).load(decodedImage).circleCrop().into(iv)
                }
            }
            .addOnFailureListener {
                android.widget.Toast.makeText(context, "Database Error: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
            }
    }
}
