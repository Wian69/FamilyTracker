package com.wiandurandt.familytracker.utils

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.wiandurandt.familytracker.BuildConfig
import java.io.File

object UpdateManager {

    private const val KEY_VERSION_CODE = "latest_version_code"
    private const val KEY_UPDATE_URL = "update_url"

    fun checkForUpdates(activity: Activity) {
        val remoteConfig = FirebaseRemoteConfig.getInstance()
        val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(3600) // 1 Hour cache (Production)
            .build()
        remoteConfig.setConfigSettingsAsync(configSettings)
        
        // Defauts
        val defaultMap = mapOf(
            KEY_VERSION_CODE to BuildConfig.VERSION_CODE.toLong(),
            KEY_UPDATE_URL to ""
        )
        remoteConfig.setDefaultsAsync(defaultMap)

        remoteConfig.fetchAndActivate().addOnCompleteListener(activity) { task ->
            if (task.isSuccessful) {
                val latestVersion = remoteConfig.getLong(KEY_VERSION_CODE)
                val updateUrl = remoteConfig.getString(KEY_UPDATE_URL)
                
                // Debug log
                android.util.Log.d("UpdateManager", "Remote Version: $latestVersion, Current: ${BuildConfig.VERSION_CODE}")

                if (latestVersion > BuildConfig.VERSION_CODE && updateUrl.isNotEmpty()) {
                    showUpdateDialog(activity, updateUrl)
                }
            }
        }
    }

    private fun showUpdateDialog(activity: Activity, url: String) {
        AlertDialog.Builder(activity)
            .setTitle("New Update Available")
            .setMessage("A newer version of the Family Tracker is available. Update now for the latest features and fixes.")
            .setPositiveButton("Update Now") { _, _ ->
                downloadAndInstall(activity, url)
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, url: String) {
        if (url.isEmpty()) return
        
        Toast.makeText(activity, "Downloading update...", Toast.LENGTH_SHORT).show()

        val fileName = "FamilyTracker_Update.apk"
        val request = DownloadManager.Request(Uri.parse(url))
        request.setTitle("Updating Family Tracker")
        request.setDescription("Downloading latest version...")
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setMimeType("application/vnd.android.package-archive")

        val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        // Listen for completion
        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadId) {
                    activity.unregisterReceiver(this)
                    
                    val query = DownloadManager.Query()
                    query.setFilterById(downloadId)
                    val c = downloadManager.query(query)
                    if (c.moveToFirst()) {
                        val status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val uriString = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
                            // Depending on Android version, might differ
                            // Usually: file:///storage/... or content://...
                            // But DownloadManager usually returns file:// URI for setDestinationInExternalPublicDir
                             
                            val fileUri = if (uriString.startsWith("file://")) {
                                Uri.parse(uriString)
                            } else {
                                // Reconstruct path if needed, or use FileProvider
                                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                                FileProvider.getUriForFile(activity, "${activity.packageName}.provider", file)
                            }
                            
                            val installIntent = Intent(Intent.ACTION_VIEW)
                            installIntent.setDataAndType(fileUri, "application/vnd.android.package-archive")
                            installIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                            
                            // For Android N+ need file provider if using file:// URI exposed? 
                            // Actually DownloadManager public dir usually safe, but let's be robust
                            
                            try {
                                activity.startActivity(installIntent)
                            } catch (e: Exception) {
                                // Sometimes FileUriExposedException
                                // Let's try explicit FileProvider approach strictly
                                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                                if (file.exists()) {
                                    val contentUri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", file)
                                    val newIntent = Intent(Intent.ACTION_VIEW)
                                    newIntent.setDataAndType(contentUri, "application/vnd.android.package-archive")
                                    newIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    activity.startActivity(newIntent)
                                } else {
                                    Toast.makeText(activity, "Update file not found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    c.close()
                }
            }
        }
        activity.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
    }
}
