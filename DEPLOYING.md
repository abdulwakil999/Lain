# Deploying Lain

## The signing key — read this first

`lain-release.jks` signs the app. Android identifies an app by its signature, so:

- **Every future update must be signed with this same key**, or phones will
  refuse to install it as an update. The only way out is uninstalling, which
  wipes conversations, memories and the API key.
- The key is **not in the repository** (`.gitignore` covers `*.jks` and
  `keystore.properties`). Back it up somewhere you will still have in five years.

| | |
|---|---|
| Keystore | `lain-release.jks` |
| Alias | `lain` |
| Store / key password | `LainRelease2026` |
| Algorithm | RSA 4096, SHA384withRSA |
| Valid until | 2056 |
| SHA-256 | `e402efce3106603c665d15bbae4028912f7e9a2b5c55de476852cd8bcc3b2e1f` |

Change the password before wider distribution: regenerate with `keytool`, update
`keystore.properties`, and note that doing so breaks updates for anyone already
running a build signed with the old key.

## Building

```
./gradlew clean testDebugUnitTest lintDebug assembleRelease
```

Outputs:
- `app/build/outputs/apk/release/app-release.apk` — sideload
- `app/build/outputs/bundle/release/app-release.aab` — Play Store

Both are signed with the same key. Play re-signs the bundle with its own key once
App Signing is enabled, so the SHA-256 below applies to the APK you distribute
yourself, not to what Play serves.

The build reads signing details from `keystore.properties` at the repo root, or
from `LAIN_KEYSTORE`, `LAIN_KEYSTORE_PASSWORD`, `LAIN_KEY_ALIAS`,
`LAIN_KEY_PASSWORD` in the environment. With neither present it still builds, just
unsigned — useful for checking size without holding the key.

## What ships

| | |
|---|---|
| Package | `com.lain.assistant` |
| Version | 1.9.0 (versionCode 31) |
| Size | 4.9 MB |
| Min / target | Android 8.0 (26) / Android 15 (35) |
| Signature | v2 |

Debug builds install alongside release ones as `com.lain.assistant.debug`.

## Installing

Sideload: transfer the APK, tap it, allow the installer to install unknown apps.
Or `adb install -r app-release.apk`.

## First run

1. Onboarding asks for name, provider, model and API key.
2. Runtime permissions: microphone, camera, phone, SMS, contacts, notifications.
3. **On Android 13+, in this order** — App Info → ⋮ → "Allow restricted
   settings", *then* Accessibility → Lain → on. The second is greyed out until
   the first is done; onboarding now walks through both as numbered steps.
4. Optional, in Settings: exact alarms, Do Not Disturb access, notification
   access, battery-optimisation exemption.

## Verifying a build

```
apksigner verify --print-certs app-release.apk
aapt2 dump badging app-release.apk | head -3
```

The SHA-256 should match the table above. If it doesn't, it was signed with a
different key and will not install over an existing copy.

## Release checklist

- [ ] `versionCode` and `versionName` both incremented — every build, no exceptions
- [ ] `./gradlew clean testDebugUnitTest lintDebug` clean
- [ ] Release APK signed with `lain-release.jks`, SHA-256 matches
- [ ] Installs *over* the previous version without an uninstall
- [ ] Accessibility still connects (Settings → Show diagnostic log)
- [ ] A widget still binds after install — R8 keep rules cover the providers,
      and a stripped one fails silently rather than crashing

## If you publish to Play

Two things need a policy declaration:

- `USE_EXACT_ALARM` — allowed for alarm and calendar apps. Lain sets alarms that
  ring, snooze and repeat, which is the qualifying use.
- `AccessibilityService` — must be declared as accessibility functionality. Lain
  genuinely is: screen reading and hands-free control for blind users is a core
  feature, not a workaround for automation.

Play requires the App Bundle, which `assembleRelease bundleRelease` already
produces. Play App Signing takes over from the upload key once enabled — keep
`lain-release.jks` anyway, since it stays the upload key.

The privacy policy and terms are built into the app and shown before setup asks
for anything. Play also wants a publicly reachable privacy policy URL; host the
same text from `LegalText.PRIVACY` and point the listing at it, so the two cannot
drift apart.

## What Lain cannot do, by design

Worth knowing before anyone reports these as bugs. Android forbids all of them
for normal apps, and Lain says so instead of pretending:

- Toggling Wi-Fi, Bluetooth, mobile data or aeroplane mode *programmatically*.
  With the Accessibility Service connected she presses the Quick Settings tile
  instead — the user's own service, the user's own tile — and reads the state back
  to confirm. Without the service, she opens the system panel and says why.
- Changing system dark mode (needs `WRITE_SECURE_SETTINGS`, adb only).
- Holding a phone call inside her own UI, or choosing a SIM for you.
- Force-quitting a foreground app.
