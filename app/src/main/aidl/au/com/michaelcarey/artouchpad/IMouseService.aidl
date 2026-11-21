// Copyright 2026 Paul Gratz
// Modifications Copyright 2026 Michael Carey
// Modified for the Michael Carey fork, including the package namespace migration.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package au.com.michaelcarey.artouchpad;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.ResultReceiver;

// Input calls are asynchronous and ordered on this Binder, keeping the UI thread free.
interface IMouseService {
    // Tells the service which display to target for cursor movement and key injection,
    // and provides its pixel dimensions so the cursor can be clamped correctly.
    oneway void setDisplay(int displayId, int width, int height) = 1;

    // Moves the virtual mouse by (dx, dy) pixels relative to its current position.
    // Sub-pixel remainders are accumulated across calls so slow movements don't stall.
    oneway void moveMouse(float dx, float dy) = 2;

    // Presses and releases a mouse button at the current cursor position.
    // button: MotionEvent.BUTTON_PRIMARY (left) or BUTTON_SECONDARY (right).
    oneway void click(float x, float y, int button) = 3;

    // Scrolls the view under the cursor right/down by (dx, dy), in finger-pixel units.
    // dy becomes vertical wheel motion, dx horizontal, both in 1/120-detent hi-res units.
    oneway void scroll(float dx, float dy) = 4;

    // Injects a key press+release for the given Android keycode (e.g. KEYCODE_BACK = 4)
    // targeted at the focused window on the configured display via InputManagerGlobal.
    oneway void pressKey(int androidKeycode) = 5;

    // Converts a text string to KeyEvents via KeyCharacterMap and injects them
    // to the focused window on the configured display.
    oneway void typeText(String text) = 6;

    // Writes the system font_scale setting (clamped 0.85–1.5) via `settings put system`.
    // Shell uid has permission to write system settings without root.
    oneway void setFontScale(float scale) = 7;

    // Presses BTN_LEFT without releasing — used to begin a click-drag (text selection).
    oneway void mouseDown() = 8;
    // Releases BTN_LEFT — used to end a click-drag (text selection).
    oneway void mouseUp() = 9;

    // Injects Ctrl+keycode (Android keycode) via InputManagerGlobal — used for Copy/Cut/Paste/SelectAll
    // after a text selection drag without moving the cursor.
    oneway void pressKeyWithCtrl(int keycode) = 10;

    // Injects a Ctrl+scroll MotionEvent at the current cursor position so Chrome and
    // WebView-based apps zoom their content without changing the system font scale.
    // amount: AXIS_VSCROLL value (positive = zoom in, negative = zoom out).
    oneway void ctrlScroll(float amount) = 11;

    // Sets the per-display IME policy via IWindowManager.setDisplayImePolicy (reflection;
    // shell uid holds the required INTERNAL_SYSTEM_WINDOW permission).
    // policy: 0 = local (IME on the display that owns the field), 1 = fallback (IME on the
    // default display, i.e. the phone — DeX-style), 2 = hide.
    // Returns true if the call succeeded; false if unsupported on this build.
    boolean setImePolicy(int displayId, int policy) = 12;

    // Ctrl+Alt+Shift+Z toggles Moonlight's input capture on the configured display.
    oneway void toggleMoonlightCapture() = 13;

    // Acknowledges completed mouse creation and display association (not just IPC delivery).
    boolean isDisplayReady(int displayId, int width, int height) = 14;

    // Releases buttons and removes the virtual mouse while no external display is present.
    // A build handoff waits for this optional acknowledgement before launching its peer.
    oneway void clearDisplay(in ResultReceiver completion) = 15;

    // Hides our mouse icon while locked, preserving the device for immediate settings input.
    oneway void setPointerHidden(boolean hidden) = 16;
    oneway void showModeFeedback(in Bitmap badge) = 17;
    oneway void dismissModeFeedback() = 18;
    oneway void setDragReady(boolean ready) = 19;

    // Last actionable setup/input failure; empty when healthy.
    String getLastError() = 20;

    // Observed playback and music-stream volume; synchronous calls run off the UI thread.
    Bundle getMediaState() = 21;
    boolean sendMediaControl(int keycode) = 22;
    // Allows only this build's settings overlay through the existing Shizuku grant.
    boolean enableSettingsOverlay() = 23;
    oneway void setNotificationShadeBlocked(boolean blocked) = 24;
    oneway void showPhoneOverview() = 25;
    oneway void hideTransientPhoneBars() = 26;

    Bitmap getMediaArtwork(String expectedKey) = 27;

    // Closes the uinput file descriptor and marks the device not ready.
    void destroy() = 16777114;
}
