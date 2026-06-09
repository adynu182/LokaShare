package com.lokashare.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume

class LocationRepository(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Location? {
        return try {
            val cts = CancellationTokenSource()
            val location = fusedClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cts.token
            ).await()
            location
        } catch (e: Exception) {
            Timber.e(e, "Gagal mengambil lokasi dari Fused Location")
            null
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocationFromGps(): Location? = withContext(Dispatchers.IO) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null

        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }

                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            try {
                locationManager.requestSingleUpdate(
                    LocationManager.GPS_PROVIDER,
                    listener,
                    Looper.getMainLooper()
                )
            } catch (e: Exception) {
                Timber.e(e, "Gagal mengambil lokasi dari GPS provider")
                continuation.resume(null)
            }

            continuation.invokeOnCancellation {
                try {
                    locationManager.removeUpdates(listener)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun meetsStrictCriteria(location: Location): Boolean {
        val ageMs = System.currentTimeMillis() - location.time
        val ageSeconds = ageMs / 1000L
        val providerMatches = location.provider?.equals(LocationManager.GPS_PROVIDER, ignoreCase = true) == true
        val satelliteCount = location.extras?.getInt("satellites", -1) ?: -1
        val speedJumpNormal = location.speed <= 15f

        return location.accuracy < 10f &&
            ageSeconds < 10L &&
            providerMatches &&
            satelliteCount > 8 &&
            speedJumpNormal
    }
}
