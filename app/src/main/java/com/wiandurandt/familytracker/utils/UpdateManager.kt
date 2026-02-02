package com.wiandurandt.familytracker.utils

import android.app.AlertDialog
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.wiandurandt.familytracker.BuildConfig
import com.wiandurandt.familytracker.MainActivity
import com.wiandurandt.familytracker.R
import java.io.File

object UpdateManager {

    private val DB_URL = "https://familiy-tracker-default-rtdb.firebaseio.com/"
    private const val UPDATE_FILE_NAME = "family-tracker-update.apk"
    private const val CHANNEL_ID = "UPDATE_CHANNEL"

    fun checkForUpdates(context: Context, fromBackground: Boolean = false) {
        val db = FirebaseDatabase.getInstance(DB_URL).getReference("config")
        
        db.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val latestVersionRaw = snapshot.child("latest_version_code").value
                val updateUrl = snapshot.child("update_url").getValue(String::class.java)
                
                android.util.Log.d("UpdateManager", "Firebase Data: version=$latestVersionRaw, url=$updateUrl")
                
                val latestVersion = when (latestVersionRaw) {
                    is Long -> latestVersionRaw.toInt()
                    is Int -> latestVersionRaw
                    is String -> latestVersionRaw.toIntOrNull() ?: 0
                    else -> 0
                }
                
                val currentVersion = BuildConfig.VERSION_CODE
                
                if (latestVersion > currentVersion && !updateUrl.isNullOrEmpty()) {
                    if (fromBackground) {
                        showUpdateNotification(context, updateUrl)
                    } else {
                        showUpdateDialog(context, updateUrl)
                    }
                } else if (!fromBackground) {
                    if (latestVersion == 0) {
                        Toast.makeText(context, "Update Check: No data found in Firebase 'config' node.", Toast.LENGTH_LONG).show()
                    } else if (updateUrl.isNullOrEmpty()) {
                        Toast.makeText(context, "Update Check: Found version $latestVersion but 'update_url' is missing.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Latest version $latestVersion is already installed (You have $currentVersion).", Toast.LENGTH_LONG).show()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                if (!fromBackground) {
                    Toast.makeText(context, "Update Check Failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
                android.util.Log.e("UpdateManager", "Database Error: ${error.message}")
            }
        })
    }

    private fun showUpdateDialog(context: Context, url: String) {
        AlertDialog.Builder(context)
            .setTitle("New Update Available 🚀")
            .setMessage("A newer version of Family Tracker is available. High speed download will start automatically.")
            .setPositiveButton("Update Now") { _: DialogInterface, _: Int ->
                startDownload(context, url)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showUpdateNotification(context: Context, url: String) {
        createNotificationChannel(context)
        
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("New App Update 🚀")
            .setContentText("A new version of Family Tracker is available. Tap to open.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(999, notification)
    }

    fun startDownload(context: Context, url: String) {
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), UPDATE_FILE_NAME)
            if (file.exists()) file.delete()

            Toast.makeText(context, "Starting download...", Toast.LENGTH_LONG).show()
            
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                .setTitle("Family Tracker Update")
                .setDescription("Downloading latest version...")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, UPDATE_FILE_NAME)
            
            val downloadId = downloadManager.enqueue(request)
            
            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == downloadId) {
                        Toast.makeText(context, "Download complete. Starting installation...", Toast.LENGTH_SHORT).show()
                        installApk(context)
                        context.unregisterReceiver(this)
                    }
                }
            }
            
            ContextCompat.registerReceiver(
                context, onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun installApk(context: Context) {
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), UPDATE_FILE_NAME)
            if (file.exists()) {
                val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(contentUri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "Update file not found.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error launching installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Set up a persistent listener for instant notifications when config changes in Firebase.
     */
    fun listenForUpdates(context: Context) {
        val db = FirebaseDatabase.getInstance(DB_URL).getReference("config")
        db.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val latestVersionRaw = snapshot.child("latest_version_code").value
                val latestVersion = when (latestVersionRaw) {
                    is Long -> latestVersionRaw.toInt()
                    is Int -> latestVersionRaw
                    is String -> latestVersionRaw.toIntOrNull() ?: 0
                    else -> 0
                }
                
                
                val updateUrl = snapshot.child("update_url").getValue(String::class.java)
                val currentVersion = BuildConfig.VERSION_CODE
                
                if (latestVersion > currentVersion && !updateUrl.isNullOrEmpty()) {
                    showUpdateNotification(context, updateUrl)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(context, "Update Check Cancelled: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "App Updates", NotificationManager.IMPORTANCE_HIGH)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
