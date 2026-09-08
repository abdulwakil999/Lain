# Voice system — test checklist

The parts of the voice pipeline that can be checked without a phone are in
`VoiceSystemTest`, `LainNameTest` and `ScreenAndSpeechTest`. Everything below needs
real hardware, because it is about the microphone, the synthesiser and the OS.

Two ways in. **Push to talk**: the mic button in the chat bar, the mic widget, the
Quick Settings tile, or the floating bubble. **Hands-free**: turn it on at
**Settings → Hands-free → On** and say her name — "Lain", "hello Lain", "hey Lain".
Grant the microphone when asked.

While hands-free is on the microphone is held open in one continuous stream and is
never opened and closed again until you switch it off. That is deliberate, and the
notification says so: nothing is recorded, saved or sent, and until she hears her
name the audio is measured for loudness and thrown away without ever being turned
into words.

| # | Test | Expected |
|---|------|----------|
| 0 | Turn hands-free on, then watch the mic indicator for ten minutes | **Lit and steady.** It must not blink off and on — one open stream, not one per sound |
| 0b | Say "Lain", "hello Lain", "hey Lain", "sup Lain" | Each wakes her |
| 0c | Say "Lain, open WhatsApp" as one sentence | She acts on it without asking you to repeat the command |
| 0d | Watch the indicator through a whole hands-free turn — name, command, reply | **Still steady.** On Android 13+ the command is recognised from the same open stream, so the mic never changes hands |
| 0e | Say "Lain" while she is speaking | She stops mid-sentence and listens for the new command |
| 0f | A reply that contains the word "Lain" | She does **not** wake herself |
| 0g | Talk near the phone for a minute without saying her name | No wake, nothing sent, indicator unchanged |
| 0h | Read the hands-free notification | Says plainly that nothing is recorded or sent and that she is only listening for her name |
| 1 | Tap the mic button | State shows **Listening…**, the privacy indicator lights |
| 2 | Say a command | State goes Listening… → Thinking… → Working… → Speaking… |
| 3 | Say "Lain, open WhatsApp" — the address included | She opens WhatsApp. The name is stripped, not sent as part of the request |
| 4 | Tap the mic widget on the home screen | Mini surface opens over whatever is on screen, already listening |
| 5 | Tap the Quick Settings tile | Same |
| 6 | Lain speaks the reply | Audible, and the same reply appears in the transcript |
| 7 | When the turn ends | Microphone released, indicator **off**, state back to **Tap to talk** |
| 8 | Tap the mic again straight away | Second turn runs — one tap is one command |
| 9 | Turn **HANDS-FREE** on in the chat bar, then speak | She listens again on her own after each reply, until you turn it off or stay quiet |
| 10 | Tap the mic button while she is speaking | Speech **stops mid-sentence** and she listens for the new command |
| 11 | Deny the microphone permission at the prompt | Nothing pretends to be listening; the reason is on screen |
| 12 | Revoke the microphone in Android Settings mid-turn | State becomes **Voice unavailable** with a reason; no crash, no silent failure |
| 13 | Turn off Wi-Fi and mobile data, then talk | Recognition still works on Android 13+ (on-device). The reply reports the offline line instead of hanging |
| 14 | Disable / uninstall the TTS engine | Reply is shown in text; she doesn't hang in **Speaking…** |
| 15 | Take a phone call mid-turn | The mic is released; no stuck state afterwards |
| 16 | Rotate the phone during a turn | Turn continues; the engine is application-scoped, not tied to the activity |
| 17 | Tap the mic and say nothing | Times out to **Tap to talk** rather than sitting on Listening… |
| 18 | Ask for something that needs confirmation, answer "yes" out loud | It runs, and the microphone is released afterwards like any other turn |
| 19 | Cause a failure (no API key set), by voice | Error is shown **and** the microphone is released — she is not deaf afterwards |
| 20 | Five consecutive spoken turns, including a failed one | All five work; the indicator goes out between each |
| 21 | Speak "open Settings and turn on Bluetooth", then type the same thing | Identical behaviour — one pipeline, not two |
| 22 | Listen to any reply that says her name | She pronounces it **"Lane"**, to rhyme with rain — the same every time, including "Lain's" and a shouted "LAIN!" |
| 23 | Look at any screen | The UI always shows **"Lain"** |
| 24 | Say "switch to Claude" | Heard as **Claude**, not "cloud" |
| 25 | Say "use DeepSeek", "ask ChatGPT", "open WhatsApp", "play the Qur'an" | Each name comes through spelled correctly |
| 26 | Say "upload the photos to the cloud" | Left **exactly** as spoken — the correction must not rewrite ordinary sentences |
| 27 | Say "hello Lain" with nothing after it | Treated as a greeting, not a truncated command |

## What "stuck" looks like, and why it shouldn't happen

Every transient state has a ceiling in `VoiceSession`: listening 20s, thinking 90s,
working 3min, speaking 2min. When one expires the microphone is released, the reason
is recorded, and voice goes idle. If you ever see **Listening…**, **Thinking…** or
**Speaking…** outlive those numbers, that is a bug worth reporting with the state
name.

Every voice turn ends by handing the microphone back — on success, on failure, on a
thrown request, on a cancel, and on a spoken yes/no to a confirmation. Row 19 is
that guarantee: the failure case is the one that used to leave her deaf.

## Why the microphone stays open

Because the alternative is worse. Opening and closing a recorder costs an audio HAL
round trip, a route reconfiguration and two AudioEffect allocations every time —
doing that on every sound in the room is more expensive than simply holding the
stream, and it is exactly what a blinking privacy indicator looks like from outside.
A steady 16 kHz mono capture reduced to one RMS number per 20 ms frame is cheap; the
churn was not.

On Android 13+ the microphone is not handed over even for the command:
`RecognizerIntent.EXTRA_AUDIO_SOURCE` lets the recogniser read a pipe, so the frames
already being captured are written to it. Below 13 there is no such API and the
recorder genuinely has to be released for the recogniser, once per turn.

What is *not* available to an ordinary app, and why this is not a DSP hotword:

- **No DSP hotword.** `AlwaysOnHotwordDetector` and `HotwordDetectionService` — what
  the built-in assistants use to listen at near-zero power — are reserved for
  whichever app holds the system voice-interaction role, and accept only the
  keyphrases burned into the OEM's hardware model. "Lain" is not one of them.
- **There is no bundled keyword model.** A trained spotter (Porcupine and friends)
  needs either a licensed key the user must obtain or tens of megabytes of acoustic
  model.
- **Screen off would have to pause it anyway.** An open microphone holds the audio
  path awake, so listening through the night flattens the battery in exchange for
  hearing nothing.

Holding the microphone outside the foreground requires `FOREGROUND_SERVICE_MICROPHONE`
and a foreground service with `foregroundServiceType="microphone"`, which is why the
ongoing notification exists — Android requires it, and it is also the honest signal,
because while hands-free is on the microphone genuinely is open.

## Known Android limitations

- **On-device recognition needs Android 13+** and a downloaded language pack. Below
  that the default recogniser is used, which on many phones is a network call.
- **`EXTRA_SPEECH_INPUT_*` timeouts are hints.** Several OEM recognisers ignore them,
  which is why `VoiceInputController` carries its own hard ceiling.
- **Recognisers have a fixed vocabulary.** They have never heard of Lain, Claude,
  DeepSeek or Qwen and return the nearest English word. `LainName` and `Misheard`
  put those back before the transcript reaches the model, and both refuse to touch a
  word whose ordinary meaning fits the sentence.
