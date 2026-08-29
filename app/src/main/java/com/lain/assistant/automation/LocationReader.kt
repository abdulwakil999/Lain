package com.lain.assistant.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** @return a sentence, or null when it genuinely cannot be answered. */
    suspend fun describe(): String? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null

        val fix = lastKnownFix()
            ?: return@withContext "Location is on but the phone has no recent fix. " +
                "Open Maps for a moment and ask again."

        val age = System.currentTimeMillis() - fix.time
        val place = reverseGeocode(fix)
        val staleness = if (age > STALE_AFTER_MS) " (last fix ${age / 60_000} minutes ago)" else ""

        if (place != null) "$place$staleness."
        else "%.4f, %.4f$staleness.".format(fix.latitude, fix.longitude)
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
