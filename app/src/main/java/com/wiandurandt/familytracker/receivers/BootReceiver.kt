package com.wiandurandt.familytracker.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wiandurandt.familytracker.services.LocationService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "com.wiandurandt.familytracker.RESTART_SERVICE") {
            val serviceIntent = Intent(context, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            // Schedule Safety Net immediately on Boot
            val workManager = androidx.work.WorkManager.getInstance(context)
            val periodicRequest = androidx.work.PeriodicWorkRequest.Builder(
                com.wiandurandt.familytracker.services.KeepAliveWorker::class.java,
                15, java.util.concurrent.TimeUnit.MINUTES
            ).build()
            
            workManager.enqueueUniquePeriodicWork(
                "KeepAliveWorker",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        }
    }
}
