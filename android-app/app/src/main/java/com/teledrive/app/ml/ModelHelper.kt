package com.teledrive.app.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class ModelHelper(context: Context) {

    companion object {
        private const val TAG = "ModelHelper"
        const val MODEL_FILENAME = "model.tflite"
        // v4: 9-feature 1D-CNN (ax,ay,az,gx,gy,gz,acc_mag,jerk_mag,speed), 4-class, HARSH_ACCEL recall=0.975
        const val MODEL_VERSION = "v4"
    }

    private val interpreter: Interpreter

    init {
        interpreter = Interpreter(loadModel(context))
        Log.d(TAG, "Loaded model: $MODEL_FILENAME  version=$MODEL_VERSION")
    }

    private fun loadModel(context: Context): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_FILENAME)
        val inputStream = fileDescriptor.createInputStream()
        val fileChannel = inputStream.channel

        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    fun predict(input: Array<Array<FloatArray>>): FloatArray {
        val output = Array(1) { FloatArray(4) }
        interpreter.run(input, output)
        return output[0]
    }
}