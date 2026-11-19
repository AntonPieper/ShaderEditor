# tuning-3: device pass of "code on request" (fork PR #824 at dee4e58d)

Built the debug app of PR #824 head **dee4e58dfd2a8e295ac9973228b775913fab87c2** (still the head when I started). Rows from the PR body. Pixel_API_37 (Android 17, Gboard, hardware keyboard attached, TalkBack 17) for rows 1–12; Pixel_API_23 (Android 6) for rows 2, 4, 5, 6. Test file `Tune.frag` as in tuning-1/2. Session started each time from the row's Tune item (caret on 0.75).

Summary: the feature works on both emulators for rows 1–10. **Three findings:** with the code showing, **Esc reverts the whole session** (row 12 expects it to hide the code); **typing a letter does not end tuning** (nothing happens; row 12 expects tuning to end); with TalkBack, **moving by words to another number or a keyword and double-tapping does not move the bar or end tuning** (it says "Tuning 0.52" again). Also an oddity: while the code view is scrolled sideways, the vec3 component lines 10–12 render empty (both emulators).

## Pixel_API_37 (Android 17)
| Row | Expected | Seen | Result |
|---|---|---|---|
| 1 Portrait eye | Code shows; bar stays; tuned line above the bar; nothing written | Code over the picture; bar stays; line 13 above the bar; eye's label "Hide code"; file unchanged; keyboard stays closed | Pass |
| 2 Tap another float | Bar moves, number left is written, no keyboard, focus stays in the bar | Tap on `0.25` in the code: bar on 0.25; 0.76 written in the code; keyboard down; focus `tuning_ruler` | Pass |
| 3 Tap a vec3 component | Parts row; tuned line stays above | Tap on `0.60` (line 11): x/y/z row + Colour; line 11 visible above the bar | Pass |
| 4 Tap a keyword right of a lengthened number | Tuning ends, values kept, caret on the tapped char, keyboard open | 0.25→0.255 typed, code shown, tap on `sin`: bar closed, 0.50 and 0.255 kept, caret between `si` and `n`, Gboard open | Pass |
| 5 Ruler, −, + hide the code | Code hides | + hid the code (also → / ← keys, which step the ruler) | Pass |
| 6 Back | First hides the code, next is Done | Back 1: code hidden, bar stays (0.76); Back 2: Done, keyboard opens | Pass |
| 7 ‹ › Undo Redo with code showing | Code follows the tuned number | Bar walks 0.60 → 1.00 → 0.010; Undo 0.76→0.75 and Redo back; code stays shown throughout. (The caret isn't drawn in screenshots, so "caret on the tuned number" itself not confirmed.) | Pass |
| 8 Typed value taken before the code shows | | Typed 0.50 without Enter, eye: 0.50 in the statement, code shown | Pass |
| 9 Before while code shows; tap on a number turns Before off | | Before on with code shown: start value shown; tap on 0.25 in the code: bar moves, Before off | Pass |
| 10 Landscape rows 1–6 | | 1: code over picture, line 13 above the bar; 2: tap 0.60 moves the bar, no keyboard; 5: + hides; 6: Back hides, Back again = Done. 4 (tap on non-number text) also ended tuning keeping 0.51 (happened by a mis-tap). | Pass |
| 11 TalkBack 17 | Eye's three names; focus on the tuned line; double-tap on the caret's number tunes it, on a keyword ends; word-move then double-tap; focus after Done | See speech below. **Word-move to 0.255 or to `sin`, then double-tap: "Tuning 0.52" — neither moves nor ends.** After Done focus lands on the toolbar ("Shaders"). | **Partial / finding** |
| 12 Hardware keys | Arrows and Enter act on the bar, Esc hides the code, typing ends tuning, mouse click = tap | → / ← step the value (and hide the code). **Esc with the code showing: the session is reverted** (0.52 → 0.51, bar closed). **Enter with the code showing: Done** (bar closed, value kept) — not a press on a bar button. **Typing `q` with the code showing: nothing** (no text, bar stays). Mouse click on a number: bar moves; on other text: tuning ends, keyboard opens. | **Fail** (Esc, typing) |
| 14 Release build | Eye unchanged | Not done: release build needs signing keys this machine doesn't have | Not done |

## Pixel_API_23 (Android 6)
| Row | Seen | Result |
|---|---|---|
| 2 | Code shown; tap `0.60` moves the bar (x/y/z row), no keyboard | Pass |
| 4 | 0.25→0.255, code shown (scrolled right), tap `sin`: tuning ends, 0.255 kept, caret in `sin`, keyboard open | Pass |
| 5 | + hides the code | Pass |
| 6 | Back hides the code; next Back is Done | Pass |

## TalkBack speech verbatim (row 11, Android 17)
- Row's Tune item: "Tune 0.52. Button. Window Shader Editor" → (double-tap) "Shader Editor", "Tuning 0.52", "0.52, started at 0.52. Value. Slider", "keyboard hidden"
- Eye with the bar open (touch): **"Show code. Button"**; double-tap: "Show code", "Line 13 of 17. 		1.00); / 	vec3 col = tint * GLOW(d + 0.010) * (0.52 + 0.255 * sin(time)); / 	for (int i = 0; i < 3; i++) { col *= 1.05; }. Edit box", **"Line 13: 	vec3 col = tint * GLOW(d + 0.010) * (0.52 + 0.255 * sin(time));"** (focus on the tuned line), then "Hide code. Button"
- Eye with the code showing (touch): **"Hide code. Button"**; outside tuning the eye is "Toggle code" (UI label, from the dump)
- Touch the code: "Line 13 of 17. … Edit box"; double-tap (caret on 0.52): **"Tuning 0.52"**
- Reading by Words: "d", "0.010", "0.52", "0.255" → double-tap: **"Tuning 0.52"** (bar did not move to 0.255)
- Words on to "sin" → double-tap: **"Tuning 0.52"** (tuning did not end)
- Done: "Done. Button" → "Done", then focus **"Shaders"** (toolbar title)

## Oddities
1. With the code showing and scrolled sideways, lines 10–12 (`0.20,` `0.61,` `1.00);`, indented) render as empty lines (both emulators; they appear again when scrolled back).
2. While tuning with the code showing, the code shows the last written value (by design): e.g. statement 0.50 vs code 0.75 until the next move.
3. Pixel_API_23 showed an all-white display after install; cold-booted with the agreed emulator.exe command, then fine.
4. TalkBack needed its notification permission granted temporarily to avoid the dialog closing the bar (it was revoked afterwards and flags reset).
5. My own slips: two stray letters (`x`) typed into line 13 when a setup tap missed and no session was open; both removed and the text checked. Once my Backs left the app (guards stopped further input); returned the launcher way.

## Could not do
Row 13's Android 6 subset done (2, 4, 5, 6). Row 11 on TalkBack 16 (no Android 16 emulator here). Row 14 (no release signing).

## Cleanup
Pixel_API_37: earlier fork build back (f706f08a), data byte-identical (141/141), TalkBack off, Display speech output off, log level ERROR, TalkBack notification permission revoked and flags back to USER_SENSITIVE_WHEN_GRANTED; font 1.0, density 420, rotation 1/0, Gboard. Pixel_API_23: earlier build back (f0655b81), data byte-identical (28/28). Both shut down; worktree removed; no Gradle daemon; session list empty.
