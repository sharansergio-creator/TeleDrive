package com.teledrive.app.TripHistory

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object TripStorage {

    private const val PREF_NAME = "trip_history"
    private const val KEY = "trips"

    fun save(context: Context, trip: TripSummary) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val gson = Gson()

        val list = getAll(context).toMutableList()
        list.add(0, trip)

        val json = gson.toJson(list)
        prefs.edit().putString(KEY, json).apply()
    }

    fun getAll(context: Context): List<TripSummary> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, null) ?: return emptyList()

        val type = object : TypeToken<List<TripSummary>>() {}.type
        return Gson().fromJson(json, type)
    }
}