# newprompt-2: the Turn on prompt for a missing remembered file (fork draft PR #828 at 87e432c5b)

Built the debug app of fork draft PR #828 (`fix/new-editor-prompt-gone-file`) at **87e432c5b8969626fbb4ec0c890d3284dc66a0fb**. That was exactly the head. The commit changes only `ProjectFileManager.open`, so that with the old editor in use the remembered file is loaded first: Turn on is offered only if the file is there, and otherwise the load fails as usual. Tested on Pixel_API_37 (Android 17) only, starting from my tuning-4 data: New editor off, and the library remembers `Macros/Parameters.frag`, which does not exist.

**All four cases pass.**

| Case | Expected | Seen | Screenshots | Result |
|---|---|---|---|---|
| A. Start, remembered file missing, New editor off | No Turn on prompt; the load error for the missing file; the stand-in for a lost file | **No prompt.** Snackbar: **"Macros/Parameters.frag cannot be opened: File not found: Macros/Parameters.frag"**. The screen shows the stand-in **"No file open. Create a shader or pick an example."** (title "Shaders"). `new_editor` stays false. The app's logcat has no error | a-1s (splash), a-3s (message), a-12s (message gone) | Pass |
| B. Remembered file exists (TimeQ.frag opened with the new editor on, setting then off, app force-stopped and started) | Prompt as before; Turn on within 10 s opens that file in the new editor | Prompt **"Files of a folder open in the new editor"** with **Turn on**, over "No file open". Turn on tapped about 3 s after start: the screen is rebuilt with **TimeQ.frag** open in the new editor (its error row for line 12 shows); `new_editor` false → **true** | b-prompt, b-after | Pass |
| C. Setting off, tap a folder file in the drawer (IncQ.frag) | Prompt as before; Turn on opens it | Same prompt; Turn on: **IncQ.frag** open in the new editor (its include rows show); `new_editor` → true | c-prompt, c-after | Pass |
| D. New editor on, restart | Start-up opens the remembered file, nothing else | Restart with IncQ.frag remembered: IncQ.frag opens, no prompt. Repeated with **NumsR2.frag**, a file that compiles: it opens and runs (58 fps), with no prompt and no message | d-start, d-start2, d-start3 | Pass |

## Oddity (not from this PR)
- When the remembered file does not compile (TimeQ.frag with `a17mine` on line 12, or IncQ.frag with missing includes), a force-stop and restart shows the crash guard's message: **"Shader Editor closed while TimeQ.frag was starting. It does not run again by itself."** with **Run code** (d-start, d-start2).
- `pending_crash_file` stays set while such a file is shown, even 8 s after it opened. So a file that never builds counts as "starting" for as long as it is open, and any kill looks like a crash.
- With a file that builds (NumsR2.frag), `pending_crash_file` is empty and no message appears (d-start3).
- This PR does not touch the guard. I did not check whether the integration head does the same.

## Note
After A, the library still remembers `Macros/Parameters.frag` (`no_backup/projects/otlkosmo/shown`), so every start with the old editor shows the same load error again until another file is opened. The new editor does the same in this state (newprompt-1, case A after Turn on).

## Cleanup
Pixel_API_37:
- Earlier fork build back (sha1 f706f08a), app data byte-identical to the backup (141/141).
- Font 1.0, density 420, rotation 1/0, Gboard, no accessibility service.
- Shut down, and the agent-device session list is empty.

Machine: the worktree `ShaderEditor-np2` is removed and no Gradle daemon is running. The APK is not pushed.
