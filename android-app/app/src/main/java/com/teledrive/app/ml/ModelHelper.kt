package com.teledrive.app.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class ModelHelper(context: Context) {

    private val interpreter: Interpreter

    init {
        interpreter = Interpreter(loadModel(context))
    }

    private fun loadModel(context: Context): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd("model.tflite")
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