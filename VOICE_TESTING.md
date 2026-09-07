# Voice system — test checklist

The parts of the voice pipeline that can be checked without a phone are in
`VoiceSystemTest`, `LainNameTest` and `ScreenAndSpeechTest`. Everything below needs
real hardware, because it is about the microphone, the synthesiser and the OS.

Voice is **push to talk**: the mic button in the chat bar, the mic widget, the
Quick Settings tile, or the floating bubble. Grant the microphone when asked. Lain
does not listen in the background and never holds the microphone between turns —
there is no wake word (see *Why there is no wake word*).

| # | Test | Expected |
|---|------|----------|
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

## Why there is no wake word

Always-on hotword listening was removed. What an ordinary Android app can build is a
microphone held open around the clock plus a general-purpose recogniser checking
candidate utterances — which costs battery, keeps the privacy indicator lit, and is
still less accurate than the built-in assistants, because the thing that makes those
cheap and reliable is not available:

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

The app no longer requests `FOREGROUND_SERVICE_MICROPHONE` and declares no service
that can hold the microphone outside the foreground. `RECORD_AUDIO` is still needed
and is used only while a turn you started is running.

## Known Android limitations

- **On-device recognition needs Android 13+** and a downloaded language pack. Below
  that the default recogniser is used, which on many phones is a network call.
- **`EXTRA_SPEECH_INPUT_*` timeouts are hints.** Several OEM recognisers ignore them,
  which is why `VoiceInputController` carries its own hard ceiling.
- **Recognisers have a fixed vocabulary.** They have never heard of Lain, Claude,
  DeepSeek or Qwen and return the nearest English word. `LainName` and `Misheard`
  put those back before the transcript reaches the model, and both refuse to touch a
  word whose ordinary meaning fits the sentence.
