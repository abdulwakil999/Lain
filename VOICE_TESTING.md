# Voice system — test checklist

The parts of the voice pipeline that can be checked without a phone are in
`VoiceSystemTest`, `LainNameTest` and `ScreenAndSpeechTest`. Everything below needs
real hardware, because it is about the microphone, the synthesiser and the OS.

Turn hands-free on first: **Settings → Hands-free → On**, and grant the microphone
when asked. A persistent notification appears while it is listening; that
notification is required by Android and is also the honest signal that the mic is
open.

| # | Test | Expected |
|---|------|----------|
| 1 | Say "Lain" with the app open | Mini surface opens, state shows **Listening…** |
| 2 | Say "hey Lain", "hi Lain", "sup Lain", "yo Lain" | All wake her — the wake word is the **name**, not a fixed phrase |
| 3 | Say "Lain, open WhatsApp" as one sentence | She acts on it without asking you to repeat the command |
| 4 | Say "Lain" with the app minimised | Wakes and opens the mini surface over whatever is on screen |
| 5 | Screen off | Detection **pauses** by design (see *Known limitations*). Screen on resumes it |
| 6 | Speak a command after waking | State goes Listening… → Thinking… → Working… → Speaking… |
| 7 | Lain speaks the reply | Audible, and the same reply appears in the transcript |
| 8 | Immediately say another command | Second turn runs; she returns to wake listening afterwards |
| 9 | Say "Lain" while she is speaking | Speech **stops mid-sentence**, she starts listening for the new command |
| 10 | Tap the mic button while she is speaking | Same barge-in, from touch |
| 11 | Deny the microphone permission at the prompt | Hands-free stays **Off**; nothing pretends to be listening |
| 12 | Revoke the microphone in Android Settings while running | State becomes **Voice unavailable** with a reason; no crash, no silent failure |
| 13 | Re-grant the microphone | Detection resumes on the next screen-on without reinstalling |
| 14 | Turn off Wi-Fi and mobile data | Wake still works on Android 13+ (on-device recogniser). Commands report the offline line instead of hanging |
| 15 | Disable / uninstall the TTS engine | Reply is shown in text; she doesn't hang in **Speaking…** |
| 16 | Start a voice recording in another app | Detection stands down; comes back a second or two after that app stops |
| 17 | Take a phone call | Same — the mic is released, and returns afterwards |
| 18 | Connect a Bluetooth headset, then disconnect it mid-listen | Detection restarts on the new route rather than holding a dead recorder |
| 19 | Rotate the phone during a turn | Turn continues; the engine is application-scoped, not tied to the activity |
| 20 | Force-stop, relaunch | Hands-free comes back on if it was on; watchdog leaves no stuck state |
| 21 | Reboot | Same — the switch survives |
| 22 | Five consecutive interactions | All five work; she returns to wake listening after each |
| 23 | Noisy room (music, TV, traffic) | Noise floor adapts; stage two runs sometimes but does **not** wake her without the name |
| 24 | Talk near the phone for a minute without saying her name | No wake. Nothing is sent anywhere |
| 25 | Reply containing the word "Lain" | She does **not** wake herself up (echo cancellation + spoken-text check) |
| 26 | Listen to any reply | She pronounces her name **"Line"** |
| 27 | Look at any screen | The UI always shows **"Lain"** |
| 28 | Speak "open Settings and turn on Bluetooth", then type the same thing | Identical behaviour — one pipeline, not two |
| 29 | Leave hands-free on for an hour idle | One steady mic stream. The privacy indicator stays **lit and steady** — it must not flicker on and off |
| 29b | Sit near a TV or a conversation for ten minutes | Indicator still steady. Checks get rarer as the refractory period widens, not more frequent |
| 30 | Say her name, then say nothing | Times out to **Say Lain** rather than sitting on Listening… |

## What "stuck" looks like, and why it shouldn't happen

Every transient state has a ceiling in `WakeWordManager`: wake-detected 5s, listening
20s, thinking 90s, working 3min, speaking 2min. When one expires the microphone is
released, the reason is recorded, and the service returns to wake listening. If you
ever see **Listening…**, **Thinking…** or **Speaking…** outlive those numbers, that
is a bug worth reporting with the state name.

## Why the microphone stays on

It is held open in **one continuous stream** and not cycled. Opening and closing a
recorder costs an audio HAL round trip, a route reconfiguration and two AudioEffect
allocations every time — doing that on every candidate utterance is far more
expensive than simply holding the stream, and it is what made the privacy indicator
flicker. A steady 16 kHz mono capture reduced to one RMS number per 20 ms frame is
cheap; the churn was not.

On Android 13+ checking a candidate does not touch the microphone at all: the
recogniser is handed a pipe (`RecognizerIntent.EXTRA_AUDIO_SOURCE`) carrying audio
already captured, so there is no handover. Below 13 no such API exists, so a check
does release and retake the mic — which is why the gate only accepts short,
completed utterances and widens a refractory period after each miss.

## Known Android limitations

- **No DSP hotword.** `AlwaysOnHotwordDetector` and `HotwordDetectionService` — what
  the built-in assistants use to listen at near-zero power — are reserved for
  whichever app holds the system voice-interaction role, and accept only the
  keyphrases burned into the OEM's hardware model. "Lain" is not one of them. This is
  the closest an ordinary app can get.
- **Screen off pauses detection.** An open microphone holds the audio path awake, so
  listening through the night flattens the battery in exchange for hearing nothing.
  The tile, the widgets and the bubble all reach Lain in one tap instead.
- **There is no bundled keyword model.** Stage one is energy detection and stage two
  is the platform recogniser. A trained spotter (Porcupine and friends) needs either a
  licensed key the user must obtain or tens of megabytes of acoustic model. What this
  costs is a little accuracy across a large room.
- **On-device recognition needs Android 13+** and a downloaded language pack. Below
  that, stage two uses the default recogniser, which on many phones is a network call
  — so on older devices the *wake check* can leave the phone. Stage one never does.
- **Foreground-service starts are restricted** from Android 12. Starting hands-free
  from the background can be refused; the service degrades to off rather than crashing.
- **`EXTRA_SPEECH_INPUT_*` timeouts are hints.** Several OEM recognisers ignore them,
  which is why `VoiceInputController` carries its own hard ceiling.
