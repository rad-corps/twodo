# TwoDo

Shared to-do lists for Android that sync directly between phones — no account, no server of our own,
no subscription. See [docs/PLAN.md](docs/PLAN.md) for the design and
[docs/GUIDE.md](docs/GUIDE.md) for running emulators and installing on phones.

## Install

TwoDo is free to install from this repository's [Releases](../../releases). Android 8.0 or newer,
64-bit ARM (any phone from the last several years).

**Recommended — stay updated with [Obtainium](https://obtainium.imranr.dev):**

1. Install Obtainium (from its website, F-Droid or GitHub).
2. In Obtainium tap **Add App**, paste `https://github.com/rad-corps/twodo` and tap **Add**.
3. Tap **Install**. Obtainium checks for new releases and offers updates.

**Or manually:** download the latest `TwoDo-v*.apk` from [Releases](../../releases) and open it on your
phone (allow "Install unknown apps" when asked).

## Build

Requires JDK 17+ and the Android SDK (compile SDK 37). With `local.properties` pointing at the SDK:

```
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio.

### Release build (for installing on real phones)

```
./gradlew assembleRelease
```

Produces `app/build/outputs/apk/release/app-release.apk` (64-bit ARM only, ~20 MB), signed with the key
described in `~/.twodo/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).
Without that file, release builds are unsigned. **Back up `~/.twodo/`** — updates must be signed with the
same key, or the app has to be uninstalled (losing its lists) before a new version can be installed.

### Publishing a release

```
./scripts/release.ps1 0.2.0
```

Bumps the version in `gradle.properties`, runs the tests, builds the signed APK, commits, tags `v0.2.0`,
pushes, and creates a GitHub Release with the APK attached — which is what Obtainium watches.

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

## Licence

[GPL-3.0](LICENSE). You're free to use, study, change and share TwoDo; if you distribute a modified
version, it must be under the same licence with its source available.
