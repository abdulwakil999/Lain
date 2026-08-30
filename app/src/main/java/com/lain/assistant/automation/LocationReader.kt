package com.lain.assistant.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Where the phone is, answered by the phone.
 *
 * Uses the last known fix rather than requesting a new one. A fresh GPS fix costs
 * seconds and a visible battery hit, and "where am I" almost never needs
 * street-level precision on a device that has been carried here. When the cached
 * fix is genuinely stale it says how old it is instead of presenting it as current.
 *
 * Reverse geocoding is the platform's, which is on-device on most builds. Nothing
 * about the location is sent anywhere by Lain.
 */
class LocationReader(private val context: Context) {

    companion object {
        /** Beyond this, a cached fix is reported with its age rather than as "here". */
        private const val STALE_AFTER_MS = 30 * 60 * 1000L

        /**
         * How long to wait for a new fix before saying one didn't arrive.
         *
         * Short, because someone is watching. A coarse network fix normally lands in
         * a second or two; anything past this is a phone that isn't going to get one
         * quickly, and saying so beats making them wait to find out.
         */
        private const val FIX_TIMEOUT_MS = 8_000L
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** @return a sentence, or null when it genuinely cannot be answered. */
    suspend fun describe(): String? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null

        // A cached fix answers this instantly and costs nothing, which is why it is
        // tried first. When there isn't one — a phone that has been indoors all day,
        // or freshly rebooted — the old code gave up and told the user to go and open
        // Maps, which is asking them to do the one thing they asked her to do.
        val fix = lastKnownFix() ?: freshFix()
            ?: return@withContext "Location's on but the phone has no fix and one didn't " +
                "arrive in time. Usually means no clear sky or no signal — try again by a window."

        val age = System.currentTimeMillis() - fix.time
        val place = reverseGeocode(fix)
        val staleness = if (age > STALE_AFTER_MS) " (last fix ${age / 60_000} minutes ago)" else ""

        if (place != null) "$place$staleness."
        else "%.4f, %.4f$staleness.".format(fix.latitude, fix.longitude)
    }

    /**
     * Asks for a single new fix, and gives up quickly if one doesn't come.
     *
     * Deliberately one fix and not a subscription: a running location request is a
     * battery cost that keeps being paid, and "where am I" is a question asked once.
     * Coarse accuracy is enough for the answer this gives — an area name — and it
     * resolves far faster than a GPS lock, which matters because the user is
     * watching a spinner while it happens.
     *
     * The timeout is what stops a phone with no signal hanging the turn forever. A
     * null return is a real answer here: it means no fix arrived, which is what she
     * then says.
     */
    private suspend fun freshFix(): Location? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null

        return runCatching {
            withTimeoutOrNull(FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val listener = LocationListener { location ->
                        if (continuation.isActive) continuation.resume(location) { _, _, _ -> }
                    }
                    val provider = when {
                        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                            LocationManager.NETWORK_PROVIDER
                        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                            LocationManager.GPS_PROVIDER
                        else -> null
                    }
                    if (provider == null) {
                        if (continuation.isActive) continuation.resume(null) { _, _, _ -> }
                        return@suspendCancellableCoroutine
                    }

                    @Suppress("MissingPermission")
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                    // Removed on timeout and on cancellation alike, so a request never
                    // outlives the question that started it.
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                }
            }
        }.getOrNull()
    }

    /** Raw coordinates, for anything that needs them rather than a description. */
    fun coordinates(): Pair<Double, Double>? =
        lastKnownFix()?.let { it.latitude to it.longitude }

    /**
     * The best recent fix across providers.
     *
     * Providers disagree and any of them may be null, so this takes the newest of
     * whatever exists rather than trusting one.
     */
    private fun lastKnownFix(): Location? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return runCatching {
            @Suppress("MissingPermission")
            manager.allProviders
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    private fun reverseGeocode(fix: Location): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        @Suppress("DEPRECATION")
        val results = Geocoder(context, Locale.getDefault())
            .getFromLocation(fix.latitude, fix.longitude, 1)
        val address = results?.firstOrNull() ?: return null
        listOfNotNull(
            address.subLocality ?: address.locality,
            address.locality.takeIf { it != null && it != address.subLocality },
            address.adminArea,
            address.countryName
        ).distinct().joinToString(", ").ifBlank { null }
    }.getOrNull()
}
