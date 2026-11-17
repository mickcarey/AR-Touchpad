# AR Touchpad

Turn an Android phone into a trackpad for an external display running Android desktop mode. The app was developed with Viture XR glasses, but should work with other displays that support Android's extended desktop.

## Features

- Move the external display's cursor with one finger; tap to click.
- Double tap to double-click and long-press to right-click.
- Drag with two fingers to scroll; spread or pinch to zoom.
- Use the phone as a keyboard for text fields on the external display.
- Keep menus open while moving the pointer from the phone's touchpad.

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

## License

Licensed under the [Apache License 2.0](LICENSE).
