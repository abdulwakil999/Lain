# Lain — working notes

Android AI assistant. `com.lain.assistant`, Kotlin + Compose, minSdk 26 / target 35.

## Versioning — always bump

**Every build gets a new `versionCode` and `versionName`, without exception.**

This holds even when asked to keep the version the same. Reusing a version is not
a neutral choice:

- Android identifies a build by `versionCode`. Two different APKs sharing one make
  it impossible to tell from a device which is installed, and some installers
  refuse to overwrite same-version packages outright.
- A bug report against "1.2.0" is useless when three different 1.2.0s exist.

Bump the patch digit for a fix, the minor for new capability. Both fields in
`app/build.gradle.kts`, and the version table in `DEPLOYING.md` alongside them.

## The rule that matters most

Never report an action as done unless it was confirmed. Most of this project's real
bugs have been the same shape: a call that connected being reported as failed, an
alarm silently not firing, a scheduled text that never went. Prefer "I can't
verify" to a confident guess in both directions.

Related: never bypass Android's security model, never silently grant a permission,
and where the platform genuinely forbids something, say so plainly rather than
faking it. `QuickToggles` documents the ones that come up.

## Build

```
./gradlew clean testDebugUnitTest lintDebug assembleRelease
```

Signing comes from `keystore.properties` (git-ignored) or `LAIN_*` environment
variables. Lint must stay at zero errors. See `DEPLOYING.md` for the rest,
including the signing key warning.

## Shape of the code

- `agent/` — routing, prompt, the tool loop, working memory. `FastRouter` answers
  what Android can answer without a model at all; that path is the reason the app
  feels quick on free models.
- `automation/` — everything that touches the device. `LainAccessibilityService`
  is the screen-control layer and is deliberately thin.
- `network/` — LLM clients. `ReasoningFilter` keeps model thinking out of replies.
- `tools/` — tool definitions and dispatch, with per-tool metadata.
- `data/` — Room, DataStore, model capabilities.

## Free models are the target

The app is built so a free model produces a useful assistant, not a demo. When
something is unreliable, move the intelligence into the app — routing, parsing,
verification — rather than into a longer system prompt or a higher token ceiling.
