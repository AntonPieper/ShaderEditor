# a6-only: Android-6-only, timing, or steps?

Debug build of origin/integration/project-mode at 8d016bf9 (de.markusfisch.android.shadereditor.debug), new editor on.
Library folder: /data/data/de.markusfisch.android.shadereditor.debug/files/user-files/Shaders (run-as path files/user-files/Shaders).
Fixtures Akeep.frag, Agone.frag, Bfile.frag: 7 lines + newline, line 2 `// <name>`.
Keyboard: AOSP LatinIME on Pixel_API_23, Gboard on Pixel_API_37; text typed with `adb shell input text` (key events) unless noted.
marks.txt has the timestamp of every step; logcat-*-ourpackage.txt is the log of our package's processes only.

## A: drawer keeps listing a file deleted outside
Deleted with the app IN FRONT: REPRODUCED API 23 4/4 (A23-1, -8, -9, -10), API 37 5/5 (A37-1, -2, -3, -7, -8, -9).
Deleted with the app in the BACKGROUND (Home, rm, return): NOT, API 23 0/6 (A23-2..7), API 37 0/3 (A37-4..6).
Steps: force-stop; push Agone.frag; launch; Akeep.frag shown; drawer closed; app in front:
`adb shell "run-as de.markusfisch.android.shadereditor.debug rm files/user-files/Shaders/Agone.frag"`; wait 1.5 s;
tap in code, MOVE_END, `input text y`, close keyboard, menu > Save; open drawer: Agone.frag row still listed.
Clears: Home + return (onResume re-reads), or Up to Folders and back into Shaders. A swipe down does not.
Tapping the stale row: if Agone had been opened before in that process it opens with its old text and no bar (A23-1, A37-9); never opened: the drawer closes and nothing opens (A37-8, 1 try).
Deleting the SHOWN file in front then Save: not re-created; drawer lists Agone.frag twice ("Not in this folder" + "All files", unsaved dot + conflict icon); after Home + return the bar says "deleted outside ShaderEditor and has unsaved text here" (A23-10, A37-3).

## B: one Undo after Reload + typing takes back typing AND reload
API 23 (only device run; API 37 not reached):
- Reload, tap, MOVE_HOME, MOVE_END, ENTER, `input text x_after`, wait 5.5 s, Undo: NOT 0/3 (B23-1..3), only the typing is undone.
- V1 typed immediately (0 s), V2 after 5.5 s pause, V4 Enter, V5 AOSP soft-key taps, V7 helper IME + key events, V8 paste: NOT, only the last typed step goes.
- V3 Reload without typing, one Undo: the unsaved `//mine` comes back (as documented).
- V6 helper IME + `agent-device type`: the text never reaches the editor on API 23; Undo then undoes the Reload.
- F1-F3, first-pass sequence with a process restart: REPRODUCED 3/3. After Reload: no tap; keyevent 122, 123, 66; `input text after_reload`; wait 5.5 s; `am force-stop`; relaunch; menu > Undo -> text back to the unsaved `}//mine` (no after_reload, no `// outside`).
  After a restart the journal holds the text after Reload + typing, and the text the Reload replaced is kept beside it as the single step undo leads to.
