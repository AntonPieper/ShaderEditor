# Upstream batch 5: device steps (Android 16 and Android 6)

Five branches of github.com/AntonPieper/ShaderEditor (remote `origin`), each one commit on markusfisch/ShaderEditor `master` f251c8e0. Run every item on an **Android 16** and an **Android 6 (API 23)** emulator: first on a build of `upstream/master` (BEFORE), then on a build of the branch (AFTER). Save the screenshots to `.foreman/show/upstream-batch-5/<branch-short-name>/<android16|android6>/<before|after>-<n>.png`.

| # | Branch | Head |
|---|---|---|
| 1 | `upstream-fix-shadertoy-rewrap` | c8017864 |
| 2 | `upstream-fix-hex-uint-suffix` | 04b14c54 |
| 3 | `upstream-fix-live-wallpaper-optional` | be9c2f93 |
| 4 | `upstream-fix-export-database-legacy` | a68110b7 |
| 5 | `upstream-fix-remove-unloadable-texture` | 59d6655f |

## Common setup

```sh
git fetch upstream && git fetch origin
git checkout --detach upstream/master && ./gradlew :app:assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk master.apk
git checkout --detach origin/<branch> && ./gradlew :app:assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk <short-name>.apk
```

- **Package:** `de.markusfisch.android.shadereditor.debug`. The fork's own debug build has the same id. Uninstall it first, and reinstall it at the end if it was there.
- **Fresh install** = `adb uninstall de.markusfisch.android.shadereditor.debug` then `adb install <apk>`. Every item starts with a fresh install unless it says otherwise.
- **Start the app:** `adb shell am start -n de.markusfisch.android.shadereditor.debug/de.markusfisch.android.shadereditor.activity.SplashActivity`
- **Menu** = the ⋮ button at the top right of the editor.

---

## 1. `upstream-fix-shadertoy-rewrap` (#122)

1. Fresh install, then start the app.
2. Send a one-line ShaderToy shader to the app. On Windows cmd.exe use the same line, keeping the outer double quotes.
   ```sh
   adb shell "am start -a android.intent.action.SEND -t text/plain -n de.markusfisch.android.shadereditor.debug/de.markusfisch.android.shadereditor.activity.MainActivity --es android.intent.extra.TEXT 'void mainImage(out vec4 c, in vec2 p) { c = vec4(p / iResolution.xy, 0.5 + 0.5 * sin(iTime), 1.0); }'"
   ```
   The editor shows the converted shader, on both builds:
   - one `#ifdef GL_FRAGMENT_PRECISION_HIGH` block at the top
   - `uniform vec2 resolution;` and `uniform float time;`
   - the `mainImage` line
   - a `void main() {` … `}` at the end

   The preview runs (a colour gradient). Take screenshot 1.
3. Select from `void main() {` to the very end:
   - long-press the word `void` of the `void main()` line;
   - drag the end handle to after the last `}`;
   - tap **Cut**.

   Wait 2 s and take screenshot 2.
   - BEFORE: the text was rewritten at once: a second `#ifdef GL_FRAGMENT_PRECISION_HIGH` block at the top and a new `void main()` at the end.
   - AFTER: the text ends after the `mainImage` line, exactly as cut, with only one precision block. The error button (!) may show because `main` is missing; that is expected.
4. Put the cursor at the very end and **Paste**. Wait for the compile and take screenshot 3.
   - BEFORE: an error "'main' : function already has a body", and the text holds two `main()`s.
   - AFTER: no error, one `main()`, and the preview runs as in step 2.
5. Regression check (AFTER only):
   - select all text and Cut; the editor is empty;
   - send the shader again as in step 2.

   It is converted again, as in step 2. Take screenshot 4.

## 2. `upstream-fix-hex-uint-suffix`

1. Fresh install, then start the app.
2. Tap at the end of the line `uniform float time;`. Press Enter and type `const uint A = 0x7fu;`. Then press Enter and type `const uint B = 127u;`.
3. Hide the keyboard and take screenshot 1, zoomed into those two lines if possible.
   - BEFORE: on the `A` line, `0x7f` has the number colour and `u` has a different colour (the name colour); on the `B` line, `127u` is all number colour.
   - AFTER: `0x7fu` is all number colour, like `127u`.

## 3. `upstream-fix-live-wallpaper-optional` (#227)

A device without the live-wallpaper feature (many Android Go phones) is not available, and an emulator cannot remove a system feature. So this item checks the manifest and checks that nothing changed on normal devices.

1. On the host, with Android SDK build-tools (any recent version):
   ```sh
   aapt dump badging master.apk | grep -i wallpaper
   aapt dump badging live-wallpaper-optional.apk | grep -i wallpaper
   ```
   - BEFORE: the output contains `uses-feature: name='android.software.live_wallpaper'`.
   - AFTER: it contains `uses-feature-not-required: name='android.software.live_wallpaper'` instead.

   Save the output as `aapt.txt`.
2. On each device run `adb shell pm list features | grep live_wallpaper`. Note the result; it is expected to print `feature:android.software.live_wallpaper`.
3. Fresh install of the fix build, then start the app.
   - Open the Menu: **Set as wallpaper** is listed. Take screenshot 1.
   - Tap it: the system live-wallpaper preview for Shader Editor opens, as on master. Take screenshot 2, then press Back.
4. Menu → Settings: the first section is **Wallpaper**, with **Shader** and **Save battery**. Take screenshot 3.

AFTER on both devices: the same as BEFORE. If anything wallpaper-related is missing on a device that lists the feature, that is a failure.

## 4. `upstream-fix-export-database-legacy`

Before each export, list the Downloads folder and save the output:

```sh
adb shell ls -l /sdcard/Download/
```

### Android 6, BEFORE (master.apk)

1. Fresh install, then start the app.
2. Menu → Settings, scroll to the bottom, tap **Export database**. Note the toast: expected "Cannot write database", with no permission dialog. Take screenshot 1.
3. Grant the permission: `adb shell pm grant de.markusfisch.android.shadereditor.debug android.permission.WRITE_EXTERNAL_STORAGE`. Then tap **Export database** again and note the toast. Take screenshot 2. Expected "Exported to Downloads".
4. Tap **Export database** a third time and note the toast. Take screenshot 3. Expected "Cannot write database", because today's file already exists.
5. List Downloads again.

**Important:** if step 3 already shows "Cannot write database" while no `shader-editor-backup-<today>.db` was in Downloads before it, write that down clearly. That would be a cause this change does not cover.

### Android 6, AFTER (export-database-legacy.apk)

1. Fresh install, then start the app.
2. Settings → **Export database**: a permission dialog for storage appears. Tap **Deny**: the toast "Write access to external storage is required for export to work." appears. Take screenshot 1.
3. **Export database** again → **Allow**: the toast "Exported to Downloads" appears. `shader-editor-backup-<today>.db` is in Downloads. Take screenshot 2.
4. **Export database** again: no dialog, "Exported to Downloads", and `shader-editor-backup-<today>_1.db` is in Downloads. Take screenshot 3.
5. Optional: `adb shell rm -r /sdcard/Download` (only on a test emulator), then **Export database** again: "Exported to Downloads", and the folder exists again.
6. Optional: import the exported file with **Import database** and check that the shaders are there.

### Android 16, BEFORE and AFTER

1. Fresh install, then Settings → **Export database** twice.
2. Expected on both builds:
   - no permission dialog;
   - "Exported to Downloads" both times;
   - Downloads holds `shader-editor-backup-<today>.db` and a second file that the system names, for example `shader-editor-backup-<today> (1).db`.

The fix changes nothing here. Take one screenshot per build.

## 5. `upstream-fix-remove-unloadable-texture` (#105)

Make the bundled texture `noise` unloadable by giving it an image blob that cannot be decoded. This takes the same path as a texture that is too big for memory. It needs Python 3 on the host.

1. Fresh install, start the app once, wait 3 s, press Home, then `adb shell am force-stop de.markusfisch.android.shadereditor.debug`.
2. Break `noise` (cmd.exe: use `set P=...` and `%P%`):
   ```sh
   P=de.markusfisch.android.shadereditor.debug
   adb exec-out run-as $P cat databases/shaders.db > shaders.db
   python3 -c "import sqlite3;c=sqlite3.connect('shaders.db');c.execute(\"update textures set matrix=x'00010203' where name='noise'\");c.commit();print(c.execute(\"select name,length(matrix) from textures\").fetchall())"
   adb shell run-as $P rm -f databases/shaders.db-journal
   adb push shaders.db /data/local/tmp/shaders.db
   adb shell "run-as $P sh -c 'cat /data/local/tmp/shaders.db > databases/shaders.db'"
   ```
   The printed list shows `('noise', 4)`. If `run-as` cannot read `/data/local/tmp` on that image, use `cat shaders.db | adb shell "run-as $P sh -c 'cat > databases/shaders.db'"`.
3. Start the app. Menu → **Add uniform/texture** → tab **sampler2D**. `noise` is listed with its thumbnail. Take screenshot 1.
4. Tap `noise`.
   - BEFORE: the toast "Texture was too big to handle" appears and the screen closes. `noise` is still listed. Long-pressing it does nothing, and tapping it again does the same, so there is no way to remove it. Take screenshot 2 while the toast shows.
   - AFTER: a dialog "Texture was too big to handle" with **Remove texture** and **Cancel**. Take screenshot 2.
5. AFTER only: tap **Cancel**. The screen closes and `noise` is still listed (it was not removed). Take screenshot 3.
6. AFTER only: tap `noise` again → **Remove texture**. The screen closes and `noise` is no longer in the list; to confirm, switch tabs and come back. Take screenshot 4.
7. AFTER only: tap `rgba_noise`, which is intact. It opens normally, and its Remove action in the toolbar still asks "Do you want to remove this texture?" as before (answer Cancel). Take screenshot 5.
8. AFTER only: repeat step 4 and press **Back** while the dialog shows. The screen closes and the texture is kept.

## At the end

Uninstall the test build, and reinstall whatever debug build was there before.
