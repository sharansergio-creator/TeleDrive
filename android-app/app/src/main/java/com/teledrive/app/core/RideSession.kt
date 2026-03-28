package com.teledrive.app.core

data class RideSession(
    val startTime: Long,
    var endTime: Long = 0L,

    var eventCount: Int = 0,
    var harshAccelerationCount: Int = 0,
    var harshBrakingCount: Int = 0,
    var unstableRideCount: Int = 0,

    var finalScore: Int = 100,

    var distanceKm: Float = 0f,
    var estimatedMileage: Float = 0f,
    var fuelUsed: Float = 0f,
    var lastEventImagePath: String? = null,

    // Fix: Added default value to prevent initialization error
    var rideDuration: Long = 0L
)

class RideSessionManager {
    companion object {
        var lastSession: RideSession? = null
        var lastEventImagePath: String? = null
    }

    private var currentSession: RideSession? = null

    private val historyList = mutableListOf<RideSession>()

    fun addToHistory(session: RideSession) {
        historyList.add(session)
    }

    fun getHistory(): List<RideSession> {
        return historyList
    }

    // Fix: Ensure distance is updated in the active session
    fun updateDistance(distance: Float) {
        currentSession?.let {
            it.distanceKm = distance
        }
    }

    fun setLastEventImagePath(path: String) {
        currentSession?.lastEventImagePath = path
        lastEventImagePath = path
    }

    fun startRide() {
        currentSession = RideSession(
            startTime = System.currentTimeMillis()
        )
    }

    fun processEvent(event: DrivingEvent) {
        val session = currentSession ?: return
        session.eventCount++

        when (event.type) {
            DrivingEventType.HARSH_ACCELERATION ->
                session.harshAccelerationCount++

            DrivingEventType.HARSH_BRAKING ->
                session.harshBrakingCount++

            DrivingEventType.UNSTABLE_RIDE ->
                session.unstableRideCount++

            DrivingEventType.NORMAL -> {}
        }
    }

    fun updateScore(score: Int) {
        currentSession?.finalScore = score
    }

    fun endRide(): RideSession? {
        val session = currentSession ?: return null

        // Final calculations
        session.endTime = System.currentTimeMillis()
        session.rideDuration = session.endTime - session.startTime

        // Calculate fuel using your FuelEstimator
        val fuelEstimator = FuelEstimator()

        // Calculate mileage (e.g., base 40 km/l - penalties)
        val mileage = fuelEstimator.estimateMileage(
            session.harshAccelerationCount,
            session.harshBrakingCount,
            session.unstableRideCount
        )
        session.estimatedMileage = mileage

        // Calculate fuel used: (Distance / Mileage)
        // If mileage is 0 (failed calc), use a safe default to avoid Infinity
        val safeMileage = if (mileage > 0) mileage else 40f
        session.fuelUsed = session.distanceKm / safeMileage

        lastSession = session
        currentSession = null // Reset for next ride
        return session
    }
}