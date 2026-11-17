# AR Touchpad

Turn an Android phone into a trackpad for an external display running Android desktop mode. The app was developed with Viture XR glasses, but should work with other displays that support Android's extended desktop.

## Features

- The touchpad fills the entire phone screen, including the space previously used by
  the title and navigation buttons. Android's system bars are hidden until a system swipe.
- With the phone rotated **90° anticlockwise**, swipe **down along the right edge**
  to open the app's settings on the external display. Start just inside the edge,
  away from the corners, and swipe at least about 2 cm. In upright phone coordinates,
  this is a right-to-left swipe along the bottom edge. This gesture is independent
  of the cursor's Axis Rotation setting.
- The phone remains a touchpad while settings are open on the glasses. Click **Done**
  there to close them. If an external settings window is unavailable, settings open
  on the phone instead. Shizuku setup/reconnect controls remain available when disconnected.
- Settings open in a centred Material-style window, with two columns on wide displays,
  a single column on the phone, and an expandable gesture guide. Changes save automatically.
- Move the external display's cursor with one finger; tap to click.
- Double tap to double-click; tap with two fingers to right-click (long-press also works).
- Drag with two fingers to scroll; spread or pinch to zoom.
- Tap with three fingers while Moonlight is focused on the external display to release
  its mouse capture back to Android. This sends Moonlight's Ctrl+Alt+Shift+Z shortcut;
  repeat while Moonlight is focused to recapture. The stream stays open.
- Use the phone as a keyboard for text fields on the external display.
- The popup keyboard stays on the external display by default so you can click its
  keys with the cursor. Choose **Settings → Popup keyboard → External display** to keep
  the phone available as a fullscreen touchpad, or choose **Phone** to type there instead.
- Keep menus open while moving the pointer from the phone's touchpad.
- In **Settings → Axis Rotation**, rotate cursor movement and two-finger scrolling by
  45° or 90° clockwise or counterclockwise. The default is 0°, and your choice is saved.
  For example, at 90° clockwise, swiping right moves the cursor down.

## Requirements

- Android 14 or later
- ARM64 device
- Shizuku running, started through Wireless Debugging or root
- Android desktop/extended display mode

## Setup

1. Install and start [Shizuku](https://shizuku.rikka.app/).
2. Connect the phone to an external display and enable Desktop or Extended mode.
3. Build and install the app:

   ```bash
   ./gradlew installDebug
   ```

4. Open AR Touchpad and grant its Shizuku request. Use **Connect** if the mouse does not connect automatically.

## Build

Requires Android Studio with the Android SDK and NDK installed.

```bash
./gradlew assembleDebug
```

On Windows PowerShell, use `.\gradlew.bat assembleDebug` (JDK 17 recommended).

## Wireless installation from the CLI (Windows)

Keep the PC and phone on the same Wi-Fi. On the phone enable **Developer options →
Wireless debugging**, then open **Pair device with pairing code**.

```powershell
# Use the pairing dialog's IP:port. adb prompts for its six-digit code.
adb pair 192.168.1.10:37123

# Close that dialog. Use the DIFFERENT IP:port on the main Wireless debugging page.
.\scripts\install-wireless.ps1 -Address 192.168.1.10:40001 -Launch

# Subsequent builds, when exactly one wireless device is connected:
.\scripts\install-wireless.ps1 -Launch
```

Alternatively, pass `-PairAddress IP:PAIR_PORT -Address IP:CONNECT_PORT` to the script
to pair and install in one invocation. It finds `adb` on PATH or in the Android SDK,
builds the debug APK, and installs with `adb install -r` to retain app data. It never
automatically uninstalls an existing app. Use `-SkipBuild` to install the last build,
or `-Serial` to select among multiple connected wireless devices.

If discovery fails, run `adb mdns services` and `adb devices -l`, then reconnect with
`-Address` using the phone's current connection port (it can change). A pairing port
is not an installation port. If installation reports a signing mismatch, use a build
signed with the existing app's key; uninstalling would erase its data.

See Android's [wireless ADB documentation](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi).

## License

Licensed under the [Apache License 2.0](LICENSE).
