package com.wiandurandt.familytracker.utils

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.wiandurandt.familytracker.BuildConfig
import java.io.File

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
            .setMessage("A newer version of Family Tracker is available. High speed download will start automatically.")
            .setPositiveButton("Update Now") { dialog: DialogInterface, which: Int ->
                startDownload(context, url)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startDownload(context: Context, url: String) {
        Toast.makeText(context, "Starting download...", Toast.LENGTH_LONG).show()
        
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(url)
        val request = DownloadManager.Request(uri)
        
        request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
        request.setTitle("Family Tracker Update")
        request.setDescription("Downloading latest version...")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "family-tracker-update.apk")
        
        val downloadId = downloadManager.enqueue(request)
        
        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == downloadId) {
                    installApk(context)
                    context.unregisterReceiver(this)
                }
            }
        }
        
        context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun installApk(context: Context) {
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "family-tracker-update.apk")
            if (file.exists()) {
                val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setDataAndType(contentUri, "application/vnd.android.package-archive")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error launching installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
