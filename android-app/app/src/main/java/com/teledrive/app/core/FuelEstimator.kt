package com.teledrive.app.core

class FuelEstimator {

    private var baseMileage = 55f

    fun setBaseMileage(value: Float) {
        baseMileage = value
    }

    fun estimateMileage(
        harshAccel: Int,
        harshBrake: Int,
        instability: Int
    ): Float {

        val penalty =
            (harshAccel * 0.4f) +
                    (harshBrake * 0.3f) +
                    (instability * 0.5f)

        val result = baseMileage - penalty

        return result.coerceIn(30f, 70f)
    }

    fun estimateFuelUsed(distanceKm: Float, mileage: Float): Float {

        if (mileage <= 0f) return 0f

        return distanceKm / mileage
    }
}