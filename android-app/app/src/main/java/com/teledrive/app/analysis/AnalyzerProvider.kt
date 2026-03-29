package com.teledrive.app.analysis

import android.content.Context
import android.util.Log

object AnalyzerProvider {

    var useML = false

    fun getAnalyzer(context: Context): DrivingAnalyzer {
        return if (useML) {
            Log.i("MODE_DEBUG", "Using ML ANALYZER")
            MLAnalyzer(context)
        } else {
            Log.i("MODE_DEBUG", "Using RULE ANALYZER")
            RuleBasedAnalyzer()
        }
    }
}