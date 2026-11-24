# tuning-4: device delta on fork PR #824 (`tuning-several/6-code` at 5df4c8b9)

Built the debug app from a worktree at **5df4c8b9f73b57d13879e4c6eb47775a80c56e0f**. That was the head of `origin/tuning-several/6-code` after `git fetch` and is PR #824's head. `gradlew.bat --stop` ran after the build. Pixel_API_37 (Android 17, Gboard, hardware keyboard attached, TalkBack 17) first, then Pixel_API_23 (Android 6). One emulator at a time. Test file: `Tune.frag` as in tuning-3. The fork app's data was backed up before installing on each emulator and restored at the end.

**Overall: all rows pass on both emulators.** The three tuning-3 findings are fixed:
- Esc with the code showing now hides it first.
- A letter no longer ends or changes anything.
- With TalkBack, word reading followed by a double-tap now moves the bar or ends tuning.

The system dialog no longer closes the bar. Notes are under Oddities.

## Pixel_API_37 (Android 17)

| Row | Expected | Seen | Result |
|---|---|---|---|
| 1 System dialog | Bar stays open, value kept | Session on 0.75 → + → 0.76. TalkBack turned on; its notification dialog ("Allow Android Accessibility Suite to send you notifications?") came up over the app. **The bar stayed open at 0.76** behind it (r1-tb2). Answered "Don't allow" and the bar was still open at 0.76 (r1-after). TalkBack turned off: still open, 0.76 | Pass |
| 1 Home | Value on disk | Home → `run-as cat` line 13: `(0.76 + 0.25 * sin(time))` | Pass |
| 1 Rotate | Tuning ends, value kept | Back via launcher intent, new session 0.76 → 0.77, rotate to landscape: bar closed, code shows `0.77`, file on disk `0.77` (r1-rot) | Pass |
| 2 Arrow keys | On Before/Step, ← → move focus, value unchanged; on the ruler they step | Ruler: → 0.77→0.78, ← back to 0.77. D-pad ↓ from the ruler: focus Step. → Done, → Next number (‹ › row), ← statement line, ← Previous number, ← stays. From Before: → Step, ← Before, ← Revert. **Value 0.77 throughout.** ↑↑ back to ruler: → 0.78, ← 0.77 | Pass |
| 3 Before spelling | Before shows "0.010" | ‹ to 0.010, typed 0.0105 + Enter (statement `0.0105`); › to 0.77 and ‹ back (0.0105); Before on: field **"0.010"**, statement `0.010` (r3-before) | Pass |
| 3 Tap turns Before off | Tap on the tuned number / another number turns Before off | Tap on `0.010` in the statement: Before off (checked=false), value 0.0105 shown again. Before on again, tap on `0.77`: bar moves to 0.77, Before off | Pass |
| 4 TalkBack 17 | First double-tap on the code opens the keyboard and the bar stays; words to 0.255 + double-tap moves the bar; words to `sin` + double-tap ends tuning | First double-tap on the code: "Showing Password keyboard", keyboard up, **bar stays at 0.79** (t4-dt1). Words to 0.255 (caret `0.|255`), double-tap: **"Tuning 0.255"**, bar on 0.255, keyboard hidden (t4-dt255). Words to `sin` (caret `sin|(`), double-tap: **tuning ends**, keyboard opens, completion strip `sin` `sinh` (t4-dtsin). Speech below | Pass |
| 5 Esc and letters | Esc hides the code (value kept), 2nd Esc reverts, a letter does nothing | Code shown with 0.78: Esc → code hidden ("Show code" again), **0.78 kept**, bar open (r5-esc1). 2nd Esc → bar closed, **line 13 back to 0.77** (r5-esc2). New session 0.77→0.78, code shown: `q` and `d` (key events): **nothing typed, bar stays, value 0.78**, code still shown (r5-q). Same with the code hidden: `q` nothing. Done keeps 0.78 | Pass |
| 6 Sweep (tuning-3 1, 2, 3, 6) | No regressions | 1: eye shows the code, bar stays, line 13 above the bar, eye "Hide code", nothing written, keyboard closed (r6-1-eye). 2: tap on `0.25` in the code → bar on 0.25, 0.79 written, no keyboard, focus on the ruler (r6-2-tap). 3: tap on `0.60` (line 11) → x/y/z row + Colour, line 11 above (r6-3-vec3). 6: Back hides the code; next Back is Done, keyboard opens | Pass |

## Pixel_API_23 (Android 6)

| Row | Expected | Seen | Result |
|---|---|---|---|
| 1 | Dialog if possible, else Home + rotation | **No app permission dialog can be triggered on Android 6** without changing settings: runtime permissions are granted at install for this app, and tuning has no permission flow. Did Home + rotation: 0.75→0.76, Home → disk `0.76`; back, new session 0.76→0.77, rotate → bar closed, code and disk `0.77` (a1-rot) | Pass (dialog part not possible) |
| 2 | Same as A17 | Ruler → 0.78, ← 0.77. ↓ from the ruler: Before. → Step, → Done, ← Step, ← Before, ← Revert, ← (no visible focus). **Value 0.77 throughout** (a2-keys, a2-after) | Pass |
| 3 | Same as A17 | 0.010 → typed 0.0105, › and ‹ back, Before on: **"0.010"** (a3-before). Tap on the tuned number: Before off, value 0.0105. Before on, tap on `0.77`: bar moves, Before off | Pass |
| 5 | Same as A17 (key events, no hardware keyboard) | Code shown with 0.78: Esc → code hidden, 0.78 kept; 2nd Esc → reverted to 0.77. New session 0.78 + code shown: `q`, `d` → nothing typed, bar stays, value 0.78; same with the code hidden. Done keeps 0.78 | Pass |

## Row 4 speech, verbatim (Android 17, TalkBack 17)
- TalkBack on (session open, code shown): "TalkBack on", "Shader Editor", "Shaders" (hint "Double-tap to activate")
- Touch the code (line 13): **"Line 13 of 17. 		1.00); / 	vec3 col = tint * GLOW(d + 0.0105) * (0.79 + 0.255 * sin(time)); / 	for (int i = 0; i < 3; i++) { col *= 1.05; }. Edit box"** (hint "Double-tap to edit text. Double-tap and hold to long press. Actions available, use Tap with 3 fingers to view")
- First double-tap on the code: "		1.00); / 	vec3 col = tint * GLOW(d + 0.0105) * (0.79 + 0.255 * sin(time)); / 	for (int i = 0; i < 3; i++) { col *= 1.05; }", **"Showing Password keyboard"**; the bar stays.
- (My slip: swipes right moved through the bar instead of the code: "Previous number 0.0105. Button", "…(d + 0.0105) * (0.79 + 0.255 * sin(…", "0.0105, can be tuned". Touched the code again: "Line 13 of 17. Editing. … Edit box", hint "…Proofreading is available. 1 misspelled words…")
- Reading control: "Words", "swipe up or swipe down to read by words"
- Swipe down by words: (first a long run from line 2 to `(0`), "Period", "79", "+", "0", "Period". The caret is now in `0.|255`.
- Double-tap: **"Tuning 0.255"**, "0.", "keyboard hidden"
- Words on: "0", "Period", "255", "Asterisk", **" sin"**, "Left paren", "sin: angle"; one back: "Left paren" (caret `sin|(`)
- Double-tap: **"Showing Password keyboard"**. Tuning ended, keyboard open.

## Oddities
1. **TalkBack 17 reads numbers in pieces by words**: `0.255` is "0", "Period", "255". To reach a number by words you land on its parts. The double-tap still found the number, with the caret after "0.".
2. Row 4: the "Tuning 0.255" double-tap also spoke "0." before "keyboard hidden".
3. Row 2: from Done (A17), → wraps to "Next number" in the ‹ › row and ← then walks along the statement row. On Android 6, ← from Revert puts focus on an element with no text. The value never changed in any of this.
4. A17: with the code showing during tuning, the code is scrolled sideways to keep the caret in view, so line starts are cut (`ec3 col`, `cision mediump`). When it scrolls further (caret at `sin`), lines 10–12 (`0.20,` etc.) appear empty because they are shorter than the scroll offset (t4-sin). This is the tuning-3 oddity 1; it is just how sideways scrolling looks, not lost text.
5. On A17 the "new editor" prompt's **Turn on** did not take for me (twice). I turned on Settings → New editor instead; it went back with the data restore.
6. TalkBack's "Log output level" → VERBOSE asks for a confirmation ("Enable verbose logging?") that I missed the first time, so the first TalkBack start logged no speech. Redone after confirming. Back to ERROR at the end.
7. My slips:
   - Android 6: one setup tap missed the Tune item, and two Esc events then went to the launcher. The guards stopped everything after that; I came back with the launcher intent. Line 13 was unchanged.
   - A17: the first word reading went right through the bar; it was redone on the code.

## Could not do
- Row 1 on Android 6: no app permission dialog without changing settings. Home and rotation were done.
- Row 4 on TalkBack 16: there is no Android 16 emulator here.
- Rows 2/5 on Android 6 used adb key events (D-pad, Esc, letters) because there is no hardware keyboard.

## Cleanup
- **Pixel_API_37:** earlier fork build back (sha1 f706f08a), app data byte-identical to the backup (141/141). TalkBack off (`enabled_accessibility_services` null, `accessibility_enabled` 0). Log output level ERROR, Display speech output off. TalkBack's notification permission revoked, flags back to `USER_SENSITIVE_WHEN_GRANTED`. Font 1.0, density 420, rotation 1/0, Gboard. Shut down.
- **Pixel_API_23:** earlier fork build back (md5 f0655b81), app data byte-identical (28/28). Font 1.0, density 440, rotation 0/0, LatinIME. Shut down.
- Temporary files on both emulators removed. agent-device session list empty. No emulator, Gradle daemon or adb left running. Worktree `ShaderEditor-tune4` removed after this push.
- Not pushed: the APK and the full verbose logcat (kept locally). `spoken.txt` and `marks.txt` hold the speech.
