# selectall-1: TalkBack's Select all and selection mode in the new editor

Builds (heads checked, both exact):
- **A: fork PR #834** (`fix/talkback-select-all`) at **3f73edf35dd823e3bc2d717153d68549d220b24e**, "fix(editor): a service's select all selects the whole shader". Debug app, library, new editor.
- **B: upstream PR markusfisch/ShaderEditor#338** (head `AntonPieper:upstream-new-editor`) at **eb47cb664d0d76e5588e3c3929dd9be62ee54624**, "Add a new code editor behind a setting". Debug app (database world), with **New editor** turned on in Settings.

Pixel_API_37 (Android 17, TalkBack 17). Test shader `Sel.frag`, 46 lines (each line 7–43 is `v += 0.01; // line N`, line 46 `// line 46 end`). In A it is a library file; in B it was shared in as text and saved. Caret on line 22/23 (end of line). All TalkBack actions were chosen from TalkBack menu (3-finger tap) → Actions, each item confirmed by its spoken name before the double-tap. Speech is from TalkBack's verbose log (`spoken.txt`, `marks.txt`).

**Summary:**
- **Row 1 passes on both builds:** Select all + Cut clears the **whole** shader, and Undo (Ctrl+Z) brings all 46 lines back. I did not see the "first three lines" fault on either build on this path.
- **Row 2: the advisor's suspicion is confirmed on both builds, identically.** In selection mode, "Move cursor to end" and "Move cursor to beginning" select text **near the top of the file**, not near the caret. The selected range is the caret's offset and the end/start of TalkBack's 3-line window, taken as positions in the whole file.
- **Row 3: not done.** TalkBack is not installed on Pixel_API_23 (`pm list packages` has no TalkBack or accessibility suite), and I did not install it.

## Row 1: Select all, then Cut, then Undo

| Build | Right after Select all (speech) | Seen | Cut (speech) | After Cut | Undo | Result |
|---|---|---|---|---|---|---|
| A fork #834 | **"Selected text: 	v += 0.01; // line 21 / 	v += 0.01; // line 22 / 	v += 0.01; // line 23"**, then **"Selection mode on"** | Every line highlighted, 1–46 (a1-selectall) | "cut, precision mediump float; / … / 	v += 0.01; // line 43 / 	gl_FragColor = vec4(uv, v, 1.0); / } / // line 46 end / . Cleared text" (all 38 "// line N" comments spoken) | Editor empty, one line; "Sel.frag: defines no main, so it cannot run" (a1-cut) | Ctrl+Z: all 46 lines back, still selected (a1-undo) | **Pass** |
| B upstream #338 | **"Selected text: 	v += 0.01; // line 21 / 	v += 0.01; // line 22 / 	v += 0.01; // line 23"**, then **"Selection mode on"** | Lines 21–46 highlighted on screen; the cut shows that all was selected (b1-selectall) | "cut, precision mediump float; / … // line 46 end. Cleared text" (all 38 comments) | Editor empty; "'' : syntax error" (b1-cut) | Ctrl+Z: all back (b1-undo) | **Pass** |

In both builds TalkBack's own announcement after Select all names only the three lines of its window ("Selected text: … line 21 … line 23"), although the whole text is selected.

## Row 2: selection mode, then Move cursor to end / to beginning (caret at end of line 23 in A, line 22 in B)

| Build | Action | Spoken | Selected (picture) | Near the caret? |
|---|---|---|---|---|
| A | Start selection mode | "Selection mode on" | — | — |
| A | **Move cursor to end** | **"End of field. ion; / uniform float time, selected"** | From line 2 "resolut**ion;**" to line 3 "uniform float time" (a2-end, a2-end-top) | **No: near the top** |
| A | Move cursor to beginning (after End selection mode, caret back on line 23, selection mode again) | **"Beginning of field. precision mediump float; / uniform vec2 resolut, selected"** | From the start of line 1 to line 2 "uniform vec2 resolut" (a2-begin) | **No: near the top** |
| B | Move cursor to end | **"End of field. ion; / uniform float time, selected"** | Same as A (b2-end) | **No** |
| B | Move cursor to beginning | **"Beginning of field. precision mediump float; / uniform vec2 resolut, selected"** | Same as A (b2-begin) | **No** |

**Why (from the numbers, not from code):**
- Each line here is 23 characters with its newline. TalkBack's window is the three lines around the caret (lines 22–24 in A), 68 characters long, and the caret sits at offset 45 inside it.
- The selection runs from file offset **45** ("…resolut|ion;") to file offset **68** (after "time") for "end", and from file offset **0** to **45** for "beginning".
- So the window-relative offsets are applied to the whole document instead of being shifted by the window's start. Both builds behave the same here, so #834 does not change this path.

Nothing was changed by row 2. Selection mode was ended each time, and Sel.frag on disk in A was byte-identical to the test file afterwards (md5 40fc30f5).

## Row 3: Pixel_API_23
**Not done:** TalkBack is not installed there, and was not installed by me.

## Oddities
1. B's debug app asked for notification permission on first start (answered "Don't allow"). On restore, the fork app's flag was cleared with `clear-permission-flags … user-set user-fixed`. It now reads `granted=false, flags=[USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]`. I did not record its exact flags before the pass.
2. TalkBack's notification dialog came up once at a TalkBack start (answered "Don't allow").
3. My menu helper once missed the first item because TalkBack prefixes it with "selected."; I redid that step, and the report shows the redone result.

## Cleanup
- **Pixel_API_37:**
  - Fork debug app back to the earlier build (sha1 f706f08a). Its data was cleared once after the upstream build and then restored byte-identical (141/141).
  - TalkBack off (`enabled_accessibility_services` null, `accessibility_enabled` 0), Log output level ERROR, TalkBack notification permission revoked with flags `USER_SENSITIVE_WHEN_GRANTED`.
  - Font 1.0, density 420, rotation 1/0, Gboard. Shut down.
- **Pixel_API_23:** only booted to check for TalkBack; nothing installed or changed. Shut down.
- agent-device session list empty. Worktrees `ShaderEditor-sa` and `ShaderEditor-up3` removed after this push; no Gradle daemon. The APKs and the full verbose logcat are not pushed.
