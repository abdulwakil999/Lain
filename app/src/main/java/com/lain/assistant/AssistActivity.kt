package com.lain.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * The door Android's assistant gesture knocks on.
 *
 * Holding the power button, swiping in from a bottom corner, or squeezing the frame
 * all send `ACTION_ASSIST` to whichever app the user picked as their assistant. An
 * app cannot make itself that app — the choice is the user's, in Settings — but it
 * has to be selectable, and it is only selectable if something of its own answers
 * that intent. Nothing did, so Lain never appeared in the picker at all.
 *
 * A separate activity rather than an intent-filter on [MiniActivity], because the
 * filter has to be exported and [MiniActivity] should not be. Anything on the phone
 * can start this one; all it can do is open Lain listening, which is exactly what
 * the gesture is for and no more than a widget tap already allows.
 *
 * It draws nothing and finishes immediately, so the assist gesture goes straight to
 * the compact surface rather than through a screen that flashes and disappears.
 */
class AssistActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Deliberately drops every extra the system attached. ACTION_ASSIST can carry
        // a screenshot and a structured dump of whatever app was in front — Android
        // offers it, Lain does not take it. She reads the screen when asked to and
        // through a permission the user granted for it, not as a side effect of a
        // gesture they used to summon her.
        startActivity(
            Intent(this, MiniActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MiniActivity.EXTRA_AUTO_LISTEN, true)
                data = android.net.Uri.parse("lain://assist/${System.currentTimeMillis()}")
            }
        )
        finish()
        // No transition of its own; the surface it opened does the animating.
        overridePendingTransition(0, 0)
    }
}
