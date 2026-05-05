package com.teledrive.app.location

import android.content.Context
import android.location.Geocoder
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Edge-based location cache system
 * - No backend calls
 * - Local geocoding with fallback
 * - Persistent cache
 */

data class LocationCache(
    val lat: Double,
    val lng: Double,
    val name: String
)

object LocationCacheManager {
    private const val PREF_NAME = "location_cache"
    private const val KEY_CACHE = "cache"
    private const val ROUNDING_PRECISION = 3 // ~100m accuracy
    
    private var memoryCache = mutableMapOf<String, String>()

    private fun roundCoordinate(coord: Double): Double {
        return String.format("%.${ROUNDING_PRECISION}f", coord).toDouble()
    }

    private fun getCacheKey(lat: Double, lng: Double): String {
        val roundedLat = roundCoordinate(lat)
        val roundedLng = roundCoordinate(lng)
        return "$roundedLat,$roundedLng"
    }

    fun loadCache(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CACHE, null) ?: return
        
        val type = object : TypeToken<Map<String, String>>() {}.type
        memoryCache = Gson().fromJson(json, type)
        
        Log.d("LocationCache", "Loaded ${memoryCache.size} cached locations")
    }

    private fun saveCache(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = Gson().toJson(memoryCache)
        prefs.edit().putString(KEY_CACHE, json).apply()
    }

    suspend fun resolveLocation(
        context: Context,
        lat: Double,
        lng: Double
    ): String = withContext(Dispatchers.IO) {
        val key = getCacheKey(lat, lng)
        
        // Check memory cache first
        memoryCache[key]?.let {
            Log.d("LocationCache", "Cache HIT: $it")
            return@withContext it
        }

        // Try geocoding if network available
        val geocoder = Geocoder(context, Locale.getDefault())
        
        return@withContext try {
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]

                // Priority: area-level suburb first, then road, then city, then admin.
                // This avoids "Mangaluru → Mangaluru" when both points resolve to the same city.
                val raw = when {
                    address.subLocality  != null -> address.subLocality!!   // "Kankanady", "Hampankatta"
                    address.thoroughfare != null -> address.thoroughfare!!  // road name as fallback
                    address.locality     != null -> address.locality!!      // city — last clean option
                    address.subAdminArea != null -> address.subAdminArea!!
                    address.adminArea    != null -> address.adminArea!!
                    else                         -> null
                }

                // Trim anything after a comma, strip trailing whitespace, limit length
                val name = raw
                    ?.split(",")?.firstOrNull()?.trim()
                    ?.take(28)
                    ?: "Area ${lat.toInt()}°N"  // coordinate fallback: readable but not "Unknown"

                // Cache it
                memoryCache[key] = name
                saveCache(context)

                Log.d("LocationCache", "Geocoded: $name")
                name
            } else {
                val fallback = "Area ${lat.toInt()}°N"
                memoryCache[key] = fallback
                saveCache(context)
                fallback
            }
        } catch (e: Exception) {
            Log.e("LocationCache", "Geocoding failed", e)
            val fallback = "Area ${lat.toInt()}°N"
            memoryCache[key] = fallback
            saveCache(context)
            fallback
        }
    }
}

