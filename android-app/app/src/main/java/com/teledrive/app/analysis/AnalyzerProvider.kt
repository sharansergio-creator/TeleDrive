package com.teledrive.app.analysis

import android.content.Context
import android.util.Log
import com.teledrive.app.services.DetectionMode

object AnalyzerProvider {

    var useML = false

    // Cached analyzer instances keyed by class. Prevents a new MLAnalyzer from being
    // constructed on every `getAnalyzer()` call, which previously caused its internal
    // sensorWindowBuffer to reset to empty — making any buffer-dependent `analyze()`
    // call always return NORMAL.
    private var cachedRuleAnalyzer: RuleBasedAnalyzer? = null
    private var cachedMLAnalyzer: MLAnalyzer? = null

    fun setMode(mode: DetectionMode) {
        useML = mode != DetectionMode.RULE_BASED
        Log.i("MODE_DEBUG", "DetectionMode = $mode  useML=$useML")
    }

    fun getAnalyzer(context: Context): DrivingAnalyzer {
        return if (useML) {
            Log.i("MODE_DEBUG", "Using ML ANALYZER")
            cachedMLAnalyzer ?: MLAnalyzer(context).also { cachedMLAnalyzer = it }
        } else {
            Log.i("MODE_DEBUG", "Using RULE ANALYZER")
            cachedRuleAnalyzer ?: RuleBasedAnalyzer().also { cachedRuleAnalyzer = it }
        }
    }
}