package com.teledrive.app.evidence

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Evidence Cleanup Worker
 * Auto-deletes evidence older than 24 hours
 * Runs daily at 3 AM (low battery impact)
 */
class EvidenceCleanupWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    
    override fun doWork(): Result {
        return try {
            Log.d("EvidenceCleanup", "Starting cleanup...")
            
            EvidenceManager.cleanupOldEvidence(applicationContext)
            
            Log.d("EvidenceCleanup", "Cleanup completed successfully")
            Result.success()
            
        } catch (e: Exception) {
            Log.e("EvidenceCleanup", "Cleanup failed", e)
            Result.retry()
        }
    }
    
    companion object {
        private const val WORK_NAME = "evidence_cleanup"
        
        /**
         * Schedule daily cleanup worker
         * Call this in Application.onCreate()
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true) // Only when battery is good
                .build()
            
            val cleanupRequest = PeriodicWorkRequestBuilder<EvidenceCleanupWorker>(
                24, TimeUnit.HOURS // Run daily
            )
                .setConstraints(constraints)
                .setInitialDelay(calculateInitialDelay(), TimeUnit.MILLISECONDS)
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                cleanupRequest
            )
            
            Log.d("EvidenceCleanup", "Cleanup worker scheduled")
        }
        
        /**
         * Calculate delay to run at 3 AM
         */
        private fun calculateInitialDelay(): Long {
            val calendar = java.util.Calendar.getInstance()
            val now = calendar.timeInMillis
            
            // Set to 3 AM today
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 3)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            
            // If 3 AM has passed, schedule for tomorrow
            if (calendar.timeInMillis <= now) {
                calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            
            return calendar.timeInMillis - now
        }
        
        /**
         * Cancel scheduled cleanup
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d("EvidenceCleanup", "Cleanup worker cancelled")
        }
    }
}

