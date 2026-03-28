package com.teledrive.app.ml

import android.content.Context
import com.teledrive.app.core.SensorSample
import java.io.File
import java.io.FileWriter

class DataLogger(context: Context) {

    private val rideFileName = "ride_${System.currentTimeMillis()}.csv"
    val file = File(context.getExternalFilesDir(null), rideFileName)

    init {
        if (!file.exists()) {
            file.writeText("timestamp,ax,ay,az,gx,gy,gz,label\n")
        }
    }

    fun logWindow(window: List<SensorSample>, label: Int) {

        val writer = FileWriter(file, true)

        for (sample in window) {
            writer.append(
                "${sample.timestamp}," +
                        "${sample.ax},${sample.ay},${sample.az}," +
                        "${sample.gx},${sample.gy},${sample.gz}," +
                        "$label\n"
            )
        }

        writer.flush()
        writer.close()
    }
}