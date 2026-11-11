# AllCode Connect

A native Android app (Kotlin, Jetpack Compose) for driving T3 Code from your phone: follow
agents as they work, answer approvals and questions, start tasks, review diffs, open a
terminal, watch previews, and keep an eye on pull requests and usage limits.

It connects straight to the T3 Code server on your computer over your network or tailnet.
There is no account and no relay in between.

## Pair a phone

On the computer running T3 Code, open **Settings → Connections** and choose **Create pairing link**, or run
`npx t3 pair` (add `--tailscale` to reach it from anywhere on your tailnet). In the app,
scan the QR code, paste the pairing link, or enter the address and code. Codes work once
and expire after a few minutes.

## Build

Requirements: JDK 17 or newer and the Android SDK (`platforms;android-37.0`,
`build-tools;37.0.0`). Point `local.properties` at the SDK (`sdk.dir=/path/to/Android/Sdk`).

```bash
./gradlew :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk` and installs as
`com.allcode.connect.dev`, beside a release install. Release builds
(`./gradlew :app:assembleRelease`) are minified and signed with the keystore named by
`ALLCODE_KEYSTORE`, `ALLCODE_KEYSTORE_PASSWORD`, `ALLCODE_KEY_ALIAS` and `ALLCODE_KEY_PASSWORD`,
or with the debug key when those are unset.

## Test

```bash
./gradlew :app:testDebugUnitTest
```

`LiveServerTest` also runs a full pairing and approval round trip against a dev server when
`T3_DEV_URL` and `T3_DEV_TOKEN` (or `T3_DEV_BEARER`) are set. See
[docs/internals/android-app.md](../../docs/internals/android-app.md) for what the app
depends on in the wire protocol.
