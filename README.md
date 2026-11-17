# AR Touchpad

An Android app that turns your phone's touchscreen into a trackpad for controlling **Android desktop mode** on [Viture XR Pro](https://www.viture.com/) glasses (or any USB-C DisplayPort display running Android's extended desktop).

## How it works

The glasses connect via USB-C DisplayPort and appear as a second display in Android's desktop mode. This app creates a virtual mouse device using the Linux `uinput` subsystem (via [Shizuku](https://shizuku.rikka.app/)), so Android sees it as a real hardware mouse and shows a proper system cursor on the glasses display.

- **Single-finger drag** — moves the cursor
- **Tap** — left click
- **Double tap** — double click
- **Long press (600 ms)** — right click
- **Two-finger drag** — scroll
- **Bottom nav bar** — Back / Home / Recents / Screenshot (via Accessibility Service)

## Requirements

| Requirement | Details |
|---|---|
| Android | 14+ (minSdk 34) |
| Architecture | arm64-v8a |
| [Shizuku](https://shizuku.rikka.app/) | Running via Wireless Debugging or root |
| Accessibility Service | Must be enabled in Settings for nav buttons |

Tested on **Pixel 10 + Viture XR Pro** running Android 16.

## Setup

### 1. Enable Shizuku
Follow the [Shizuku guide](https://shizuku.rikka.app/guide/setup/) to start Shizuku via Wireless Debugging (no root required):

1. Enable **Developer Options** → **Wireless Debugging**
2. Run `adb tcpip 5555` and `adb connect <phone-ip>`
3. Start Shizuku from its app

### 2. Install AR Touchpad
Build and install the debug APK, or download a release:

```bash
git clone https://github.com/pgratz1/AR-Touchpad.git
cd AR-Touchpad
./gradlew installDebug
```

### 3. Grant permissions
- Open the app → tap **Grant** when Shizuku asks for permission
- Enable the **AR Touchpad** Accessibility Service in **Settings → Accessibility**

### 4. Connect the glasses
Plug in the Viture XR Pro. Pull down the notification shade and switch to **Desktop / Extended** mode (not Mirror). The app's status bar shows all detected displays — the glasses should appear as a second display.

## Architecture

```
app/
├── src/main/
│   ├── aidl/          IMouseService.aidl — Binder interface to Shizuku service
│   ├── cpp/           uinput_jni.cpp — JNI: open /dev/uinput, ioctl, write events
│   └── java/…/
│       ├── MouseService.kt              — Shizuku UserService (runs as shell uid)
│       ├── ShizukuMouseController.kt    — Binds/unbinds the Shizuku service
│       ├── TouchpadViewModel.kt         — State, display detection, gesture dispatch
│       ├── UinputNative.kt             — Kotlin wrapper for the JNI library
│       ├── TouchpadAccessibilityService.kt — Global nav actions (Back/Home/etc.)
│       └── ui/TouchpadScreen.kt        — Compose UI: touch surface + status bar
```

**Why JNI for uinput?**  
Android 16 removed the generic `Os.ioctl(FileDescriptor, int/long, long)` method from `android.system.Os`, leaving only specialised variants. A small native library is the only reliable way to call `ioctl` with an arbitrary integer value — required for `UI_SET_EVBIT`, `UI_SET_RELBIT`, etc.

**Why Shizuku?**  
Creating a `uinput` device requires the `input` group (gid 1004). Shizuku grants shell uid (2000), which is in that group. No root needed.

## Building from source

Requires Android Studio (for the SDK) and NDK r25+.

```bash
# Debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

The NDK CMake build compiles `libartouchpad.so` for `arm64-v8a` automatically.

## Verifying menu navigation on an external display

The active touchpad window accepts touches without taking keyboard focus. This
prevents touching the phone from dismissing focus-sensitive menus on the external
display. Normal window focus is restored in settings or when the mouse/display
is unavailable. The phone keyboard can still appear above the touchpad.

On a phone with Shizuku and an external display connected:

1. Open a Chrome bookmark folder with a tap. Touch down again and immediately move
   the pointer into the menu; the folder should remain open. Tap a subfolder, move
   again, and tap a bookmark. Repeat with a pause between gestures.
2. Repeat with a right-click context menu and another app's dropdown menu.
3. Focus a text field on the external display with phone-keyboard mode enabled;
   verify the keyboard is visible and typing goes to that field.
4. Open/close touchpad settings, disconnect/reconnect the display, and background/
   reopen the app. Check that settings remain usable and menu navigation still works.

For focus diagnostics, use `adb shell dumpsys window windows`: the active touchpad
window should have `NOT_FOCUSABLE` and `ALT_FOCUSABLE_IM`, but not `NOT_TOUCHABLE`.
These two focus flags should be absent in settings or without an external display.

## License

Copyright 2026 Paul Gratz

Licensed under the [Apache License, Version 2.0](LICENSE).
