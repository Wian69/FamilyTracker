package com.wiandurandt.familytracker.utils

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.wiandurandt.familytracker.BuildConfig

object UpdateManager {

    private val DB_URL = "https://familiy-tracker-default-rtdb.firebaseio.com/"

    fun checkForUpdates(context: Context) {
        val db = FirebaseDatabase.getInstance(DB_URL).getReference("config")
        
        db.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val latestVersion = snapshot.child("latest_version_code").getValue(Int::class.java) ?: 0
                val updateUrl = snapshot.child("update_url").getValue(String::class.java)
                
                // Get current app version
                val currentVersion = BuildConfig.VERSION_CODE
                
                if (latestVersion > currentVersion && !updateUrl.isNullOrEmpty()) {
                    showUpdateDialog(context, updateUrl)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun showUpdateDialog(context: Context, url: String) {
        AlertDialog.Builder(context)
            .setTitle("New Update Available 🚀")
            .setMessage("A newer version of Family Tracker is available. Please update to get the latest features.")
            .setPositiveButton("Update Now") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Could not open browser.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }
}
