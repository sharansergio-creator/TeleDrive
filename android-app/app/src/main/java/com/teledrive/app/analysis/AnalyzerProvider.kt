package com.teledrive.app.analysis

import android.util.Log

object AnalyzerProvider {

    var useML = false

    private val ruleAnalyzer by lazy { RuleBasedAnalyzer() }
    private val mlAnalyzer by lazy { MLAnalyzer() }

    fun getAnalyzer(): DrivingAnalyzer {

        if (useML) {
            Log.i("MODE_DEBUG", "Using ML ANALYZER")
            return mlAnalyzer
        } else {
            Log.i("MODE_DEBUG", "Using RULE ANALYZER")
            return ruleAnalyzer
        }
    }
}