# TwoDo — running and installing

All commands are PowerShell, run from `D:\dev\twodo`.

## 0. One-time shell setup (per PowerShell window)

The default `java` on this PC is Java 8, which is too old for the build, so point this window at JDK 21
and put the Android tools on the path:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$env:Path += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:LOCALAPPDATA\Android\Sdk\emulator"
```

Check: `adb version` and `emulator -list-avds` (should list `twodo_a` and `twodo_b`).

## 1. Two emulated phones

### Start them

```powershell
Start-Process emulator -ArgumentList "-avd twodo_a -port 5554"
Start-Process emulator -ArgumentList "-avd twodo_b -port 5556"
adb devices    # wait until both show "device" (about a minute)
```

They're also listed in Android Studio under **Device Manager**, where you can start them with ▶.

### Build and install

Emulators are x86_64, so use the **debug** build (the release build is ARM-only and won't install on them):

```powershell
.\gradlew assembleDebug
adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 install -r app\build\outputs\apk\debug\app-debug.apk
```

### Start from scratch

Wipe the app's data on both (lists, name, settings):

```powershell
adb -s emulator-5554 shell pm clear app.twodo
adb -s emulator-5556 shell pm clear app.twodo
```

### Share a list between them

1. Open **TwoDo** on both. Each asks for a name — use different ones (e.g. "Adam" and "Sam").
2. **Phone A:** **New list** → name it → add a few items.
3. **Phone A:** tap **Share** (top right) → **Send link** → **Copy**.
   The emulators share the PC's clipboard, so the link is now on the PC and on phone B.
4. **Phone B:** **Join list** → long-press the "…or paste a link" box → **Paste** → **Join**.
5. Within ~30 s both show **● Connected to …** in the list header. Tick, untick, add, delete and drag on
   either phone and watch the other follow.

If paste doesn't work, the link is still on the PC clipboard — open it on B directly:

```powershell
adb -s emulator-5556 shell am start -a android.intent.action.VIEW -d "'$(Get-Clipboard)'"
```

(Scanning the QR code isn't practical on emulators — their camera is a virtual room.)

### Try a conflict

1. Take B offline: `adb -s emulator-5556 shell "svc wifi disable; svc data disable"` (header shows **Offline**).
2. Tick an item on A. Then on B tick and untick the same item.
3. Bring B back: `adb -s emulator-5556 shell "svc wifi enable; svc data enable"`.
4. Both phones show a message saying B's later change won, and end up identical.

Note: both emulators sit on the same PC network, so they don't test the hard case (Wi-Fi vs mobile data).
Real phones do.

## 2. Real phones

Build the release APK (signed with the key in `%USERPROFILE%\.twodo\` — keep that folder backed up):

```powershell
.\gradlew assembleRelease
# -> app\build\outputs\apk\release\app-release.apk  (~21 MB)
```

Updates are installed the same way and keep the lists, as long as they're signed with the same key.

### Method A — developer mode (USB or Wi-Fi)

Best for your own phone; updates are one command.

1. **Enable developer options:** Settings → About phone → tap **Build number** 7 times.
2. **USB:** Settings → System → Developer options → turn on **USB debugging**.
   Plug the phone into the PC, unlock it, and accept **Allow USB debugging?** (tick "Always allow").
3. Check it's visible: `adb devices` (shows a serial number and `device`).
4. Install / update:
   ```powershell
   adb -s <serial> install -r app\build\outputs\apk\release\app-release.apk
   ```

**Without a cable (Android 11+), phone and PC on the same Wi-Fi:**

1. Developer options → **Wireless debugging** → on → **Pair device with pairing code**.
2. `adb pair <ip>:<pairing-port>` and enter the 6-digit code.
3. `adb connect <ip>:<port>` (the port shown on the Wireless debugging screen, not the pairing one).
4. `adb install -r app\build\outputs\apk\release\app-release.apk`

If you ever get `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the phone has a build signed with a different key
(e.g. a debug build). Uninstall first — `adb uninstall app.twodo` — which deletes that phone's lists.

### Method B — send the APK

Best for a phone you don't want to put in developer mode.

1. Send `app-release.apk` to the phone: **Quick Share** / Nearby Share from a PC or phone, Google Drive,
   or email it as an attachment.
2. On the phone, open the file (Files app → Downloads, or tap the attachment).
3. Android asks to allow installing from that app: **Settings** → turn on **Allow from this source** → back.
4. Tap **Install**. If **Play Protect** warns about an unrecognised app, choose
   **More details → Install anyway** (wording varies by phone).
5. Optionally turn "Allow from this source" off again afterwards.

To update later, send the new APK and repeat — it installs over the old one and keeps the lists.

### First use on the phones

1. Each person enters their name when asked.
2. Phone 1: **New list** → add items → **Share** shows a QR code.
3. Phone 2: **Join list** → **Scan QR code** (allow camera) → point at the code.
4. For the real test, put one phone on Wi-Fi and the other on mobile data.
5. To stay in sync while the app is closed, turn on **Settings → Sync in background** on both.
