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

Output: `app/build/outputs/apk/release/app-release.apk`

The build reads signing details from `keystore.properties` at the repo root, or
from `LAIN_KEYSTORE`, `LAIN_KEYSTORE_PASSWORD`, `LAIN_KEY_ALIAS`,
`LAIN_KEY_PASSWORD` in the environment. With neither present it still builds, just
unsigned — useful for checking size without holding the key.

## What ships

| | |
|---|---|
| Package | `com.lain.assistant` |
| Version | 1.2.1 (versionCode 22) |
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

Play also requires an App Bundle rather than an APK (`./gradlew bundleRelease`),
and Play App Signing takes over the upload key.

## What Lain cannot do, by design

Worth knowing before anyone reports these as bugs. Android forbids all of them
for normal apps, and Lain says so instead of pretending:

- Toggling Wi-Fi, Bluetooth, mobile data or aeroplane mode. She opens the real
  system panel over herself instead.
- Changing system dark mode (needs `WRITE_SECURE_SETTINGS`, adb only).
- Holding a phone call inside her own UI, or choosing a SIM for you.
- Force-quitting a foreground app.
