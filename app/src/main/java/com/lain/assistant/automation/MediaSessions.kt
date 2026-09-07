package com.lain.assistant.automation

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * Driving the player that is already running, instead of launching it.
 *
 * "Play Burna Boy on Spotify" went out as `INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`,
 * which works — and brings Spotify to the front, which is the part the user
 * objected to. Setting an alarm doesn't leave Lain; starting a song shouldn't
 * either.
 *
 * A [MediaController] is the way out. Every player publishes a media session, and a
 * session accepts `playFromSearch`, `pause`, `skipToNext` and the rest without any
 * UI coming forward. Reading the list of active sessions needs notification access —
 * which Lain already asks for, and which the user grants on Android's own screen —
 * so this is not a new permission, and where it hasn't been granted the caller falls
 * back to the intent and says the app had to open.
 *
 * The limit is honest and worth stating: a session only exists once the player is
 * running. Spotify cold, with no session, cannot be driven from here — the app has
 * to be started, and starting it means it appears. Nothing here pretends otherwise.
 */
object MediaSessions {

    /** Whether sessions can be read at all — i.e. whether notification access is granted. */
    fun available(context: Context): Boolean =
        LainNotificationListener.isEnabledInSettings(context)

    /**
     * Every player currently holding a media session.
     *
     * Throws a SecurityException without notification access, which is caught rather
     * than checked first: the grant can be revoked between the check and the call.
     */
    private fun controllers(context: Context): List<MediaController> = runCatching {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        manager.getActiveSessions(ComponentName(context, LainNotificationListener::class.java))
    }.getOrDefault(emptyList())

    /**
     * The session for [packageName], or — with no package named — whichever session
     * is actually playing, falling back to the first one there is.
     */
    fun controllerFor(context: Context, packageName: String?): MediaController? {
        val all = controllers(context)
        if (all.isEmpty()) return null
        if (packageName != null) return all.firstOrNull { it.packageName == packageName }
        return all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.first()
    }

    /**
     * Asks a running player to find and play something.
     *
     * Returns false when the session refuses the action — some players only accept
     * `playFromSearch` from voice assistants they recognise — so the caller can fall
     * back rather than reporting a silence as a success.
     */
    fun playFromSearch(context: Context, packageName: String?, query: String): Boolean {
        val controller = controllerFor(context, packageName) ?: return false
        val supported = controller.playbackState?.actions ?: 0L
        if (supported and PlaybackState.ACTION_PLAY_FROM_SEARCH == 0L) return false
        return runCatching {
            controller.transportControls.playFromSearch(query, null)
            true
        }.getOrDefault(false)
    }

    /** Resumes a paused session in place. */
    fun play(context: Context, packageName: String? = null): Boolean = act(context, packageName) {
        it.transportControls.play()
    }

    fun pause(context: Context, packageName: String? = null): Boolean = act(context, packageName) {
        it.transportControls.pause()
    }

    fun next(context: Context, packageName: String? = null): Boolean = act(context, packageName) {
        it.transportControls.skipToNext()
    }

    fun previous(context: Context, packageName: String? = null): Boolean = act(context, packageName) {
        it.transportControls.skipToPrevious()
    }

    /** "Burna Boy — Last Last", when a session can say. */
    fun nowPlaying(context: Context, packageName: String? = null): String? {
        val controller = controllerFor(context, packageName) ?: return null
        val metadata = controller.metadata ?: return null
        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        return when {
            title.isNullOrBlank() -> null
            artist.isNullOrBlank() -> title
            else -> "$artist — $title"
        }
    }

    private inline fun act(
        context: Context,
        packageName: String?,
        action: (MediaController) -> Unit
    ): Boolean {
        val controller = controllerFor(context, packageName) ?: return false
        return runCatching { action(controller); true }.getOrDefault(false)
    }
}
