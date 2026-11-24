# tuning-5: device delta on fork PR #824 (`tuning-several/6-code` at 1987ea0b8)

Built the debug app at **1987ea0b8e32efac6c9bd2ecbef3ecae86b89267**, which was exactly the head of `origin/tuning-several/6-code` and PR #824. Same setup as tuning-4: `Tune.frag`, New editor on. For L3 I added a file `L3.frag` (line 6: `float w = 0.3 * sin(time) - 2.0;`). Pixel_API_37 (Android 17, Gboard, hardware keyboard attached, TalkBack 17) first, then Pixel_API_23 (Android 6). The fork app's data was backed up before installing and restored after.

**Summary:**
- Rows 1, 4, L3, 6 and the Android 6 rows pass.
- In M1, the gestures on a number pass, and keys and focus stay with the bar.
- **One finding (M1):** a long press or a double tap on plain text ends tuning and keeps the values, as expected, but then goes on as a normal long press or double tap. The word is **selected**, the selection toolbar (Cut · Copy · Look up · Share · Select all) appears, and **Gboard opens**. The row expected no keyboard. The PR body's row 12 says "do nothing more". (A plain tap on text opening the keyboard is documented; this is about the long press and the double tap.)
- Right click: neither agent-device nor `adb input` can send a secondary button, so it was not tested.

## Pixel_API_37 (Android 17)

| Row | Expected | Seen | Result |
|---|---|---|---|
| 1 Home | Value on disk | Session 0.75 → 0.76, Home: line 13 on disk `(0.76 + …)` | Pass |
| 1 Rotation | Tuning ends, value kept | Back via launcher, 0.76 → 0.77, rotate: bar closed, disk `0.77` (r1-rot) | Pass |
| (1 dialog, seen in row 4) | Bar stays | TalkBack's notification dialog appeared twice over an open session with the code showing; bar stayed with its value both times, also after "Don't allow" (t4-start, t4-back) | Pass |
| 4 TalkBack 17 | Double-tap on the code says "Tuning" + number, no keyboard, bar stays; switching via the statement and ‹ › | Double-tap on the code: **"Tuning 0.79"** (twice), keyboard not shown, bar stays (t4-dt1, t4-dt2). Touch `0.26` in the statement: "0.26, can be tuned", double-tap: **"Tuning 0.26"**, bar moves. Touch ›: "Next number 0. Button", double-tap: "Tuning 0". Then touch code + double-tap: **"Tuning 0"** (follows the bar) | Pass |
| M1 long press on a number | Bar moves there | Long press on `0.25` with the code showing: bar on 0.25, 0.78 written for the number left; keyboard not shown; focus on the ruler (m1-lp) | Pass |
| M1 double tap on a number | Bar moves there | Double tap on `0.010`: bar on 0.010, 0.79 written; keyboard not shown; focus on the ruler (m1-dt) | Pass |
| M1 after them: letter | Nothing typed, tuning goes on | `q` (and `d`): nothing typed, value unchanged, bar stays | Pass |
| M1 after them: Ctrl+Z | Goes to the bar | After → (0.25 → 0.26), Ctrl+Z: back to 0.25 (the try undone in the bar); with nothing tried, Ctrl+Z changes nothing; the code is never edited, the menu's Undo is disabled | Pass |
| M1 after them: Enter | Goes to the bar | Enter = **Done**: bar closes, 0.26 kept, keyboard opens (m1-enter). Same as tuning-3 | Pass (as before) |
| M1 D-pad / Tab focus | Never enters the code | `code_view` is `focusable=false` the whole time. ↓ from the ruler: Step, an unlabelled node, `tuning_bar`; ↑ goes to value, statement line, toolbar; Tab from the toolbar: title, quality, eye, menu, `tuning_bar`. Never the code | Pass |
| M1 long press on plain text (`vec3`, line 9) | Tuning ends, values kept, **no keyboard** | Tuning ends, values kept (0.011, 0.79, 0.26 stay in line 13), **but `vec3` is selected, the selection toolbar shows, and Gboard opens** (m1-lp-text) | **Fail** (keyboard and selection) |
| M1 double tap on plain text (`for`, line 14) | Same | Tuning ends, values kept, **`for` selected, selection toolbar, Gboard open** (m1-dt-text) | **Fail** (same) |
| M1 right click | Same rules | Not possible: agent-device and `adb input` have no secondary button | Not done |
| L3 | After `- (-1.5)` and lengthening 0.3, back on (-1.5), TalkBack double-tap still says "Tuning -1.5" | 2.0 → typed −1.5: statement `float w = 0.3 * sin(time) - (-1.5);`. ‹ to 0.3, typed 0.305: `0.305 * sin(time) - (-1.5)`. Back to (-1.5) (› in this line, since it comes after 0.305), code shown, TalkBack: touch line 6 "Line 6 of 9. … float w = 0.305 * sin(time) - (-1.5); …", double-tap: **"Tuning -1.5"**, no keyboard; value field "-1.5. Edit box" (l3-shown, l3-dt) | Pass |
| 6 sweep 1 | Eye shows code, bar stays, nothing written | Code shown, "Hide code", 0.76 in the bar, file still 0.75, keyboard not shown | Pass |
| 6 sweep 2 | Tap on another float moves the bar, writes the number left, no keyboard, focus in the bar | Tap `0.25`: bar on 0.25, 0.76 written, focus on the ruler | Pass |
| 6 sweep 3 | Tap a vec3 component: parts row | Tap `0.60`: x 0.20 · y 0.60 · z 1.00 · Colour | Pass |
| 6 sweep 6 | Back hides the code; next Back is Done | Back: "Show code", bar stays; Back: Done, keyboard opens | Pass |

## Pixel_API_23 (Android 6)

| Row | Expected | Seen | Result |
|---|---|---|---|
| 1 | Home: on disk; rotation ends, keeps | 0.75 → 0.76, Home: disk 0.76; back, 0.76 → 0.77, rotate: bar closed, disk 0.77. No app permission dialog can be shown on Android 6 without changing settings | Pass |
| 3 | Before shows "0.010"; taps turn it off | 0.010 → 0.0105 (adb keys), › and ‹, Before on: **"0.010"**. Tap the tuned number: Before off; Before on, tap 0.77: bar moves, Before off. With the code shown: agent-device long press on `0.25` moved the bar there; agent-device double tap on `0.0105` moved it back; no keyboard; `q` did nothing (a3-dt) | Pass |
| 5 | Esc hides, 2nd Esc reverts, letters nothing | Code shown, 0.78: `q`, `d` nothing; Esc: code hidden, 0.78 kept; Esc: reverted (line 13 back to 0.77) | Pass |

## Row 4 and L3 speech, verbatim (TalkBack 17)
- On: "TalkBack on", "Allow Android Accessibility Suite to send you notifications?", "Allow. Button" → (Don't allow) "Don't allow. Button", "Shader Editor", "Shaders"
- Touch line 13: "Line 13 of 17. 		1.00); / 	vec3 col = tint * GLOW(d + 0.011) * (0.79 + 0.26 * sin(time)); / 	for (int i = 0; i < 3; i++) { col *= 1.05; }. Edit box" (hint "Double-tap to edit text. Double-tap and hold to long press. Actions available, use Tap with 3 fingers to view")
- Double-tap on the code: **"Tuning 0.79"**; again: **"Tuning 0.79"**
- Touch statement number: "0.26, can be tuned" → double-tap: **"Tuning 0.26"**
- Touch ›: "Next number 0. Button" → double-tap: **"Tuning 0"**
- Touch the code (now line 14): "Line 14 of 17. 	vec3 col = … / 	for (int i = 0; i < 3; i++) { col *= 1.05; } / 	gl_FragColor = vec4(col, 1.0);. Edit box" → double-tap: **"Tuning 0"**
- L3: touch line 6: "Line 6 of 9. 	vec2 uv = gl_FragCoord.xy / resolution.xy; / 	float w = 0.305 * sin(time) - (-1.5); / 	gl_FragColor = vec4(vec3(uv.x + w * 0.1 + 0.4), 1.0);. Edit box" → double-tap: **"Tuning -1.5"** → value field: "-1.5. Edit box"

## Oddities
1. **Tab while the ruler has focus** steps the bar through the file's numbers: 0.25 → 0 → 3 → 1.05 → 1.0, and Shift+Tab walks back. That is how my session ended up on line 15's `1.0` before I understood it. With focus on Step or the toolbar, Tab moves focus as usual.
2. While the ruler or −/+ change the value, the code hides (by design: "a change of the value from the bar shows the whole picture again"). My first long press on text therefore landed on the picture and did nothing.
3. The hint TalkBack reads on the code during tuning is still "Double-tap to edit text", although a double-tap now tunes.
4. During the sweep, the Tune item said **"This number cannot be tuned: not part of what runs"**. L3.frag was the running file (subtitle "▶ L3.frag") because I had opened it last. This is correct behaviour; opening Tune.frag from the drawer made it run again. Two tries of my sweep script failed on this before I saw the message. They left the app the way the guards allow (Backs to the launcher), and Tune.frag stayed unchanged (checked with diff).
5. On Android 6, agent-device's `open` switched the keyboard to its test IME, so the code strip with Tune did not show. I set `LatinIME` back at once with `ime set`, and it is LatinIME at the end. agent-device's `longpress` reported "helper output could not be parsed" but the press did happen.
6. Brief named "0.31" for the statement number. In my state the second number of line 13 was 0.26 (from M1), so I used it.

## Could not do
- Right click (no secondary button in agent-device or adb input).
- Row 1's dialog part on Android 6 (no app dialog without settings changes); the TalkBack dialog on Android 17 covered it.
- TalkBack 16 (no emulator).

## Cleanup
- **Pixel_API_37:**
  - Earlier fork build back (sha1 f706f08a), app data byte-identical (141/141).
  - TalkBack off (`enabled_accessibility_services` null, `accessibility_enabled` 0), Log output level ERROR, notification permission revoked, flags `USER_SENSITIVE_WHEN_GRANTED`.
  - Font 1.0, density 420, rotation 1/0, Gboard. Shut down.
- **Pixel_API_23:** earlier fork build back (md5 f0655b81), app data byte-identical (28/28). Font 1.0, density 440, rotation 0/0, LatinIME. Shut down.
- agent-device session closed, list empty. Worktree `ShaderEditor-tune5` removed after this push; no Gradle daemon.
- Not pushed: the APK and the full verbose logcat. `spoken.txt` and `marks.txt` hold the speech.
