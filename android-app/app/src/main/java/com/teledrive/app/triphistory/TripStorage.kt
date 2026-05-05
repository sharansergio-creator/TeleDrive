package com.teledrive.app.triphistory

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object TripStorage {

    private const val PREF_NAME = "trip_history"
    private const val KEY = "trips"
    private const val PREFS_APP = "teledrive_prefs"
    private const val KEY_OVERALL_SCORE = "overall_score"

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
        return Gson().fromJson<List<TripSummary>>(json, type)
            .sortedByDescending { it.timestamp }
    }

    /** Average score of the most recent 5 trips (0 if no trips recorded). */
    fun getOverallScore(context: Context): Int {
        val trips = getAll(context)
        if (trips.isEmpty()) return 0
        return trips.take(5).map { it.score }.average().toInt()
    }

    /** Recalculate and persist the overall score to teledrive_prefs. */
    fun saveOverallScore(context: Context) {
        val score = getOverallScore(context)
        context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .edit().putInt(KEY_OVERALL_SCORE, score).apply()
    }

    /** Read the last-persisted overall score without re-computing. */
    fun readOverallScore(context: Context): Int {
        return context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .getInt(KEY_OVERALL_SCORE, 0)
    }
}