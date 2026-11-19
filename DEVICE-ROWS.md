# Device rows: grey lines and the Here row

**Build commit `8944a402844946b5411245cf07b6b92a84d55c11`** of `github.com/AntonPieper/ShaderEditor`. It is the head of PR #807 (the Here row), on top of PR #806 (grey lines). These rows were written for that commit. Build two apps from it, the debug app and an old-editor variant, as below. This branch (`foreman/dimmed-rows`) holds this checklist, the shader files and the small scripts the rows use. It is never merged.

## 0. Build and set up

On Windows, in `cmd`, from your clone of the repository. In PowerShell write `.\gradlew.bat` instead of `gradlew.bat`.

```
git fetch origin
git worktree add ..\se-trial 8944a402844946b5411245cf07b6b92a84d55c11
git worktree add ..\se-rows origin/foreman/dimmed-rows
cd ..\se-trial
gradlew.bat assembleDebug
copy app\build\outputs\apk\debug\app-debug.apk ..\se-debug.apk
powershell -NoProfile -ExecutionPolicy Bypass -File ..\se-rows\dbscreen.ps1
gradlew.bat assembleDebug
copy app\build\outputs\apk\debug\app-debug.apk ..\se-dbscreen.apk
git checkout -- app/build.gradle.kts app/src/debug/res/values/bools.xml
cd ..\se-rows
```

- If Gradle says the SDK location is not found, copy `local.properties` from your main clone into `se-trial`.
- `dbscreen.ps1` changes two lines. You can also make them by hand: in `app/build.gradle.kts`, `applicationIdSuffix = ".debug"` becomes `".dbscreen"`; in `app/src/debug/res/values/bools.xml`, `project_mode` `true` becomes `false`. The `git checkout` line undoes them.
- `se-debug.apk` is the app under test, package `de.markusfisch.android.shadereditor.debug`. `se-dbscreen.apk` is the same code as the release app's world (shaders in a database, the old editor), package `de.markusfisch.android.shadereditor.dbscreen`. It installs beside the debug app and is only for row 11.

All further commands run in `se-rows` and work the same in `cmd`, PowerShell and a Unix shell. Add `-s <serial>` after `adb` if more than one device is connected.

```
adb install -r ..\se-debug.apk
adb shell am start -n de.markusfisch.android.shadereditor.debug/de.markusfisch.android.shadereditor.activity.SplashActivity
```

If the install says `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the installed debug app was signed with another key. Stop and ask; do not uninstall it, because it holds that device's files.

In the app's Settings check: **New editor** on (the default in the debug app), **Run mode** "Auto run on change" (the default). Note the **Update delay**; "the next build" below means that long after the last key.

## What is under test

The new editor draws the lines of a shader that the last build does not compile in the grey of comments. Look up of a macro use shows a row **Here** with what that use expands to.

After a key on a line that may change a condition (a line with `#`, a backslash, `/*` or `*/`):
- The grey **above** the edit stays as it is.
- The grey **below** it is worked out again in the background right after the key, without a run. Until the answer arrives the old grey below stays. If no answer arrives within 50 ms, the grey below the edit goes until one does.
- A line typed inside a grey block is grey at once.
- An edit of an `#include` or `#pragma` line removes all grey until the next build.
- A file the running shader uses, such as a pass, shows the last build's grey as soon as it is opened.

So the one wrong picture allowed is old grey (or missing grey) on lines below the edit, for at most 50 ms after the key.

## Rules

- Do not open the system file picker. Load files only by the `run-as` copy in row 1.
- Touch no app but ShaderEditor, Settings, Gboard and TalkBack.
- Put back every setting you change: font size, TalkBack, run mode, Picture behind code.
- Line numbers below are the editor's, from 1.

## Recording the timing rows

Rows 2 to 4 are about what is on screen within 50 ms of a key, too fast for screenshots. Record the screen during the keys:

```
adb shell screenrecord --time-limit 20 /sdcard/r2.mp4
adb pull /sdcard/r2.mp4
adb shell rm /sdcard/r2.mp4
```

The first command records for 20 seconds; type the keys during that time (start it in a second terminal if the first is busy). Then step through the video frame by frame. With ffmpeg, `ffmpeg -i r2.mp4 -vf fps=60 r2/%04d.png` gives one picture per 16.7 ms. For each key, find the first frame that shows the typed character and count the frames until the grey changes. Name recordings after the row (`r2.mp4`, `r3.mp4`, ...).

Type with Gboard taps. `-` and `!` are on Gboard's `?123` page.

## 1. Import without the picker

The app must have been started once (step 0), so its folder exists.

```
adb shell rm -rf /data/local/tmp/se-rows
adb push Macros /data/local/tmp/se-rows/Macros
adb push Extra/Passes /data/local/tmp/se-rows/Passes
adb push Extra/Long /data/local/tmp/se-rows/Long
adb push send-switches.sh /data/local/tmp/se-rows/send-switches.sh
adb push send-parameters.sh /data/local/tmp/se-rows/send-parameters.sh
adb shell run-as de.markusfisch.android.shadereditor.debug cp -r /data/local/tmp/se-rows/Macros /data/local/tmp/se-rows/Passes /data/local/tmp/se-rows/Long files/user-files/Shaders/
adb shell run-as de.markusfisch.android.shadereditor.debug ls files/user-files/Shaders/Macros files/user-files/Shaders/Passes files/user-files/Shaders/Long
adb shell am force-stop de.markusfisch.android.shadereditor.debug
adb shell am start -n de.markusfisch.android.shadereditor.debug/de.markusfisch.android.shadereditor.activity.SplashActivity
```

(`force-stop` drops a live wallpaper of this app, if the device uses one.)

**Expected:**
- The `ls` lists, for Macros: Lines.frag, Nested.frag, Parameters.frag, README.md, Redefined.frag, ShaderToy.frag, Switches.frag, Template.frag. For Passes: Main.frag, Pass.frag. For Long: Long.frag.
- The app's file list shows the folders Macros, Passes and Long with those files.
- `Macros/README.md` describes every example.

**Evidence:** a screenshot of the file list (`r1.png`).

## 2. A key on a line with `#`: nothing blinks, the block flips once

File: `Macros/Switches.frag`. Tap it in the file list; it runs. **Before any key**, lines 15, 34, 37, 38 and 51 are grey, and nothing else is.

Record (`r2.mp4`) while you do these keys, a few seconds apart:

a. Put the cursor just before the `2` of line 9, `#define AA 2`, and type `-` (it reads `#define AA -2`).
   **Expected:** lines 26 to 32 turn grey and line 34 turns plain, in the frame of the key or within 3 frames (50 ms) after it. Lines 15, 37, 38 and 51 stay grey in every frame. No frame shows all grey gone.
b. Delete the `-`. **Expected:** the reverse, just as fast: 26 to 32 plain, 34 grey.
c. Delete the `2` (the line reads `#define AA `). **Expected:** line 34 turns plain, because with nothing after `AA` the app cannot tell what `#if AA > 1` does. Lines 26 to 32 stay plain; 15, 37, 38 and 51 stay grey.
d. Type `1`. **Expected:** lines 26 to 32 turn grey; nothing else changes.
e. Delete the `1` and type `2` again. **Expected:** 26 to 32 plain after the delete, 34 grey after the `2`.
f. Put the cursor just before the `0` of line 36, `#if 0`, and type `!` (it reads `#if !0`). **Expected:** lines 37 and 38 turn plain. Lines 15 and 34, **above** the edit, and line 51, below it, stay grey in every frame.
g. Delete the `!`. **Expected:** lines 37 and 38 grey again.

In each step a line changes at most once, and only the lines named change. Wait past the next build after the last key. **Expected:** the build changes nothing.

**Evidence:** `r2.mp4`, and for each step the number of frames from the key to the change.

## 3. Ordinary lines: grey kept, nothing re-run

File: `Macros/Switches.frag`, as on disk (line 9 `#define AA 2`; lines 15, 34, 37, 38, 51 grey). Record `r3.mp4`.

a. At the end of line 40 (`vec3 col = mix(...)`), type ` x`. **Expected:** no grey line changes in any frame.
b. At the end of line 34 (`v = disc(uv);`, grey), type ` // y`. **Expected:** line 34 stays grey in every frame.
c. Still at the end of line 34, press Enter and type `v += 0.0;`. **Expected:** the new line 35 is grey from its first frame. Lines 15, 34, 35, 38, 39 and 52 are grey (the lines below moved down by one).
d. Undo until the file is as it was. **Expected:** lines 15, 34, 37, 38 and 51 are grey.

**Evidence:** `r3.mp4`.

## 4. A 2,000-line file

File: `Long/Long.frag` (1,999 lines; 248 blocks `#if AA > 1 ... #else ... #endif`). Tap it; it runs.

a. **Expected:** every `return 0.0;` line is grey: lines 15, 23, 31, and every 8th line after them up to line 1991. No `return length(...)` line is grey. Scroll to the end: the same there. Scrolling is as smooth as in a file without grey.
b. Record `r4.mp4`. Put the cursor before the `2` of line 9, `#define AA 2`, and type `-`.
   **Expected:** all 248 blocks flip at once: `return length(...)` lines (13, 21, 29, ...) grey and `return 0.0;` lines plain. Check the lines in view, then scroll to the end and check there too.
   If the flip takes more than 50 ms on this phone, the frames from 50 ms until the flip show no grey below line 9; that is allowed. Report how many frames the flip took.
c. Delete the `-`. **Expected:** the flip back, just as fast.
d. At the end of line 1995 (`float v = ...`), type a few letters and delete them. **Expected:** no lag on the keys, no grey line changes.

**Evidence:** `r4.mp4`, frames from the key to the flip for b and c, and two screenshots after b (top and end of the file).

## 5. Manual run mode

Settings → **Run mode** → "Run manually". A **Run code** button (▶) is now in the toolbar. File: `Macros/Switches.frag`.

a. Tap **Run code** once. **Expected:** lines 15, 34, 37, 38, 51 grey.
b. Type `-` before the `2` of line 9 and do **not** tap Run code. **Expected:** right after the key lines 26 to 32 grey and line 34 plain, as in row 2a. The picture does not change (no run).
c. Tap **Run code**. **Expected:** the grey does not change.
d. Delete the `-`. **Expected:** the flip back right after the key, without a run.
e. Open `Macros/Parameters.frag` and tap **Run code**. Long-press the first `RING` in line 19 and tap **Look up**.
   **Expected:** **Here** `smoothstep(0.02, 0.0, abs(length(uv) - (0.3)))`.
   Close the sheet, type a space at the end of line 22, and look up the same `RING` again. **Expected:** no **Here** row: after an edit it is gone until the next run.
   Tap **Run code** and look up again: **Here** is back. Delete the space.

Set **Run mode** back to "Auto run on change". **Evidence:** screenshots after b and c (`r5b.png`, `r5c.png`), and of the three sheets in e.

## 6. An edit that may change which programs there are

File: `Passes/Main.frag`. Tap it; it runs. **Expected:** line 18 (`col *= 1.0;`) is grey.

a. At the end of line 4, `#pragma se input "Pass.frag"`, type a space. **Expected:** all grey goes at once (this is meant: such an edit may change which programs there are). After the next build, line 18 is grey again.
b. Delete the space. **Expected:** the same: grey gone, back after the next build.

**Evidence:** a screenshot right after a and one after the next build.

## 7. A second pass with its own switches

Files: `Passes/Main.frag`, then `Passes/Pass.frag`. `Main.frag` reads `Pass.frag` as an input pass.

a. Tap `Main.frag`; it runs. **Expected:** line 18 grey.
b. Tap `Pass.frag`. It opens without running on its own, because `Main.frag` uses it; the picture stays `Main.frag`'s. **Expected:** line 18 (`float v = smoothstep(...)`) is grey at once, from `Main.frag`'s last build.
c. Delete the `1` of line 9, `#define RINGS 1`. **Expected:** right after the key line 18 turns plain, because with nothing after `RINGS` the app cannot tell. Type `0`: line 16 (`float v = 0.5 + ...`) turns grey.
d. Delete the `0` and type `1`. **Expected:** line 16 plain, then line 18 grey.
e. Wait past the next build, then tap `Main.frag`. **Expected:** line 18 of `Main.frag` grey at once.
f. Settings → **Run mode** → "Run manually". In `Main.frag`, type a space at the end of line 20. Tap `Pass.frag`. **Expected:** no grey in `Pass.frag`, because `Main.frag` changed since the last build. Tap **Run code**: line 18 grey. Go back to `Main.frag`, delete the space, tap **Run code**, and set **Run mode** back to "Auto run on change".

**Evidence:** screenshots after b, c and f.

## 8. Switching files and back

Files: `Macros/Switches.frag` and `Macros/Template.frag`.

a. With `Switches.frag` shown (lines 15, 34, 37, 38, 51 grey), tap `Template.frag`. It runs.
   **Expected:** after its build only line 18 is grey. Lines 2 and 4 are **not** grey (in ES 1.00 only the driver knows `GL_FRAGMENT_PRECISION_HIGH`).
b. Tap `Switches.frag`. **Expected:** after its build lines 15, 34, 37, 38, 51 grey.
c. Switch back and forth three times quickly.
   **Expected:** no frame shows grey on a line where the shown file has none. In particular `Template.frag` never shows grey on 15 or 34, and `Switches.frag` never on 18.
   A moment with no grey right after a switch is expected, until that file's build.

**Evidence:** a recording `r8.mp4` of c.

## 9. Rotation

File: `Macros/Switches.frag`, as on disk.

a. Rotate to landscape. **Expected:** after the screen is rebuilt and the build has run, lines 15, 34, 37, 38, 51 are grey; no other line is grey at any moment.
b. In landscape, do rows 2a and 2b (`-` before the `2`, then delete it). **Expected:** as in row 2.
c. Type the `-` again and rotate back to portrait. **Expected:** after the build, lines 15, 26 to 32, 37, 38, 51 are grey. Delete the `-`.

**Evidence:** screenshots after a and c.

## 10. Grey over the brightest picture

Settings → **Picture behind code** → "Bright". File: `Macros/Switches.frag`, whose picture is a gold disc in the middle of the screen.

Scroll the code so that line 34 (grey) lies across the middle of the disc. Then type `-` before the `2` of line 9, so lines 26 to 32 turn grey, and scroll so that they lie over the disc.

**Expected:** the grey lines are readable over the brightest part of the disc and read as comment grey, clearly unlike the coloured code around them. Delete the `-` and set **Picture behind code** back to "Balanced".

**Evidence:** two screenshots (`r10a.png` with line 34 over the disc, `r10b.png` with lines 26 to 32), and your judgement of readability.

## 11. The old editor shows nothing

Uses `se-dbscreen.apk` from step 0.

```
adb install ..\se-dbscreen.apk
adb shell am start -n de.markusfisch.android.shadereditor.dbscreen/de.markusfisch.android.shadereditor.activity.SplashActivity
adb shell sh /data/local/tmp/se-rows/send-switches.sh
```

The last command hands the app the text of `Switches.frag` as a share. It opens as a new shader in the old editor (**New editor** is off by default in this variant).

a. **Expected:** no line is grey; lines 15, 34, 37, 38, 51 have syntax colours like the rest. Typing `-` before the `2` of line 9 changes no colours.
b. Settings → **New editor** on, open the same shader again. **Expected:** still no grey.
c. Run `adb shell sh /data/local/tmp/se-rows/send-parameters.sh` (the text of `Parameters.frag`, as another new shader). With **New editor** still on, long-press the first `RING` in line 19. If **Look up** is offered, its sheet has the definition but no **Here** row.

Afterwards: `adb uninstall de.markusfisch.android.shadereditor.dbscreen`

**Evidence:** screenshots of a, b and c.

## 12. Where Look up sits with Gboard

File: `Macros/Parameters.frag`. Long-press the first `RING` in line 19. The name is selected and a bar appears over it.

**Expected:** **Look up** is in the bar or under its ⋮, and Gboard does not cover the bar. Note which, with the keyboard open and with it closed. Tap it: the sheet shows the definition of `RING` and **Here** `smoothstep(0.02, 0.0, abs(length(uv) - (0.3)))`.

To look up another name: close the sheet and tap an empty place below the code first. A long-press while a word is still selected can keep the old word.

**Evidence:** screenshots of the bar with the keyboard open and closed, and of the sheet.

## 13. The Here layout at the largest font and in landscape

Android Settings → Display → font size (Android 17: "Display size and text") → the largest. File: `Macros/Parameters.frag`, `MIX3` in line 20 → Look up. Then `Macros/Nested.frag`, `SPIN` in line 19.

**Expected:**
- In portrait and in landscape, every line of the Here row fits the sheet. Nothing is cut off at the right edge, and nothing needs sideways scrolling.
- The code is laid out like a formatter would: `mix(` with its arguments one per line, an inner call that fits kept whole on its line, one that does not fit broken the same way.
- Landscape has fewer breaks.
- The sheet scrolls if the row is taller than the screen.

For reference, the Here texts unbroken are:

- `MIX3`: `mix(mix(vec3(0.1, 0.2, 0.5), vec3(0.9, 0.4, 0.2), smoothstep(0.0, 0.5, length(uv))), vec3(1.0, 0.9, 0.6), smoothstep(0.5, 1.0, length(uv)))`
- `SPIN`: `mat2(cos(((time * 0.05) * (2.0 * 3.14159265))), sin(((time * 0.05) * (2.0 * 3.14159265))), -sin(((time * 0.05) * (2.0 * 3.14159265))), cos(((time * 0.05) * (2.0 * 3.14159265))))`

Set the font size back. **Evidence:** four screenshots (two files, portrait and landscape).

## 14. TalkBack reads the Here row

Turn TalkBack 17 on. File: `Macros/Parameters.frag`. Select the first `RING` of line 19 with TalkBack's long-press (double-tap and hold), choose **Look up**, then swipe right through the sheet.

**Expected:**
- After the definition, TalkBack reads "Here" and then the expansion `smoothstep(0.02, 0.0, abs(length(uv) - (0.3)))` as **one** item, read once, without pauses where the layout breaks lines.
- Do the same for `MIX3` in line 20: its long expansion is one item too.
- Nowhere does TalkBack say anything about grey lines (grey is only a colour).

To get the spoken text, set TalkBack Settings → Advanced → Developer settings → Log output level "Verbose" and read `adb logcat -v raw` lines with `text="`. Put the log level back afterwards, and turn TalkBack off at the end.

**Evidence:** the spoken lines from the log, or a screen recording with sound.

## When you are done

```
adb shell rm -rf /data/local/tmp/se-rows
```

Leave the imported folders in the app; they do no harm. Report per row: pass or fail, the evidence files, the frame counts in rows 2 to 4, and anything that surprised you.

## What is in this branch

- `DEVICE-ROWS.md`: this checklist.
- `Macros/`: the example shaders, the same as `app/src/test/resources/directives/macros/` at the commit above, with a README of what each shows.
- `Extra/Passes/`: a shader with a second pass, each with a switch of its own.
- `Extra/Long/`: a 1,999-line shader. Both `Extra` shaders compile in Mesa's compiler and in the app without errors.
- `send-switches.sh`, `send-parameters.sh`: run on the device by row 11; they share a file's text with the old-editor variant.
- `dbscreen.ps1`: makes a checkout the old-editor variant (step 0).
- `.gitattributes`: keeps every file's line ends as they are, so the scripts run on the device from a Windows checkout too.
