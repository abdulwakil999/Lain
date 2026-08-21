package com.lain.assistant.automation

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent

/**
 * Playback control and "play X", without a language model in the loop.
 *
 * Two distinct mechanisms, because Android gives us two:
 *
 *  - **Transport controls** (play/pause/next/previous) go out as media key events,
 *    which the OS routes to whichever app currently owns the media session. That
 *    needs no permission, no knowledge of which player is running, and no network —
 *    so "pause" works in a tunnel, which is exactly when you want it.
 *  - **Starting something** uses the platform's play-from-search intent. Spotify,
 *    YouTube Music and the rest implement it precisely so an assistant can say
 *    "play this" without knowing anything about their catalogue or API.
 *
 * The alternative — asking a model to reason its way to opening an app, reading the
 * screen, finding the search box and tapping a result — is a dozen round trips to
 * do badly what one intent does correctly.
 */
class MediaController(private val context: Context) {

    /** Known players, by the words people actually say. */
    private val players = mapOf(
        "spotify" to "com.spotify.music",
        "youtube music" to "com.google.android.apps.youtube.music",
        "yt music" to "com.google.android.apps.youtube.music",
        "youtube" to "com.google.android.youtube",
        "soundcloud" to "com.soundcloud.android",
        "deezer" to "deezer.android.app",
        "tidal" to "com.aspiro.tidal",
        "apple music" to "com.apple.android.music",
        "audiomack" to "com.audiomack",
        "boomplay" to "com.afmobi.boomplayer"
    )

    fun packageFor(appName: String?): String? {
        if (appName.isNullOrBlank()) return null
        val key = appName.trim().lowercase()
        return players.entries.firstOrNull { key.contains(it.key) }?.value
    }

    // ------------------------------------------------------- transport

    /**
     * Sends a media key to whatever is holding the media session.
     *
     * `dispatchMediaKeyEvent` needs a down and an up event to register as a press;
     * sending only the down is a common way for this to silently do nothing.
     */
    private fun sendKey(keyCode: Int): Boolean = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        true
    }.getOrDefault(false)

    fun playPause(): Boolean = sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun play(): Boolean = sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    fun pause(): Boolean = sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun next(): Boolean = sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous(): Boolean = sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun stop(): Boolean = sendKey(KeyEvent.KEYCODE_MEDIA_STOP)

    /** True when something is actually playing, so a reply can be accurate rather than hopeful. */
    fun isPlaying(): Boolean = runCatching {
        context.getSystemService(AudioManager::class.java)?.isMusicActive == true
    }.getOrDefault(false)

    // ----------------------------------------------------- starting playback

    /**
     * Asks a player to start something.
     *
     * @param query what to play — a track, artist, album or playlist. Blank means
     *   "just play something", which the platform treats as an unstructured request
     *   and players interpret as resume/shuffle.
     * @param preferredPackage a specific player, or null to let Android pick the
     *   user's default.
     * @return true if a player accepted the intent.
     */
    fun playFromSearch(query: String, preferredPackage: String?): Boolean {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SearchManager.QUERY, query)
            // "Unstructured" tells the player to interpret the string itself rather
            // than expecting artist/album/track to be broken out separately.
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            preferredPackage?.let { setPackage(it) }
        }

        if (start(intent)) return true

        // The named player may not implement the intent (or may not be installed).
        // Retrying unpackaged lets the user's default music app take it, which is a
        // better outcome than reporting failure.
        if (preferredPackage != null && start(Intent(intent).apply { setPackage(null) })) return true

        // Last resort for Spotify specifically: it honours its own search URI even on
        // builds where the media intent is missing.
        if (preferredPackage == "com.spotify.music" && query.isNotBlank()) {
            return start(
                Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return false
    }

    /** Opens a player without starting anything specific. */
    fun openPlayer(packageName: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return start(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun start(intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
