# newprompt-1: the new-editor prompt's "Turn on" (integration head b5c100ab)

Built the debug app of `origin/integration/project-mode` at **b5c100ab4269f64bc8a7528d981d33b135d83f6e** (as expected). Pixel_API_37 (Android 17) only.

**Verdict: the button works; it did not reproduce as a broken button.** In tuning-4, my tap came after the prompt had already gone: it lasts 10 s and my helper's lookup took longer, and my log from then shows "NO RECT Turn on". So "it did nothing" was my timing. One real, smaller effect showed up, though. In the tuning-4 data state, Turn on does switch the editor, but the file it then opens no longer exists. The screen therefore still says "No file open" and adds "Macros/Parameters.frag cannot be opened: File not found: Macros/Parameters.frag". That looks like nothing happened. Android 6 was not run, as the task said (only if it reproduces on 17).

## 1. Which prompt
- **What:** a Snackbar at the bottom: **"Files of a folder open in the new editor"** with the action **"Turn on"** (p1-before / p2-before-tap). The snackbar text is at [63,2190][823,2315] and the action, id `snackbar_action`, at [844,2190][1038,2316]. It stays **10 s** (`NEEDS_NEW_EDITOR_MILLIS = 10000`).
- **Strings:** `app/src/main/res/values/strings.xml:483` `file_needs_new_editor`, `:484` `turn_on`. The English text is also copied in values-ru, -uk, -zh, -zh-rTW.
- **Code:**
  - `LibraryScreen.java:1190–1200` `onNeedsNewEditor(Runnable turnOn)` → `Message.make(...).setAction(R.string.turn_on, v -> { leaveNotMoved(); turnOn.run(); })`.
  - It is called from `ProjectFileManager.java:304` (a file tapped in the drawer) and `:315` (at screen start, after `onGone()`).
  - The caller is `ProjectFileManager.open(...)` at `:292`, when `!preferences.useNewEditor()`.
  - `turnOn` (`:296–302`) is `setUseNewEditor(true)`, `askedFor = new Asked(...)`, `activity.recreate()`.
- **State that leads to it:** the setting "New editor" is off (`new_editor` false), and either:
  - (a) the app starts while the library remembers a folder file. In this data, `held_file` = `otlkosmo\nNames/Names.frag`, and `no_backup/projects/otlkosmo/shown` and `running` = `Macros/Parameters.frag`. The screen shows "No file open" with the prompt.
  - (b) a file is tapped in the drawer.

  It is not a first-start prompt.

## 2. Tapping it
| Case | Before | After | `new_editor` | Logcat (our pid) |
|---|---|---|---|---|
| A. Start with the tuning-4 data (remembered file gone), tap 3 s after start | p2-before-tap: prompt over "No file open" | p2-after-tap: still "No file open", snackbar **"Macros/Parameters.frag cannot be opened: File not found: Macros/Parameters.frag"** | false → **true** | Activity recreated; no exception |
| B. Setting turned off in Settings with TimeQ.frag open; drawer → TimeQ.frag → prompt → tap within 2 s | p5-before-tap: prompt | p5-after: **TimeQ.frag open in the new editor** | false → **true** | `I/ActivityThread: Schedule relaunch activity: …MainActivity`, then onDestroy/new activity; no error |
| (my slip) tap more than 10 s after the prompt appeared | prompt already gone | nothing (p4-after) | stays false | nothing |

- **Leave and return** (Home, then the launcher intent) after A: the new editor stays on. The drawer lists the files, and a tap on TimeQ.frag opens it in the new editor (p3-timeq).
- TimeQ.frag was not changed. Its line 12 `a17mine` was already in the backup (same md5).

## 3. Likely cause of the "nothing happened" look (case A; no fix, no commit)
With the old editor, `ProjectFileManager.open` offers "Turn on" for the remembered file without checking that it still exists, because it never loads it. After `recreate()`, the new editor loads `askedFor` (Macros/Parameters.frag), finds it missing and falls back to "No file open" with a load error. So the switch happened, but the empty screen looks the same as before; only the error message differs.

## Cleanup
Pixel_API_37:
- Earlier fork build back (f706f08a), app data byte-identical to the backup (141/141).
- Font 1.0, density 420, rotation 1/0, Gboard, no accessibility service.
- Shut down, and the agent-device session list is empty.

Machine: the worktree `ShaderEditor-np1` is removed and no Gradle daemon is running. The APK and the extracted data copy are not pushed.
