# upstream-editor-1: device check of the upstream PR branch (final report as sent)

No blocker on either emulator; none of the rows I could run failed. TalkBack (rows 2, 3) only on Android 17 — Pixel_API_23 has no TalkBack installed.

Built `origin/upstream-new-editor` at **9a45b95b91e616ee57c971ea1d791a1634276825** (f1caaa93 was not on the remote; 9a45b95b was still newest at the end) and `upstream/master` at **ef7615d976f3ddfd3337c2df6bec90d988496d82** as BEFORE. Both use the fork's debug package id (`de.markusfisch.android.shadereditor.debug`, versionCode 94), so they replaced the fork's debug app; I copied its data first and restored data and earlier build at the end. Uninstalled nothing.

Pushed: commit 9e01ac79 under `upstream-editor-1/` (128 files).

## Pixel_API_37
| Row | Expected | Seen | Result |
|---|---|---|---|
| 1 long-press | Handles + Cut, Copy, Paste, Select all | Cut, Copy, Select all; Paste once clipboard has text | Pass |
| 1 end handle to edge, hold | Auto-scroll, grows | Yes | Pass |
| 1 double-tap + drag | Same | Yes | Pass |
| 1 swap handles | Swap | First try stopped short; further drag swapped | Pass, see oddity |
| 1 magnifier | Shows | Shows | Pass |
| 2 TalkBack | Reached by swipe/touch; lines around caret; typing echoed | Quoted below | Pass |
| 3 words across line break | Breaks, words, Semicolon…; back same; never "Selection cleared" | Forward exact; back same words but no line breaks spoken; never "Selection cleared" | Pass, oddity |
| 5 setting OFF vs master | Pixel-identical | Only fps digits differ | Pass |
| 6 paste ×5 | No freeze, right count, one Undo each | 183 lines → 1093 (183 + 5×182), 18732 chars; Undo 1093→911→729→547→365→183 | Pass |
| 6 paste+type | Only if head has Paste change | Not in 9a45b95b; skipped | Not run |
| 7 rotation | No crash | No crash; highlight kept; keyboard closes | Pass |

## Pixel_API_23
| Row | Seen | Result |
|---|---|---|
| 1 | CUT/COPY/PASTE + ⋮→SELECT ALL; grow, swap, double-tap drag; no magnifier | Pass |
| 2, 3 | No TalkBack | Not possible |
| 4 | No VerifyError/Rejecting/Unable to resolve/Could not find class mentioning de.markusfisch (only LeakCanary's own and AppCompat's setEpicenterBounds) | Pass |
| 5 | Only fps digits differ | Pass |
| 6 | 209 lines ×6 = 1249 lines, 21408 chars; one Undo per paste | Pass |
| 7 | No crash, selection kept | Pass |

## Row 2 speech (Android 17)
"Line 30 of 33. Editing.  / precision mediump float; / . Edit box" (by swipe and by touch, both times the lines around the caret). Typing: "x", "30: 'x' : syntax error. Details. Button"; "y", "30: 'xy' : syntax error. Details. Button"; "y deleted", "30: 'x' : syntax error. Details. Button"; "x deleted", "32: 'resolution' : redefinition. Details. Button".

## Row 3 speech
Forward: " /  / uniform" → " vec2" → " resolution" → "Semicolon" → " / uniform" → " float" → " time". Back: "time" → "float " → "uniform " → "Semicolon" → "resolution" → "vec2 " → "uniform " → "Semicolon" → "float". "Selection cleared": 0.

## Row 5 numbers
Android 17: closed 0 px, open 0 px, typed 20 px (fps text), selected 0 px. Android 6: closed 279 px (fps digits), others 0 px.

## Oddities
Same package id as fork's debug; handle swap needs a long drag; backwards word reading skips line breaks; each key reads the error banner; touch reads caret lines not touched line; Android 6 ignores keyboard PASTE key (279); ~1 s busy after big pastes; crash-guard `#if 0` wrapper and a notification prompt after my bad edit.

## Cleanup
Both emulators: earlier fork builds back (f706f08a / f0655b81), data byte-identical, settings unchanged, shut down; worktrees removed.
