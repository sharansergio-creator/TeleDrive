package com.teledrive.app.ml

import android.content.Context
import com.teledrive.app.core.SensorSample
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

class MLModelRunner(context: Context) {

    private val interpreter: Interpreter

    init {
        val fileDescriptor = context.assets.openFd("model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel

        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength

        val mappedBuffer = fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            startOffset,
            declaredLength
        )

        interpreter = Interpreter(mappedBuffer)
    }

    fun predict(window: List<SensorSample>): Int {

        val input = Array(1) { Array(50) { FloatArray(6) } }

        val size = minOf(window.size, 50)

        for (i in 0 until size) {
            val s = window[i]

            input[0][i][0] = normalize(s.ax, 10f)
            input[0][i][1] = normalize(s.ay, 10f)
            input[0][i][2] = normalize(s.az, 10f)
            input[0][i][3] = normalize(s.gx, 5f)
            input[0][i][4] = normalize(s.gy, 5f)
            input[0][i][5] = normalize(s.gz, 5f)
        }

        val output = Array(1) { FloatArray(4) }

        interpreter.run(input, output)

        return output[0].indices.maxByOrNull { output[0][it] } ?: 0
    }

    private fun normalize(value: Float, scale: Float): Float {
        return (value / scale).coerceIn(-1f, 1f)
    }
}