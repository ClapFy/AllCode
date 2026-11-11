---
name: test-t3-mobile
description: Build, test, and verify AllCode Connect, the native Android app in apps/mobile, against an isolated T3 dev server. Use for mobile verification, protocol compatibility checks, and installing the app on a device.
---

# Test AllCode Connect

`apps/mobile` is a Gradle project (Kotlin, Jetpack Compose). It speaks the T3 wire
protocol itself, so server or contract changes can break it without any TypeScript error.

## Build and unit-test

From `apps/mobile`, with `JAVA_HOME` set to a JDK 17+ and `local.properties` naming the SDK:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk` (package `com.allcode.connect.dev`).
On small machines, run one Gradle task at a time and stop dev servers first.

## Check the protocol against a real server

1. Start an isolated dev server (`vp run dev:server`, with `--home-dir` outside a worktree).
2. Mint a pairing token for it: `node apps/server/src/bin.ts pair --base-dir <that home> --ttl 1h`.
3. Run the live test, which pairs, syncs the shell, launches a Replay (dev) thread, and
   answers its approval:

```bash
T3_DEV_URL=http://127.0.0.1:<port> T3_DEV_TOKEN=<token> ./gradlew :app:testDebugUnitTest --tests 'com.allcode.connect.LiveServerTest'
```

Never point it at the user's live server or `~/.t3`.

## On a device

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Pair through
the app's QR scanner, a pasted pairing link, or an address plus code. Reach a dev server
from the phone over the LAN or tailnet with `vp run dev --share`.
