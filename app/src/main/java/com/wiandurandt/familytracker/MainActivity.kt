package com.wiandurandt.familytracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.wiandurandt.familytracker.auth.AuthActivity
import com.wiandurandt.familytracker.fragments.FamilyFragment
import com.wiandurandt.familytracker.fragments.MapFragment
import com.wiandurandt.familytracker.fragments.ProfileFragment
import com.wiandurandt.familytracker.fragments.PlacesFragment
import com.wiandurandt.familytracker.services.LocationService

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    private val mapFragment = com.wiandurandt.familytracker.fragments.MapFragment()
    private val familyFragment = com.wiandurandt.familytracker.fragments.FamilyFragment()
    private val profileFragment = com.wiandurandt.familytracker.fragments.ProfileFragment()
    private val placesFragment = com.wiandurandt.familytracker.fragments.PlacesFragment()
    private var activeFragment: androidx.fragment.app.Fragment = mapFragment

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        
        if (fineLocationGranted || coarseLocationGranted) {
            startLocationService()
            checkBackgroundPermission()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissions()

        // Fix: Use correct ID matching activity_main.xml (bottom_navigation)
        val navView: com.google.android.material.bottomnavigation.BottomNavigationView = findViewById(R.id.bottom_navigation)
        
        // Add all fragments but hide them except map
        // Fix: Use correct ID matching activity_main.xml (nav_host_fragment)
        supportFragmentManager.beginTransaction().add(R.id.nav_host_fragment, profileFragment, "4").hide(profileFragment).commit()
        supportFragmentManager.beginTransaction().add(R.id.nav_host_fragment, placesFragment, "3").hide(placesFragment).commit()
        supportFragmentManager.beginTransaction().add(R.id.nav_host_fragment, familyFragment, "2").hide(familyFragment).commit()
        supportFragmentManager.beginTransaction().add(R.id.nav_host_fragment, mapFragment, "1").commit()



        setupBottomNavigation()
        
        // Auto-Check for OTA Updates
        com.wiandurandt.familytracker.utils.UpdateManager.checkForUpdates(this)
    }

    override fun onResume() {
        super.onResume()
        checkPermissions(false) 
    }

    private fun setupBottomNavigation() {
        // Fix: Use correct ID (bottom_navigation)
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                // Fix: Use correct ID matching bottom_nav_menu.xml (navigation_map)
                R.id.navigation_map -> {
                    supportFragmentManager.beginTransaction().hide(activeFragment).show(mapFragment).commit()
                    activeFragment = mapFragment
                    true
                }
                R.id.navigation_family -> {
                    supportFragmentManager.beginTransaction().hide(activeFragment).show(familyFragment).commit()
                    activeFragment = familyFragment
                    true
                }
                R.id.navigation_places -> {
                    supportFragmentManager.beginTransaction().hide(activeFragment).show(placesFragment).commit()
                    activeFragment = placesFragment
                    true
                }
                R.id.navigation_profile -> {
                    supportFragmentManager.beginTransaction().hide(activeFragment).show(profileFragment).commit()
                    activeFragment = profileFragment
                    true
                }
                else -> false
            }
        }
        // Set initial selection if not already set (e.g., after rotation)
        if (bottomNav.selectedItemId == 0) { // Check if no item is selected
            bottomNav.selectedItemId = R.id.navigation_map
        }
    }
    
    fun switchToMap() {
        val navView: com.google.android.material.bottomnavigation.BottomNavigationView = findViewById(R.id.bottom_navigation)
        navView.selectedItemId = R.id.navigation_map
    }

    fun checkPermissions(requestIfMissing: Boolean = true) {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        
        if (hasFine || hasCoarse) {
            startLocationService()
            if (requestIfMissing) checkBackgroundPermission()
        } else if (requestIfMissing) {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun checkBackgroundPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotification = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!hasNotification) {
                 requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackground = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasBackground) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Background Location Needed")
                    .setMessage("To track your location even when the app is closed, please select 'Allow all the time' in settings.")
                    .setPositiveButton("Grant") { _, _ ->
                        requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        
        checkBatteryOptimization()
    }
    
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(android.os.PowerManager::class.java)
            val packageName = packageName
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Disable Battery Optimization")
                    .setMessage("To ensure location updates work when the screen is off, please allow the app to ignore battery optimizations.")
                    .setPositiveButton("Allow") { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = android.net.Uri.parse("package:$packageName")
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {}
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun showPermissionDeniedDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Location Permission Required")
            .setMessage("This app needs location access to function. Please grant permission in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = android.net.Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scheduleBackgroundWork() {
        val workManager = androidx.work.WorkManager.getInstance(this)
        
        // 1. Periodic Check (Every 15 mins - Minimum allowed by Android)
        val periodicRequest = androidx.work.PeriodicWorkRequest.Builder(
            com.wiandurandt.familytracker.services.KeepAliveWorker::class.java,
            15, java.util.concurrent.TimeUnit.MINUTES
        ).build()
        
        workManager.enqueueUniquePeriodicWork(
            "KeepAliveWorker",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP, // Don't replace if already scheduled
            periodicRequest
        )
        
        // 2. Immediate Check (Start now)
        val oneTimeRequest = androidx.work.OneTimeWorkRequest.Builder(
            com.wiandurandt.familytracker.services.KeepAliveWorker::class.java
        ).build()
        workManager.enqueue(oneTimeRequest)
    }

    private fun startLocationService() {
        // Start Service Directly
        val intent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        // Schedule Safety Net
        scheduleBackgroundWork()
    }
}
