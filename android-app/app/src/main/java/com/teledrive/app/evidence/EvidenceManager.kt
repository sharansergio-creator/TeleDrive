package com.teledrive.app.evidence

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Evidence Item — represents one captured evidence image on disk.
 *
 * All state is derived from the filesystem; nothing is held in memory
 * between sessions. This means evidence survives app restarts and is
 * always consistent with what actually exists on storage.
 *
 * Filename format: event_<typeSlug>_<epochMs>.jpg
 *   typeSlug: "accel" | "brake" | "unstable" | "event"
 */
data class EvidenceItem(
    val file: File,
    val eventType: String,   // HARSH_ACCELERATION | HARSH_BRAKING | UNSTABLE_RIDE | UNKNOWN
    val timestamp: Long
)

object EvidenceManager {

    private const val TAG = "EvidenceManager"
    private const val MAX_EVIDENCE_AGE_MS = 24L * 60 * 60 * 1000 // 24 hours

    // ── Directory ────────────────────────────────────────────────────────────

    fun getEvidenceDir(context: Context): File {
        val dir = context.getExternalFilesDir("evidence")
            ?: File(context.filesDir, "evidence")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ── File creation (called by CameraControllerActivity) ───────────────────

    /**
     * Build a new File path for an evidence image before it is captured.
     * Format: event_<typeSlug>_<epochMs>.jpg
     */
    fun newEvidenceFile(context: Context, eventType: String): File {
        val typeSlug = when (eventType.uppercase()) {
            "HARSH_ACCELERATION" -> "accel"
            "HARSH_BRAKING"      -> "brake"
            "UNSTABLE_RIDE"      -> "unstable"
            else                 -> "event"
        }
        val timestamp = System.currentTimeMillis()
        return File(getEvidenceDir(context), "event_${typeSlug}_$timestamp.jpg")
    }

    // ── Filesystem read ───────────────────────────────────────────────────────

    /** Load the most-recent 50 evidence items from disk, newest first. IO-safe to call from any thread. */
    fun loadAll(context: Context): List<EvidenceItem> =
        getEvidenceDir(context)
            .listFiles { f -> f.isFile && f.name.startsWith("event_") && f.name.endsWith(".jpg") }
            ?.mapNotNull { parseFile(it) }
            ?.sortedByDescending { it.timestamp }
            ?.take(50)
            ?: emptyList()

    /** Fast count of evidence files on disk. Safe to call on the main thread. */
    fun getCount(context: Context): Int =
        getEvidenceDir(context)
            .listFiles { f -> f.isFile && f.name.startsWith("event_") && f.name.endsWith(".jpg") }
            ?.size ?: 0

    /**
     * Parse a filename into an [EvidenceItem].
     * Also accepts the legacy format "evidence_<timestamp>.jpg" written by older builds.
     */
    fun parseFile(file: File): EvidenceItem? = try {
        val name = file.nameWithoutExtension
        if (name.startsWith("event_")) {
            // event_<typeSlug>_<timestamp>
            val parts = name.split("_")            // e.g. ["event","accel","1710001234"]
            val timestamp = parts.last().toLongOrNull() ?: file.lastModified()
            val rawSlug = parts.drop(1).dropLast(1).joinToString("_").lowercase()
            val eventType = when {
                rawSlug.contains("accel")    -> "HARSH_ACCELERATION"
                rawSlug.contains("brake")    -> "HARSH_BRAKING"
                rawSlug.contains("unstable") -> "UNSTABLE_RIDE"
                else                         -> "UNKNOWN"
            }
            EvidenceItem(file, eventType, timestamp)
        } else {
            // Legacy: evidence_<timestamp>.jpg
            val timestamp = name.removePrefix("evidence_").toLongOrNull() ?: file.lastModified()
            EvidenceItem(file, "UNKNOWN", timestamp)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not parse filename: ${file.name}", e)
        null
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /** Delete an evidence file from storage. Returns true on success. */
    fun delete(item: EvidenceItem): Boolean = try {
        item.file.delete().also { Log.d(TAG, "Deleted: ${item.file.name}") }
    } catch (e: Exception) {
        Log.e(TAG, "Delete failed: ${item.file.name}", e)
        false
    }

    // ── Auto-cleanup ──────────────────────────────────────────────────────────

    /**
     * Delete evidence files older than 24 hours.
     * Filesystem-based — safe to call from WorkManager or any coroutine.
     */
    fun cleanupOld(context: Context): Int {
        val now = System.currentTimeMillis()
        var deleted = 0
        getEvidenceDir(context).listFiles()?.forEach { file ->
            val item = parseFile(file) ?: return@forEach
            if (now - item.timestamp > MAX_EVIDENCE_AGE_MS) {
                if (file.delete()) {
                    deleted++
                    Log.d(TAG, "Auto-deleted: ${file.name}")
                }
            }
        }
        Log.d(TAG, "Cleanup complete — $deleted file(s) removed")
        return deleted
    }

    /** Alias kept for [EvidenceCleanupWorker] compatibility. */
    fun cleanupOldEvidence(context: Context) { cleanupOld(context) }

    // ── Gallery save ──────────────────────────────────────────────────────────

    /**
     * Copy an evidence image to the public gallery.
     * Uses MediaStore on Android 10+; direct file copy on Android 9 and below.
     * Must be called from a background (IO) thread.
     */
    fun saveToGallery(context: Context, item: EvidenceItem): Boolean {
        return try {
            if (!item.file.exists()) return false
            val displayName = "TeleDrive_${item.eventType}_${item.timestamp}.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/TeleDrive")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    item.file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val galleryDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "TeleDrive"
                )
                galleryDir.mkdirs()
                val dest = File(galleryDir, displayName)
                item.file.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(
                    context, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null
                )
            }
            Log.d(TAG, "Saved to gallery: $displayName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveToGallery failed", e)
            false
        }
    }
}
