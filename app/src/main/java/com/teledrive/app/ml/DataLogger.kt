package com.teledrive.app.ml

import android.content.Context
import com.teledrive.app.core.SensorSample
import java.io.File
import java.io.FileWriter

class DataLogger(context: Context) {

    private val file = File(context.getExternalFilesDir(null), "dataset.csv")

    init {
        if (!file.exists()) {
            file.writeText("ax,ay,az,gx,gy,gz,label\n")
        }
    }

    fun logWindow(window: List<SensorSample>, label: Int) {
        val writer = FileWriter(file, true)

        for (sample in window) {
            writer.append(
                "${sample.ax},${sample.ay},${sample.az}," +
                        "${sample.gx},${sample.gy},${sample.gz},$label\n"
            )
        }

        writer.flush()
        writer.close()
    }
}