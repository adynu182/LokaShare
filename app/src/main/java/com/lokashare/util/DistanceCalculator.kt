package com.lokashare.util

import android.location.Location

object DistanceCalculator {

    /**
     * Hitung jarak dua titik GPS (dalam meter)
     */
    fun distanceBetween(
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double
    ): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            startLatitude, startLongitude,
            endLatitude, endLongitude,
            results
        )
        return results[0]
    }
}
