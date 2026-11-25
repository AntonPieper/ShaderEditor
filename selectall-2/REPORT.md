# selectall-2: TalkBack selection mode and Select all (fork #837 vs upstream #338)

Builds (heads checked, both exact):
- **A: fork PR #837** (`fix/talkback-selection-mode`) at **4920b1168fe60d2746bc2c8e14df5ff74b8ee61f**, "fix(editor): TalkBack's selection mode extends the selection". Debug app, library, new editor.
- **B: upstream markusfisch/ShaderEditor#338** (`AntonPieper:upstream-new-editor`) at **7705fed5033712232881c0d712a7283e1832df65**. Debug app, New editor turned on.

Pixel_API_37 (Android 17, TalkBack 17). Same 46-line `Sel.frag` as selectall-1 (line N says `// line N`). Caret at the end of line 23 via hardware keys before each step. Every TalkBack action is chosen from TalkBack menu → Actions, confirmed by its spoken name. Line moves use TalkBack's reading control "Lines" and swipes down/up, with 2 s between the mode switch and the first swipe. Speech is from TalkBack's verbose log (`spoken.txt`, `marks.txt`).

**Summary:**
- **Row 1: pass on both builds.** End and beginning select from the caret to the end of the next line, or back to the start of the line before. Nothing near the top. The selectall-1 fault is gone in both.
- **Row 2: fails on both builds.** The first swipe by Lines almost always **ends selection mode** ("Selection mode off"); the caret then just moves. The selection grew as expected only once in 3 tries on A, by one line before the next swipe ended it; never on B.
- **Row 3: pass on the key point** (the whole shader is never selected) on both. On A it grew one line, then two lines, in 1 of 2 tries; the other try, and all of B, ended selection mode on the first swipe as in row 2.
- **Row 4: pass on both.** Select all + Cut clears the whole shader (cut speech ends with "// line 46 end … Cleared text"), and Ctrl+Z brings it back.

## Row 1: selection mode, then Move cursor to end / (fresh) to beginning

| Build | Action | Spoken | Selected (picture) | Result |
|---|---|---|---|---|
| A | Move cursor to end | **"End of field"**; at End selection mode: "Selected text:  / 	v += 0.01; // line 24" | From the caret (end of line 23) to the end of line 24 (a1-end) | Pass |
| A | Move cursor to beginning | **"Beginning of field. 	v += 0.01; // line 22 / 	v += 0.01; // line 23, selected"** | From the start of line 22 to the caret (a1-begin) | Pass |
| B | Move cursor to end | **"End of field.  / 	v += 0.01; // line 24, selected"** | End of line 23 to end of line 24 (b1-end) | Pass |
| B | Move cursor to beginning | **"Beginning of field. 	v += 0.01; // line 22 / 	v += 0.01; // line 23, selected"** | Start of line 22 to the caret (b1-begin) | Pass |

## Row 2: selection mode, then down by Lines ×2, and (fresh) up by Lines ×2

| Build | Try | Swipe 1 | Swipe 2 | Picture | Result |
|---|---|---|---|---|---|
| A | down #1 | " / 	v += 0.01; // line 24" — **selection grows to the end of line 24** | **"Selection mode off"**, " / 	v += 0.01; // line 25", "Selection cleared"; the caret moves to the end of line 25, nothing selected | a2-down1, a2-down2 | **Fail** (stops after 1 line) |
| A | down #2 | same as #1 | same as #1 | a2c-down1, a2c-down2 | **Fail** |
| A | up #1 | **"Selection mode off"**, "	v += 0.01; // line 23"; caret to the start of line 23, nothing selected | "	v += 0.01; // line 22 / "; caret to the start of line 22 | a2-up1, a2-up2 | **Fail** |
| A | up #2 | "	v += 0.01; // line 23"; **line 23 selected**, the mode stayed on | (menu, not a swipe) | a2-up1b | One line grew |
| A | up #3 | "Selection mode off", then the caret moves as in #1 | as in #1 | a2d-up1, a2d-up2 | **Fail** |
| B | down | **"Selection mode off"**, " / 	v += 0.01; // line 24"; caret only | " / 	v += 0.01; // line 25"; caret only | b2-down1, b2-down2 | **Fail** |
| B | up | **"Selection mode off"**, "	v += 0.01; // line 23"; caret only | "	v += 0.01; // line 22 / "; caret only | b2-up1, b2-up2 | **Fail** |

When the selection grows (A, down, first swipe), it grows correctly from the caret by a line. The fault is that selection mode switches itself off: at once on the first swipe, or on the second.

## Row 3: caret at the very start (Ctrl+Home), selection mode, down by Lines ×2

| Build | Try | Swipe 1 | Swipe 2 | Picture | Result |
|---|---|---|---|---|---|
| A | #1 | "Selection mode off", "precision mediump float;"; caret to the end of line 1 | " / uniform vec2 resolution;"; caret to the end of line 2 | a3-down1, a3-down2 | Not the whole shader (pass); no selection (mode ended) |
| A | #2 | "precision mediump float;" — **line 1 selected** | " / uniform vec2 resolution;" — **lines 1–2 selected** | a3b-down1, a3b-down2 | **Pass** |
| B | #1 | "Selection mode off", "precision mediump float;"; caret only | " / uniform vec2 resolution;"; caret only | b3-down1, b3-down2 | Not the whole shader (pass); no selection |

## Row 4: Select all from line 23, Cut, Undo

| Build | After Select all | Cut | Undo | Result |
|---|---|---|---|---|
| A | "Selected text: 	v += 0.01; // line 22 / … // line 24", "Selection mode on" | Editor empty; cut speech through "// line 46 end / . Cleared text" (a4-cut) | Ctrl+Z: all 46 lines back, selected (a4-undo) | Pass |
| B | Same announcement | Editor empty; cut speech through "// line 46 end. Cleared text" (b4-cut) | Ctrl+Z: all back (b4-undo) | Pass |

## Oddities
1. In A I once left the Actions dialog open by accident: a swipe went into it, and focus moved inside it. I closed it via its Close button, checked by speech; nothing in it was activated. A later reset used "End selection mode" from the same dialog, also checked by speech.
2. A's file on disk stayed byte-identical to the test file (md5 40fc30f5); the cut and undo were never saved.
3. B asked for notification permission on first start (answered "Don't allow"). The fork app's flags were set back to what I found at the start (`USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED`).

## Cleanup
- **Pixel_API_37:**
  - Fork debug app back to the earlier build (sha1 f706f08a), app data byte-identical (141/141). The notification flags are as found.
  - TalkBack off (`enabled_accessibility_services` null, `accessibility_enabled` 0), Log output level ERROR, TalkBack notification permission revoked with flags `USER_SENSITIVE_WHEN_GRANTED` (as found).
  - Font 1.0, density 420, rotation 1/0, Gboard. Shut down.
- agent-device session list empty. Worktrees `ShaderEditor-sa2` and `ShaderEditor-up4` removed after this push; no Gradle daemon. The APKs and the full verbose logcat are not pushed.
