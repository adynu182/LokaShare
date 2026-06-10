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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import kotlin.coroutines.resume

class LocationRepository(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    companion object {
        // Timeout untuk GPS ketat (percobaan 1): diberi waktu lebih lama
        // karena GPS cold-start memang butuh waktu acquire satelit
        private const val GPS_STRICT_TIMEOUT_MS = 15_000L   // 15 detik

        // Timeout untuk Fused (percobaan 2–5): lebih singkat karena
        // fused bisa langsung pakai Cell/WiFi jika GPS belum siap
        private const val FUSED_TIMEOUT_MS = 10_000L        // 10 detik
    }

    /**
     * Ambil lokasi via FusedLocationProvider (balanced accuracy).
     * Dipakai untuk percobaan 2–5 di checkAndSend().
     * Dilindungi timeout [FUSED_TIMEOUT_MS] — tidak akan hang.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Location? {
        return try {
            withTimeout(FUSED_TIMEOUT_MS) {
                val cts = CancellationTokenSource()
                val location = fusedClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    cts.token
                ).await()
                location
            }
        } catch (e: TimeoutCancellationException) {
            Timber.w("Fused location timeout setelah ${FUSED_TIMEOUT_MS}ms — kembalikan null")
            null
        } catch (e: Exception) {
            Timber.e(e, "Gagal mengambil lokasi dari Fused Location")
            null
        }
    }

    /**
     * Ambil lokasi langsung dari GPS hardware (bukan fused).
     * Dipakai untuk percobaan 1 (GPS ketat) di checkAndSend().
     *
     * Dilindungi [GPS_STRICT_TIMEOUT_MS]:
     *   - Jika GPS tidak dapat fix dalam 15 detik → kembalikan null
     *   - invokeOnCancellation memastikan listener selalu di-unregister
     *     baik karena timeout, cancel coroutine, maupun exception
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocationFromGps(): Location? = withContext(Dispatchers.IO) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null

        // Cek dulu apakah GPS provider tersedia dan aktif
        val isGpsEnabled = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }

        if (!isGpsEnabled) {
            Timber.w("GPS provider tidak aktif — skip percobaan GPS ketat")
            return@withContext null
        }

        try {
            withTimeout(GPS_STRICT_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            locationManager.removeUpdates(this)
                            if (continuation.isActive) {
                                Timber.d("GPS fix diterima: akurasi=${location.accuracy}m")
                                continuation.resume(location)
                            }
                        }

                        override fun onProviderDisabled(provider: String) {
                            // GPS dimatikan user saat kita sedang tunggu
                            Timber.w("GPS dinonaktifkan saat menunggu fix")
                            locationManager.removeUpdates(this)
                            if (continuation.isActive) continuation.resume(null)
                        }

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
                        Timber.e(e, "Gagal mendaftarkan GPS listener")
                        if (continuation.isActive) continuation.resume(null)
                    }

                    // Cleanup wajib: listener di-remove apapun yang terjadi
                    // (timeout, cancel dari luar, atau exception di coroutine induk)
                    continuation.invokeOnCancellation {
                        try {
                            locationManager.removeUpdates(listener)
                            Timber.d("GPS listener di-unregister karena coroutine dibatalkan/timeout")
                        } catch (_: Exception) { }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Timber.w("GPS fix timeout setelah ${GPS_STRICT_TIMEOUT_MS}ms — tidak ada sinyal GPS cukup kuat")
            null
        } catch (e: Exception) {
            Timber.e(e, "Error saat menunggu GPS fix")
            null
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
