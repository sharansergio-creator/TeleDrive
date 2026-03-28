package com.teledrive.app.core

object LiveDataBus {
    var listener: ((String, Float, Float,String?) -> Unit)? = null
}