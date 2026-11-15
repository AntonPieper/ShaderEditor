# Re-check after the owner's changes: two branches, Android 6 and Android 17

Branches on github.com/AntonPieper/ShaderEditor (remote `origin`), open as draft PRs on markusfisch/ShaderEditor:

| Fix | Branch | Head | PR |
|---|---|---|---|
| A | `upstream-fix-large-shader-state` | 132b7e67 | #319 |
| B | `upstream-fix-camera-permission-warning` | 1301d70c | #320 |

What changed: A now writes the editor text file synchronously in `onSaveInstanceState` (temp file, then rename) and deletes it synchronously in `onResume`; the executor is gone. B now shows the message as soon as the user denies the permission, using the Activity Result API.

Run everything on an Android 6 (API 23) emulator and an Android 17 emulator. Save screenshots and logcat to `.foreman/show/upstream-batch-4/recheck/<A|B>/<android6|android17>/`.

## Setup (Windows)

```bat
git fetch origin
git fetch upstream
git checkout --detach origin/upstream-fix-large-shader-state
gradlew.bat :app:assembleDebug
copy app\build\outputs\apk\debug\app-debug.apk A-fix.apk
git checkout --detach origin/upstream-fix-camera-permission-warning
gradlew.bat :app:assembleDebug
copy app\build\outputs\apk\debug\app-debug.apk B-fix.apk
git checkout --detach upstream/master
gradlew.bat :app:assembleDebug
copy app\build\outputs\apk\debug\app-debug.apk master.apk
```

- Package: `de.markusfisch.android.shadereditor.debug`. Start it with `adb shell am start -n de.markusfisch.android.shadereditor.debug/de.markusfisch.android.shadereditor.activity.SplashActivity`.
- If the fork's own debug app is installed, it has the same package id. Uninstall it first and reinstall it afterwards if it was there before.
- "Fresh install" means `adb uninstall de.markusfisch.android.shadereditor.debug` followed by `adb install <apk>`.
- Device shell commands below are written for **cmd.exe**, inside double quotes. In **PowerShell**, use single quotes instead, or PowerShell expands `$(...)` itself.
- Use `adb -s <serial>` when both emulators are running.

---

## A. Editor text survives an immediate process kill

### A1. Make the shaders (Python 3 on the host; `sqlite3` is in its standard library)

Save as `make_shader.py`:

```python
import sys
size = int(sys.argv[1])
head = "precision mediump float;\nuniform float time;\nvoid main() {\n  float v = 0.0;\n"
tail = "  gl_FragColor = vec4(0.2, 0.6, fract(v), 1.0);\n}\n"
lines, n, i = [], len(head) + len(tail), 0
while True:
    l = "  v += sin(time * %d.0) * 0.001;\n" % (i % 997)
    if n + len(l) > size: break
    lines.append(l); n += len(l); i += 1
open("shader_%d.glsl" % size, "w", newline="\n").write(head + "".join(lines) + tail)
```

Then run `python make_shader.py 2000` and `python make_shader.py 160000`.

### A2. Put a shader into the app's database (do this for each size)

1. Fresh install of `A-fix.apk`. Start the app once.
2. Settings → turn **Auto save** off. Press Home.
3. Run `adb shell am force-stop de.markusfisch.android.shadereditor.debug`.
4. Replace shader 1 (cmd.exe; `SIZE` is 2000 or 160000):
   ```bat
   set P=de.markusfisch.android.shadereditor.debug
   adb exec-out run-as %P% cat databases/shaders.db > shaders.db
   python -c "import sqlite3;c=sqlite3.connect('shaders.db');c.execute('update shaders set shader=? where _id=1',(open('shader_SIZE.glsl').read(),));c.commit()"
   adb shell run-as %P% rm -f databases/shaders.db-journal
   adb push shaders.db /data/local/tmp/shaders.db
   adb shell "run-as %P% sh -c 'cat /data/local/tmp/shaders.db > databases/shaders.db'"
   ```
   If `run-as` cannot read `/data/local/tmp` on that image, use `type shaders.db | adb shell "run-as %P% sh -c 'cat > databases/shaders.db'"` instead. That does not work in PowerShell, which re-encodes the pipe.
5. Start the app. The shader shows (line 4 is `  float v = 0.0;`).

### A3. Home, then kill at once (five times per size)

For each try:

1. Tap at the end of line 4 and type ` // EDIT n` (n = try number), so every try is a different unsaved edit. Hide the keyboard.
2. Press Home and kill the process in one command line:
   ```bat
   adb shell "input keyevent 3; sleep 0.3; run-as de.markusfisch.android.shadereditor.debug kill -9 $(pidof de.markusfisch.android.shadereditor.debug)"
   ```
   - Vary the delay over the tries: `sleep 0.3`, `sleep 0.5`, `sleep 1`, and none at all (`input keyevent 3; run-as ... kill -9 ...`).
   - If Android 6's `sleep` rejects decimals, use `sleep 1` and the no-sleep variant.
   - If `pidof` is missing, find the PID with `adb shell ps | findstr shadereditor.debug` and pass it in.
3. Check that the process is gone (`adb shell pidof ...` prints nothing).
4. Check whether the system kept the activity state:
   ```bat
   adb shell dumpsys activity activities | findstr /C:"haveState" /C:"MainActivity"
   ```
   - `haveState=true` on the MainActivity record means `onSaveInstanceState` ran before the kill.
   - `haveState=false` means the kill came first. Then losing the edit is expected, and master behaves the same.
5. Run `adb shell run-as de.markusfisch.android.shadereditor.debug ls -l no_backup/`. Expected after a saved state: `editor_draft`, about SIZE bytes plus 37, and no `editor_draft.tmp`.
6. Open the app from Recents, not from the launcher icon.

**Expected with the fix, both sizes:** in every try with `haveState=true`, line 4 ends with ` // EDIT n` of that try. Also check:
- No `TransactionTooLargeException` in `adb logcat`.
- After the app is back in front, `no_backup/editor_draft` is gone (deleted on resume).
- Record how many of the five tries had `haveState=true`.

**Master for comparison** (`master.apk`, same steps):
- 2,000 characters: the edit comes back too, because upstream keeps it in the state bundle.
- 160,000 characters: Home crashes with `TransactionTooLargeException` on Android 7 and later. On Android 6 it may only be logged, and the edit is then lost.

### A4. Rotation (fix build, 160,000 characters)

1. Type ` // ROT` at the end of line 4.
2. Select the word `ROT` by long-pressing it.
3. Rotate the device.

**Expected:**
- The text is unchanged and `ROT` is still selected.
- Menu (⋮) → Undo removes the typed text.
- Rotate back: the same holds.
- `no_backup/` holds no new `editor_draft`, because a rotation keeps the text in memory.

### A5. Cost of the synchronous write (not measured precisely)

No log line was added. The debug build's StrictMode logs the main-thread write:

```bat
adb logcat -c
REM type an edit, then press Home normally, wait 3 s
adb logcat -d | findstr /C:"DiskWriteViolation" /C:"EditorDraft"
```

For the violation whose stack contains `EditorDraft.write`, note its `~duration=N ms`, for 2,000 and for 160,000 characters on both Android versions. That figure is an approximation: it is how long the main-thread message took, not the write alone. If no such line appears, report it as "not measured".

---

## B. Camera permission message right after a denial

Use the Back camera sample. On an emulator the back camera shows a virtual scene or a checkerboard once it is granted.

### B1. Deny shows the message at once

1. Fresh install of `B-fix.apk`. Start the app.
2. Menu → Load sample → Back camera. The permission dialog appears.
3. Tap **Deny** (Android 17: **Don't allow**). On Android 6, do not tick "Never ask again".

**Expected:**
- "Camera access is required to show the camera." appears right after the tap (take a screenshot within about 2 s).
- The picture is black.
- No second dialog.
- Exactly one message, not two in a row.

### B2. Shader restarted, same screen

1. Add a space at the end of the shader, so it recompiles.
2. Press Home and come back.

**Expected:** no dialog and no message (this screen already showed it).

### B3. New screen

Rotate the device.

**Expected:** the message once, and no dialog. Rotate back: the message once more, and no dialog.

### B4. Next app run

1. Run `adb shell am force-stop de.markusfisch.android.shadereditor.debug`.
2. Start the app. The Back camera shader is loaded.

**Expected:**
- The dialog once (one request per app run). Deny: the message at once.
- On Android 17, after a second denial the system no longer shows the dialog. The next app run then shows the message at once, with no dialog. Both are fine.

### B5. Rotation while the dialog is open

1. Force-stop the app and start it, so the dialog shows.
2. Rotate while the dialog is open.
3. Deny.

**Expected, a known limit:** the rebuilt screen behind the dialog shows the message once, already while the dialog is still open. The denial itself then shows no further message, because its answer reaches the new screen, which did not ask. There is no second dialog and no crash. Report what you see.

### B6. Grant in system settings

1. Settings → Apps → Debug (Shader Editor debug) → Permissions → Camera → Allow.
2. Go back to the app (from Recents).

**Expected:** the camera picture shows, with no message and no dialog.

### B7. Untouched parts (quick)

- **Microphone:** a shader with `uniform float micAmplitude;` behaves exactly like master. It still asks with its own dialog each time the shader starts while the permission is missing, and shows no message.
- **Wallpaper:** set the Back camera shader as the live wallpaper while the camera permission is denied. The wallpaper shows black, with no dialog and no message, as on master.
