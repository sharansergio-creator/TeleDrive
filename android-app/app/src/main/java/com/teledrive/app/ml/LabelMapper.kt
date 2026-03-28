package com.teledrive.app.ml

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LabelMapper(context: Context) {

    private val labels: List<String>

    init {
        val json = context.assets.open("labels.json")
            .bufferedReader()
            .use { it.readText() }

        val temp = mutableListOf<String>()

        try {
            val trimmedJson = json.trim()
            if (trimmedJson.startsWith("[")) {
                val jsonArray = JSONArray(trimmedJson)
                for (i in 0 until jsonArray.length()) {
                    temp.add(jsonArray.getString(i))
                }
            } else if (trimmedJson.startsWith("{")) {
                val jsonObject = JSONObject(trimmedJson)
                val map = mutableMapOf<Int, String>()
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val index = jsonObject.getInt(key)
                    map[index] = key
                }
                val maxIndex = map.keys.maxOrNull() ?: -1
                for (i in 0..maxIndex) {
                    temp.add(map[i] ?: "UNKNOWN")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        labels = temp
    }

    fun getLabel(index: Int): String {
        return if (index in labels.indices) {
            labels[index]
        } else {
            "UNKNOWN"
        }
    }
}