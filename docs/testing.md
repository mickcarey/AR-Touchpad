# Testing and performance

Use JDK 17 and the Android SDK/NDK. On Windows, set `ANDROID_HOME` to the SDK directory if `local.properties` does not define `sdk.dir`.

## Local regression checks

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleProd
```

The unit suite covers the recognizer used by the real Compose surface: taps, slow movement across the dead zone, long holds, drag completion versus cancellation, drag lock, trailing contacts, first fresh contact after lifecycle cancellation, scroll/pinch latching, four-finger settings/Pocket gestures and negative edge-swipe cases. Additional tests cover time-based smoothing at multiple input rates, scheduled scroll tails, batching, rotation/calibration, validated preferences/profiles, display selection, keyboard-policy ownership and modifier release on failure.

Reports: `app/build/reports/tests/testDebugUnitTest/index.html` and `app/build/reports/lint-results-debug.html`. Test counts describe exercised behavior; they are not a line-coverage percentage. Hidden Android APIs still require hardware testing across OS/device versions.

Validated on 2026-11-20: 104 unit tests passed; lint reported 0 errors and 42 warnings (primarily dependency versions, intentional hidden APIs and device/form-factor constraints). Prod and Debug APKs built successfully; Prod manifest isolation and 16 KB native alignment were checked.

## Pixel / external-display tests

Connect an unlocked phone to an extended display, keep Shizuku running, and grant **AR Touchpad Debug** permission once. Leave the phone and display on the test apps while the suite runs. The connected tests fail explicitly if the required hardware or permission is missing.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r au.com.michaelcarey.artouchpad.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Instrumentation checks preference persistence/validation, isolated profile snapshots, settings slider preview versus persistence, four-finger help text, the DEV launcher ribbon, actual native input delivery to the external display, Ctrl+wheel coordinates/modifier cleanup, drag cancellation/copy, reconnect, lost-device recovery, reset, and background/resume keyboard routing. The first phone swipe is dispatched through the actual Activity/Compose surface and must reach the external test window without opening settings. Media-header tests cover compact and wide layouts, fixed positions while scrolling, correct previous/next/mute key mappings and disabled controls when disconnected; callbacks are recorded without changing real playback or volume.

The receiver is a private Debug/Benchmark activity, excluded from Prod/release. Hardware tests save and restore the app's preferences. The optional performance method is skipped in an ordinary suite, explicitly rather than counted as a measured result.

The final Pixel 9 / XREAL One Pro run on 2026-11-20 passed **17 device tests**, including the media-header follow-up, with the performance method skipped as intended (18 discovered tests). The separate opt-in Benchmark performance test also passed. The phone-touch test waits for Compose frames after changing settings and accepts movement on either axis so saved rotation/calibration does not invalidate the test.

## Performance sample

Benchmark uses a separate application ID, release behavior, and a local signing key. It is non-debuggable. Grant **AR Touchpad Benchmark** Shizuku permission before sampling.

```powershell
.\gradlew.bat :app:assembleBenchmark :app:assembleBenchmarkAndroidTest -PdeviceTestBuildType=benchmark
adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
adb install -r app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk
adb shell am start --display 0 -n au.com.michaelcarey.artouchpad.benchmark/com.pgratz.artouchpad.MainActivity
adb shell am instrument -w -r -e class 'com.pgratz.artouchpad.ConnectedInputTest#performanceSample' -e performance true au.com.michaelcarey.artouchpad.benchmark.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/au.com.michaelcarey.artouchpad.benchmark/files/performance.json
```

The method collects 60 software movement-to-receiver timings with smoothing off, then samples normal, black and Pocket modes for 60 seconds each. `-e idleMs 300000` extends each idle sample to five minutes. Keep brightness, other workloads, charging state and glasses constant for comparisons; do not touch or close either test activity.

Limits:

- Timing starts at the software request and ends at the receiver callback. It excludes touchscreen sampling and display scanout; it is **not** physical touch-to-photon latency.
- Allocation totals include test-harness work. App CPU time includes the receiver/harness but excludes the separate Shizuku service.
- Battery charge-counter deltas cover the whole phone and connected glasses. A short sample cannot establish battery-life improvement or compare OLED modes reliably.
- This is a repeatable diagnostic, not a before/after benchmark or a frame-jank trace. No battery-saving percentage is claimed.

### Recorded Pixel 9 / XREAL One Pro sample

The non-debuggable Benchmark run on 2026-11-20 completed successfully on Android 17. Across 60 software input requests, median delivery was **28.08 ms**, p95 **39.73 ms**, and process allocations were **128 KiB** including the harness. Raw results are in [performance-pixel9.json](performance-pixel9.json).

| Mode | Sample duration | App CPU time | Whole-device charge used |
| --- | ---: | ---: | ---: |
| Normal | 60.004 s | 107 ms | 12.5 mAh |
| Black | 60.003 s | 1,581 ms | 15.0 mAh |
| Pocket | 60.004 s | 174 ms | 12.5 mAh |

Black mode used more measured CPU/charge in this single run. The sample does not isolate the cause, so it is not evidence that black mode improves or worsens battery life. Repeated longer measurements and a profiler trace are needed for that comparison. An earlier run was interrupted by closing the test activity and was excluded.

## Packaging and icons

Prod uses `au.com.michaelcarey.artouchpad`; Debug adds `.debug`, Benchmark adds `.benchmark`. Each has independent preferences, permissions, provider authority and mouse identity. The original `com.pgratz.artouchpad` installation is separate and its data is not automatically migrated or deleted.

Prod is non-debuggable and excludes the input probe and DEV resources. With no release signing configuration it uses the local debug key for personal installation; a distribution release requires the configured release key. Native libraries use 16 KB ELF alignment.

The launcher master is `docs/assets/app-icon-master.png`. Run `scripts/build-launcher-icons.ps1` after replacing it to rebuild the bitmap used by the adaptive icon. The Debug overlay and monochrome DEV glyphs remain vector resources. Android 14+ supports adaptive icons, so obsolete density-specific launcher PNGs are not shipped.
