package com.wiandurandt.familytracker.services

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import android.content.Intent
import android.os.Build

class KeepAliveWorker(private val context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {

    override fun doWork(): Result {
        // 1. Send Heartbeat to Firebase (So usage shows "Online" even if location is off)
        sendHeartbeat()

        // 2. Restart Service if needed
        if (!isServiceRunning(LocationService::class.java)) {
            restartService()
        }
        return Result.success()
    }

    private fun sendHeartbeat() {
        try {
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                val ref = com.google.firebase.database.FirebaseDatabase.getInstance("https://familiy-tracker-default-rtdb.firebaseio.com/")
                    .getReference("users").child(uid)
                
                val updates = HashMap<String, Any>()
                updates["lastUpdated"] = System.currentTimeMillis()
                // Optional: updates["status"] = "Online (Background)"
                
                ref.updateChildren(updates)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun restartService() {
        val intent = Intent(context, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
