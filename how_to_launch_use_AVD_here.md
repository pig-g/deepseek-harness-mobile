# How to launch & use the AVD (Android emulator) in this workspace

Guide for starting the Android emulator from this machine (macOS), installing and driving the
**DSH Mobile** debug app against the live **DSH harness** at `127.0.0.1:3080`.

> Look at **`DSH_MOBILE_HANDOFF.md`** for the bug/solution log. This file covers only *how to
> launch and operate the emulator itself*.

---

## 0. Environment

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"      # SDK root (emulator, platform-tools, …)
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"

# Build-only vars (only needed when running Gradle):
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export GRADLE_USER_HOME="$HOME/.gradle-home"         # project-local Gradle caches
export ANDROID_USER_HOME="$HOME/.android-home"       # project-local AVD/user home
```

## 1. Which AVDs exist

```bash
$ANDROID_HOME/emulator/emulator -list-avds
# Medium_Phone | Medium_Phone_API_36.1 | Pixel_8
```

The one used in this project is **`Medium_Phone`** — API 35 (Android 15), screen
**1080×2400 @ 420dpi** (~411dp wide).

## 2. Launch the emulator

Run it in the background and wait for it to boot:

```bash
# pick a fresh port (5554 is the default console/serial)
$ANDROID_HOME/emulator/emulator -avd Medium_Phone \
  -no-snapshot-load -gpu swiftshader_indirect >/tmp/emu.log 2>&1 &

# wait until the device shows up and finishes booting
$ANDROID_HOME/platform-tools/adb wait-for-device
until [ "$($ANDROID_HOME/platform-tools/adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
```

Notes for agent/headless use:

- Add **`-no-window -no-audio`** to run headless (no GUI) — recommended when driving purely via adb.
- `-gpu swiftshader_indirect` (software GPU) is the most reliable/flicker-free choice for screencap analysis.
- The sandbox may deny the emulator CPU/files — here it boots fine under `danger-full-access`.

## 3. Point the app at the harness

The app talks to **`127.0.0.1:3080`** on its own `localhost`. The emulator has its own
`127.0.0.1`, so bridge the host's harness port over:

```bash
adb reverse tcp:3080 tcp:3080
adb reverse --list        # verify → "host-16 tcp:3080 tcp:3080"
```

Re-run `adb reverse` after every emulator reboot / `adb kill-server`.

## 4. Build & install the app

```bash
cd /Users/heavens3/deepseek/deepseek-harness-mobile
export JAVA_HOME=... GRADLE_USER_HOME=... ANDROID_USER_HOME=...   # see §0

./gradlew :app:assembleDebug          # debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# (optional) instrumented UI test — needs the emulator running
./gradlew :app:connectedDebugAndroidTest
```

App identifiers:

- package / id: **`com.labteto.dshmobile.debug`**   (debug variant)
- launcher activity: **`com.labteto.dshmobile.MainActivity`**

Launch / reopen:

```bash
adb shell am start -n com.labteto.dshmobile.debug/com.labteto.dshmobile.MainActivity
```

After a fresh `install -r`, a **POST_NOTIFICATIONS** dialog appears once — dismiss it with
`adb shell input tap 540 1430` ("Don't allow") before inspecting the UI.

## 5. Drive the UI (headless)

Everything below is scripted, which is why the emulator is great for verifying GUI fixes:

```bash
# dump the view tree (text + bounds), then grep it:
adb shell uiautomator dump /sdcard/u.xml
adb shell cat /sdcard/u.xml \
  | tr '>' '\n' \
  | grep -E 'text="[^"]+"' \
  | sed -E 's/.*text="([^"]*)".*bounds="([^"]*)".*/[\2] \1/'

# tap/type/screenshot:
adb shell input tap X Y
adb shell input text 'hello%sWorld'          # %s = space; avoid most special chars
adb shell input keyevent 4                   # BACK (dismiss keyboard)
adb exec-out screencap -p > shot.png         # full screenshot (analyze with PIL if needed)
```

To press an on-screen button: read its bounds from the uiautomator dump and tap the centre.

> **Verify pixels too.** Some Compose-instrumented taps/semantics are degenerate (e.g. the old
> DsButton 0-width bug): a control can be visually present yet the test tap unreliable. When in
> doubt, screencap and inspect pixels (if the model can't read images, use `python3`/PIL color
> analysis or geometry dumps to confirm).

## 6. Restarting / cleaning up

```bash
adb reboot                          # reboot the guest
adb kill-server; adb start-server   # restart adb (re-add the reverse after)

# gracefully shut the emulator down:
adb -s emulator-5554 emu kill
# or, if adb is gone, kill the process:
# pkill -f 'avd Medium_Phone'
```

## 7. Quick reference of real values seen in this project

| Thing | Value |
|---|---|
| Running AVD | `Medium_Phone` (API 35, 1080×2400, 420dpi) |
| Device serial | `emulator-5554` |
| Harness bind | `127.0.0.1:3080` (host) ↔ `adb reverse tcp:3080 tcp:3080` |
| Debug app id | `com.labteto.dshmobile.debug` |
| Post-install permission dialog tap | `input tap 540 1430` |
| Composer EditText (no keyboard) | ~`[64,1995][1016,2142]` |
| Session logs (host) | `~/.dsh/sessions/--Users-heavens3-deepseek--/session-*/session.jsonl.zstd` (zstd) |
