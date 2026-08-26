# Running the DSH Mobile app on an Android emulator (in this sandbox)

A practical how-to for booting an emulator, installing `app-debug.apk`, and driving the UI with
`adb` — including the non-obvious gotchas specific to this development sandbox (a **read-only home
directory** and a **file sandbox** that blocks writes outside the session workspace).

> The app is a Compose app, so most of the "drive the UI" work is done with `adb input` (taps,
> text, swipes) plus `uiautomator dump` to read element bounds. There is no GUI to click in.

## 0. Prerequisites (already present in this environment)

- Android SDK at `$HOME/Library/Android/sdk` (with `platform-tools`, `emulator`, `cmdline-tools`).
- JBR (JetBrains Runtime) at `/Applications/Android Studio.app/Contents/jbr/Contents/Home`.
- The repo's own Gradle/Android homes (see §1) — the sandbox forbids writing to `~/.gradle` and
  `~/.android`, so everything must live **inside the repo**.

Set these once per shell (they do **not** persist between tool calls):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export GRADLE_USER_HOME="$PWD/.gradle-home"
export ANDROID_USER_HOME="$PWD/.android-home"
ADB="$ANDROID_HOME/platform-tools/adb"
```

## 1. The two sandbox gotchas (read these first)

### Gotcha A — the home directory is not writable

`~/.gradle`, `~/.android`, and `~/Library/Application Support` are all **read-only** here
(`Operation not permitted`). Consequences:

- **Gradle / Android homes must live in the repo.** Use `GRADLE_USER_HOME="$PWD/.gradle-home"` and
  `ANDROID_USER_HOME="$PWD/.android-home"` (create them inside the repo). Do **not** try to write
  to `~/.gradle` or `~/.android`.
- **The Kotlin compile daemon cannot start** (it can't write its marker files under
  `~/Library/Application Support`). This is harmless: Gradle falls back to **in-process**
  compilation. To keep builds quiet and deterministic, pass `--no-daemon` to `./gradlew`. You will
  still see `Could not connect to Kotlin compile daemon` noise in the log — ignore it; the build
  proceeds in-process.

### Gotcha B — the emulator cannot write its AVD state under `~/.android`

The emulator resolves its AVD from `$HOME/.android/avd` and needs to **write** snapshots,
`bootcompleted.ini`, userdata, etc. there. Because the sandbox blocks those writes, the emulator
dies with:

```
FATAL | A snapshot operation for '<avd>' is pending and timeout has expired. Exiting...
```

**Fix: run the emulator with `HOME` pointed at the repo**, and stage a *copy* of the AVD under a
workspace `.android/avd`. The emulator then reads/writes everything inside the (writable) workspace:

```bash
# 1. stage a clean copy of the AVD inside the repo
mkdir -p .android/avd
cp -R "$HOME/.android/avd/Pixel_8.avd" .android/avd/
cp    "$HOME/.android/avd/Pixel_8.ini"  .android/avd/
# 2. delete the (possibly corrupt) snapshot so the emulator starts fresh
rm -rf .android/avd/Pixel_8.avd/snapshots .android/avd/Pixel_8.avd/read-snapshot.txt
```

> **Why a copy?** The original AVD under `~/.android/avd` is not writable, so you cannot clear its
> snapshot in place. Copying it into the workspace makes it writable. If an AVD's snapshot is
> corrupt, the "snapshot operation pending" FATAL will keep recurring until the snapshot is removed —
> the copy + `rm -rf snapshots` is the reliable reset.

## 2. Pick a working AVD

List AVDs (needs `JAVA_HOME` set, or `avdmanager` fails with "Unable to locate a Java Runtime"):

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" list avd
```

**Gotcha:** `avdmanager` lists an AVD by its `.avd` *directory*, but the emulator needs the matching
`.ini` file. An AVD can have a `.avd` dir **without** a `.ini` (orphaned) — the emulator then
refuses it with `Unknown AVD name`. Check both exist before choosing:

```bash
ls -la "$HOME/.android/avd/"*.ini "$HOME/.android/avd/"*.avd
```

In this environment the usable AVDs were `Pixel_8` (Android 14 / `android-34`) and `Medium_Phone`
(Android 15). The app's `minSdk` is 26, so any of these works. `Pixel_8` is a good default.

## 3. Boot the emulator (headless)

```bash
cd <repo>
HOME="$PWD" nohup "$ANDROID_HOME/emulator/emulator" \
  -avd Pixel_8 \
  -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect \
  -no-snapshot-load -no-snapshot-save \
  -camera-back none -camera-front none \
  > /tmp/emulator_boot.log 2>&1 &
```

Key flags:

- `HOME="$PWD"` — **the critical one** (Gotcha B): makes the emulator read/write the workspace
  `.android/avd` copy instead of the read-only `~/.android`.
- `-no-window` — headless (no display server here).
- `-no-snapshot-load -no-snapshot-save` — always start from a cold boot; avoids the stale-snapshot
  FATAL entirely.
- `-gpu swiftshader_indirect` — software GL; works without a GPU.
- `-camera-back none -camera-front none` — skip camera init (faster, fewer warnings).

Wait for boot (first boot can take ~1–2 min):

```bash
"$ADB" wait-for-device
while [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null)" != "1" ]; do sleep 2; done
"$ADB" devices   # expect: emulator-5554  device
```

## 4. Install the app

Build the debug APK (in-process compile, see Gotcha A):

```bash
./gradlew :app:assembleDebug --no-daemon
```

Install and launch:

```bash
"$ADB" install -r -t app/build/outputs/apk/debug/app-debug.apk
# the debug build's applicationId is com.labteto.dshmobile.debug
"$ADB" shell pm grant com.labteto.dshmobile.debug android.permission.POST_NOTIFICATIONS
"$ADB" shell am start -n com.labteto.dshmobile.debug/com.labteto.dshmobile.MainActivity
```

> On Android 14+ the first launch pops a POST_NOTIFICATIONS dialog. Granting it via `pm grant`
> (above) and relaunching avoids having to tap it.

## 5. Drive the UI with `adb` (no GUI)

### 5a. Read element positions

Compose exposes a view tree to `uiautomator`. Dump it and extract `text` + `bounds`:

```bash
"$ADB" shell uiautomator dump /sdcard/ui.xml
"$ADB" shell cat /sdcard/ui.xml | tr '>' '\n' \
  | grep -iE 'text="[^"]' \
  | sed 's/.*text="\([^"]*\)".*bounds="\([^"]*\)".*/  \1 @\2/'
```

`bounds` are `[left,top][right,bottom]` in **device pixels** (1080×2400 for Pixel_8). The tap
coordinate is the center: `((left+right)/2, (top+bottom)/2)`.

> **Gotcha:** the dump can come back empty right after a screen transition or while the IME is up.
> Wait a moment and re-dump. If the keyboard is open, the layout is scrolled — either dismiss it
> first or re-read the (moved) bounds before tapping.

### 5b. Tap, type, scroll

```bash
# tap the center of a field/button
"$ADB" shell input tap <x> <y>

# type into the focused field (tap the field first)
"$ADB" shell input text "10.0.2.2"

# clear a field: move to end, then delete N times
"$ADB" shell input keyevent KEYCODE_MOVE_END
for i in 1 2 3 4 5 6; do "$ADB" shell input keyevent KEYCODE_DEL; done

# scroll: swipe from (x1,y1) to (x2,y2) over <ms>
"$ADB" shell input swipe 540 1800 540 900 300     # swipe up = scroll down
"$ADB" shell input swipe 540 600 540 1900 300     # swipe down = scroll to top

# hide the keyboard / go back
"$ADB" shell input keyevent 4
```

### 5c. Watch for crashes

```bash
"$ADB" logcat -c                                   # clear
# ... drive the UI ...
"$ADB" shell ps | grep -i dshmobile                # is the process alive? (pid changes on crash)
"$ADB" logcat -d | grep -iE "FATAL EXCEPTION|E/AndroidRuntime"   # any crash?
```

### 5d. Screenshot (for visual verification)

```bash
"$ADB" exec-out screencap -p > /tmp/screen.png
```

## 6. Reaching a host service from the emulator (for connect tests)

The emulator is a separate network namespace. To reach a service on the **host's loopback**
(e.g. the `:mock-harness` Ktor server bound to `127.0.0.1:8080`), use the emulator's host alias:

```
10.0.2.2  ==  host 127.0.0.1
```

So a host service on `127.0.0.1:8080` is reached from the app as `10.0.2.2:8080`.

> **Gotcha (mock-harness fidelity):** the app validates a server via its `host.describe` response.
> The `:mock-harness` test double's `host.describe` payload does not match the app's current
> `HostDescribeValue` schema, so the app reports it as *"not a DeepSeek Harness"* and will not
> complete a full handshake against it. That is enough to exercise the connect/failure UI, but not
> a live connected session. For a true reconnect-with-events repro you need a real harness.

## 7. Tear down

```bash
"$ADB" emu kill 2>/dev/null || pkill -f "emulator -avd"
pkill -f "qemu-system" 2>/dev/null
# if you started the mock harness as a java process:
pkill -f "mockharness.MainKt" 2>/dev/null
```

`adb emu kill` can fail with an auth-token error in this environment; `pkill` on the emulator
process is the reliable fallback.

## Quick reference (the whole happy path)

```bash
cd <repo>
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export GRADLE_USER_HOME="$PWD/.gradle-home" ANDROID_USER_HOME="$PWD/.android-home"
ADB="$ANDROID_HOME/platform-tools/adb"

# one-time AVD staging (skip if .android/avd/Pixel_8.* already exist in the repo)
mkdir -p .android/avd
cp -R "$HOME/.android/avd/Pixel_8.avd" .android/avd/ 2>/dev/null
cp    "$HOME/.android/avd/Pixel_8.ini"  .android/avd/ 2>/dev/null
rm -rf .android/avd/Pixel_8.avd/snapshots .android/avd/Pixel_8.avd/read-snapshot.txt

# build + boot
./gradlew :app:assembleDebug --no-daemon
HOME="$PWD" nohup "$ANDROID_HOME/emulator/emulator" -avd Pixel_8 -no-window -no-audio \
  -no-boot-anim -gpu swiftshader_indirect -no-snapshot-load -no-snapshot-save \
  -camera-back none -camera-front none > /tmp/emulator_boot.log 2>&1 &

"$ADB" wait-for-device
while [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null)" != "1" ]; do sleep 2; done
"$ADB" install -r -t app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell pm grant com.labteto.dshmobile.debug android.permission.POST_NOTIFICATIONS
"$ADB" shell am start -n com.labteto.dshmobile.debug/com.labteto.dshmobile.MainActivity
"$ADB" exec-out screencap -p > /tmp/screen.png
```
