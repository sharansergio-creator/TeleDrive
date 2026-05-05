package com.teledrive.app.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.teledrive.app.core.RideSessionManager
import com.teledrive.app.evidence.EvidenceManager
import com.teledrive.app.services.SensorService
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraControllerActivity : AppCompatActivity() {

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var dummyPreview: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Step 2: PREMIUM FEEL - No layout content means no "boxy" UI flash
        // We create a tiny, invisible preview just to satisfy CameraX requirements
        dummyPreview = PreviewView(this).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            visibility = View.INVISIBLE
        }
        setContentView(dummyPreview)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraFlow()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    private fun startCameraFlow() {
        val eventType = intent.getStringExtra("event_type") ?: "manual"
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Build ImageCapture with optimized resolution for speed
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)

                // Trigger photo almost immediately (100ms for lens focus)
                dummyPreview.post {
                    takePhoto(eventType)
                }
            } catch (e: Exception) {
                Log.e("Camera", "Binding failed", e)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto(eventType: String) {
        val imageCapture = imageCapture ?: return

        // Save into the dedicated evidence directory using the canonical filename:
        // event_<typeSlug>_<epochMs>.jpg  — parsed by EvidenceManager.parseFile()
        val file = EvidenceManager.newEvidenceFile(this, eventType)

        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val imagePath = file.absolutePath
                    Log.i("Camera", "✅ Evidence Captured: $imagePath")

                    // Step 3: DATA PERSISTENCE - Save to both locations to ensure Summary Screen sees it
                    RideSessionManager.lastEventImagePath = imagePath
                    SensorService.lastCapturedImagePath = imagePath

                    // Close activity immediately to stop lag
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("Camera", "Capture Failed", exception)
                    finish()
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCameraFlow()
        } else {
            finish()
        }
    }
}