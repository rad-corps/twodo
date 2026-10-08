# TwoDo

Shared to-do lists for Android that sync directly between phones — no account, no server of our own,
no subscription. See [docs/PLAN.md](docs/PLAN.md) for the design.

## Build

Requires JDK 17+ and the Android SDK (compile SDK 37). With `local.properties` pointing at the SDK:

```
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio.

## Using it

1. Phone A: **New list**, add items, tap **Share** to show the QR code.
2. Phone B: **Join list → Scan QR code** (or paste the link sent via **Send link**).
3. Both phones show "Connected to …" once they've found each other; edits then sync live.

Turn on **Settings → Sync in background** to stay reachable while the app is closed.

## Code map

- `model/` — items, versions, merge + conflict rules (`Merge.kt`, unit-tested), encryption, share links
- `data/` — list storage (`ListRepository`) and device identity/settings
- `net/` — tracker signalling (`TrackerClient`), WebRTC connection (`Peer`), per-list peer discovery (`ListSwarm`)
- `sync/` — `SyncManager` (protocol, forwarding), background service/worker, notifications
- `ui/` — Compose screens
