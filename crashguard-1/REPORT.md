# crashguard-1: the crash guard with a shader that never builds

Builds:
- **Fork integration head c8a1dbd458d5052a74877a022c7b46ddecf4067a** (`origin/integration/project-mode` after `git fetch`, exactly the expected commit; it merges upstream 190471515). Two apps from it:
  - the debug app (library, new editor);
  - the old-editor variant via `dbscreen.ps1` (database world, package `.dbscreen`), for the "database shader" cases.
- **Upstream master 1904715152cab451a4ef607421255cae347b6480** debug app (database, old editor; same package id as the fork's debug app, so the fork app's data was backed up first and restored after).

All on Pixel_API_37 (Android 17), run mode "Auto run on change". Test files:
- `Bad.frag`: `gl_FragColor = vec4(oops, 1.0);`, which never compiles.
- `Good.frag` / `Good2.frag`: compile.
- The database cases used the same text, shared in as a new shader, and the app's own "Default".

**Answer: it is an upstream defect, and the fork's new library guard has the same flaw.**
- In both worlds the marker is set when a shader or file is handed to the engine and cleared only by a drawn frame (or by leaving the screen).
- A program that fails to compile never draws a frame, so the marker stays set for as long as it is shown. Any later process death (force-stop, an OOM kill, an update) then counts as a crash.
- Upstream and the fork's database world rewrite the shader into `#if 0` ("The previous shader crashed Shader Editor and was commented out"). The fork's library shows "Shader Editor closed while X was starting" and holds the file.

## Results

| # | Case | Marker before the force-stop | After the start | Result |
|---|---|---|---|---|
| 1 | Fork, folder file `Bad.frag` (never compiles), open 10 s | `pending_crash_file` = `otlkosmo\nBad.frag` | **"Shader Editor closed while Bad.frag was starting. It does not run again by itself."** + Run code; `held_file` = Bad.frag (f1-start) | Message (wrong) |
| 1 | Fork dbscreen, database shader with `oops` (saved, selected from the list, 10 s) | `pending_crash_shader` = 2 (from the moment it was selected) | Shader **rewritten**: `// Shader Editor disabled this shader because it caused a crash while loading.` / `#if 0` … `#endif` (d1-start2, d1-start3) | Rewritten (wrong) |
| 1 | **Upstream**, database shader with `oops` (saved, selected, 10 s) | `pending_crash_shader` = 2 for the whole 10 s | Shader **rewritten** with the same `#if 0` wrapper; toast **"The previous shader crashed Shader Editor and was commented out."** (u1-start2) | **Same defect upstream** |
| 2 | Fork, folder file `Good.frag` (compiles, 59 fps) | `pending_crash_file` empty | No message, runs | Pass |
| 2 | Fork dbscreen, "Default" (compiles) | `pending_crash_shader` = 0 | No message | Pass |
| 2 | Upstream, "Default" | 0 | No message, 59 fps | Pass |
| 3 | Fork, folder file `Good2.frag` runs, then `x` typed at the end of line 4 (error), 10 s | `pending_crash_file` empty (the earlier frame cleared it, and a drawn file is not marked again in this process) | No message at once. **But** the unsaved draft with `x` comes back after the start, and that start builds it, so the marker is set again (`otlkosmo\nGood2.frag`) and stays. A **second** force-stop shows "Shader Editor closed while Good2.frag was starting" (f3-start, f3-start2) | No message first time; message on the second kill |
| 3 | Fork dbscreen, "Default" with `x` typed in line 14, 10 s | 0 (the previous program keeps drawing, 59 fps) | No message, Default runs | Pass |
| 3 | Upstream, "Default" with `x` typed in line 14 (error row shown, 59 fps from the old program), 10 s | 0 | No message | Pass |

Note on the database case:
- The first time I opened the shared `oops` shader in dbscreen, the previous shader kept drawing (59 fps), which cleared the marker. After a force-stop the app started on "Default".
- The rewrite happened only once the broken shader was selected from the list and nothing drew.
- The code rewrites at start (`recoverFromCrashIfNeeded` in `onCreate`). The shader's modified time (01:20:05 device time) and the logcat restart (01:20:04.7) put it at the start after my force-stop.
- One thing I cannot explain: my screenshot `d1-open2`, saved by the script just before the force-stop, already shows the wrapper. The emulator's clock and the host's may differ by a few seconds, but the order in the script says the screenshot came first. Upstream's case 1 reproduces the same rewrite with clear ordering.

## 4. The code (fork c8a1dbd45)

**Library (folder files), `RunGuard`.** `activity/managers/RunGuard.java`:
- `running()` sets the file pending: lines 63–70; `store.setPending` writes `pending_crash_file`.
- It is cleared only by `ran()` (a frame of it was drawn, lines 73–78) or `left()` (the screen left the front, lines 81–86).
- On the next start, a non-empty `pending` becomes `held` (lines 41–51).

The callers in `LibraryScreen.java`:
- the Store: lines 247–268;
- `guard.running` in `mayRun`: lines 1411–1418;
- `guard.ran()` in `onFrame`: line 808, called from `MainActivity.onFramesPerSecond` when `fps > 0` (`MainActivity.java:314–317`);
- `guard.left()` in `onPause`: line 487.

**Database shaders.**
- `ShaderManager.updatePendingCrashShaderId` (`ShaderManager.java:733–746`) sets `pending_crash_shader` to the selected shader unless it already drew (`drewShaderId`). It is called on select, restore, save, delete and apply (lines 196, 326, 416, 613, 685).
- It is cleared to 0 in `MainActivity.onFramesPerSecond` when `fps > 0` (`MainActivity.java:316`) and in `onPause` (`MainActivity.java:253`).
- At start, `recoverFromCrashIfNeeded` (`MainActivity.java:691–706`) wraps the shader in `#if 0` and shows `shader_disabled_after_crash`.

**Upstream 190471515:**
- `updatePendingCrashShaderId` (`ShaderManager.java:502–507`) sets the selected shader whenever the run mode runs in the background, on lines 225, 310, 460, 498.
- It is cleared only in `onFramesPerSecond` with `fps > 0` (`MainActivity.java:269`) and in `onPause` (`MainActivity.java:164`).
- `recoverFromCrashIfNeeded` is at `MainActivity.java:510–524`.

**In two sentences:** the marker is set when a program is handed to the engine and cleared only by its first drawn frame (or by the screen pausing), because the guard means "the app died between handing it over and drawing it". A program that fails to compile never draws a frame, so its marker stays for as long as it is shown, and any process death in that time, such as a force-stop, an OOM kill in the background, or an update, is taken for a crash caused by it.

**Upstream or fork:** upstream behaves the same (case 1 upstream: rewritten and commented out), so the defect is upstream's. The fork's `RunGuard` keeps the same rule for folder files. There it costs less, because it holds the file without rewriting it, but the message is still wrong. The fork's `drewShaderId` and `drew` set only stop a marker from coming back for a shader or file that already drew in this process (case 3), which upstream does not have.

## Oddities
1. Fork case 3: an unsaved broken edit is restored as a draft on the next start. The draft is built there without drawing, so the guard arms itself again, and only the next kill shows the message.
2. Upstream case 1 was clean only after the shader was selected from the list; directly after the share, the earlier shader kept drawing and cleared the marker.

## Cleanup
Pixel_API_37:
- Fork debug app back to the earlier build (sha1 f706f08a), its data byte-identical to the backup (141/141). The upstream build's data was cleared before the restore.
- The `.dbscreen` variant is uninstalled.
- My test files in `/data/local/tmp` removed (BadC1/BadC2 there are older and were left).
- Font 1.0, density 420, rotation 1/0, Gboard, no accessibility service. Shut down, and the agent-device session list is empty.

Machine: the worktrees `ShaderEditor-cg` and `ShaderEditor-up` are removed and no Gradle daemon is running. No fix, no commit to code. The APKs are not pushed.
