package com.teledrive.app.TripHistory

data class TripSummary(
    val score: Int,
    val distance: Float,
    val duration: Long,
    val harshAccel: Int,
    val harshBrake: Int,
    val instability: Int,
    val timestamp: Long
)