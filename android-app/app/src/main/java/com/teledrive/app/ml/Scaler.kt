package com.teledrive.app.ml

import android.content.Context
import org.json.JSONObject

class Scaler(context: Context) {

    private val mean: FloatArray
    private val scale: FloatArray

    init {
        val json = context.assets.open("scaler.json")
            .bufferedReader().use { it.readText() }

        val obj = JSONObject(json)

        val meanArray = obj.getJSONArray("mean")
        val scaleArray = obj.getJSONArray("scale")

        mean = FloatArray(meanArray.length())
        scale = FloatArray(scaleArray.length())

        for (i in 0 until meanArray.length()) {
            mean[i] = meanArray.getDouble(i).toFloat()
            scale[i] = scaleArray.getDouble(i).toFloat()
        }
    }

    fun normalize(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        for (i in input.indices) {
            out[i] = (input[i] - mean[i]) / scale[i]
        }
        return out
    }
}