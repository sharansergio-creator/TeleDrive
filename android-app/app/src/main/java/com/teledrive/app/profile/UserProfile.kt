package com.teledrive.app.profile

import android.content.Context
import com.google.gson.Gson

/**
 * Local user profile system (no backend required)
 * Used for ML calibration and personalization
 */
data class UserProfile(
    val name: String,
    val vehicleName: String,
    val engineCC: Int,
    val mileage: Float, // km/l
    val createdAt: Long = System.currentTimeMillis()
)

object UserProfileManager {
    private const val PREF_NAME = "user_profile"
    private const val KEY_PROFILE = "profile"
    private const val KEY_FIRST_TIME = "first_time"

    fun isFirstTime(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FIRST_TIME, true)
    }

    fun markOnboardingComplete(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FIRST_TIME, false).apply()
    }

    fun saveProfile(context: Context, profile: UserProfile) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = Gson().toJson(profile)
        prefs.edit().putString(KEY_PROFILE, json).apply()
        markOnboardingComplete(context)
    }

    fun getProfile(context: Context): UserProfile? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PROFILE, null) ?: return null
        return Gson().fromJson(json, UserProfile::class.java)
    }

    fun hasProfile(context: Context): Boolean {
        return getProfile(context) != null
    }
}

