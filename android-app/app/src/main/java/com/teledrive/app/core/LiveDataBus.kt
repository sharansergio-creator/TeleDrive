package com.teledrive.app.core

object LiveDataBus {
    /** (eventLabel, speedKmH, stdAccel, tip, confidence, sourceName) */
    var listener: ((String, Float, Float, String?, Float, String) -> Unit)? = null
}