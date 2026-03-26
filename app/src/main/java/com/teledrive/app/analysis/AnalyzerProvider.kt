package com.teledrive.app.analysis

object AnalyzerProvider {

    var useML = false

    private val ruleAnalyzer by lazy { RuleBasedAnalyzer() }
    private val mlAnalyzer by lazy { MLAnalyzer() }

    fun getAnalyzer(): DrivingAnalyzer {
        return if (useML) mlAnalyzer else ruleAnalyzer
    }
}