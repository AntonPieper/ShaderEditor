# Error list and rotation: device steps

Branch `upstream-fix-error-list-rotation` on https://github.com/AntonPieper/ShaderEditor, commit `4b6fa00e`, one commit on `upstream/master` (`acacf4d4`).
The "before" build is `upstream/master` itself.

## Build

    git fetch origin upstream
    git checkout upstream/master                 # before
    ./gradlew :app:assembleDebug
    adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk

    git checkout origin/upstream-fix-error-list-rotation   # after
    ./gradlew :app:assembleDebug
    adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk

The app id is `de.markusfisch.android.shadereditor.debug`. Install "after" over "before" (same debug key), or `adb uninstall` the app first.

## Steps (Android 16 or 17, then Android 6)

1. Open the app. Auto-rotate must be on. On an emulator you can instead rotate with the emulator's rotate buttons, or with Ctrl+Left / Ctrl+Right.
2. Make a compile error: in the editor, type `x` on an empty line after the closing `}`, and wait for the shader to compile (run mode "auto"). The error button (`Show errors`, a red `!` in the toolbar) appears.
3. Tap the error button. The sheet with the list of errors opens.
4. Rotate to landscape while the sheet is open.
5. Rotate back to portrait.
6. Repeat 3 to 5 with a second recreate instead of a rotation: Developer options → "Don't keep activities" on, open the sheet, press Home, open the app again from Recents. Turn the option off afterwards.

## Expected

| Step | Before (upstream/master) | After (this branch) |
| --- | --- | --- |
| 4 | The app closes. Logcat: `Fragment$InstantiationException: Unable to instantiate fragment de.markusfisch.android.shadereditor.widget.ErrorListModal: could not find Fragment constructor` | No crash. The sheet is gone; the editor shows the code in landscape, with the error line still marked once the shader has compiled again, and the error button visible |
| tap the error button again | — | The sheet lists the current error (`'x' : syntax error` or the device's wording) |
| tap the error in the list | — | The editor jumps to that line and the sheet closes |
| 6 | The app closes on return | No crash; no sheet; the error button shows the current errors |

Same expectations on Android 6 (API 23); there the error text can be worded differently by the driver.

To read the crash: `adb -s <serial> logcat -d | grep -B2 -A8 InstantiationException`.
