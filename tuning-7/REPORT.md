# tuning-7: device delta on fork PR #824 (`tuning-several/6-code` at 7592028d4)

Built the debug app at **7592028d4439a8db638c28d4c975df9437cb3e76**, which was exactly the head of `origin/tuning-several/6-code` after `git fetch`. Same setup as tuning-6: `Tune.frag`, New editor on, a session on line 13, the code shown with the eye. Pixel_API_37 (Android 17, TalkBack 17), then Pixel_API_23 (Android 6). The fork app's data was backed up before installing and restored after.

**Summary:**
- Rows 2, 3 and 4 pass, and the Android 6 rows pass.
- Row 1: the plain double-tap, the hint and the missing Actions menu pass.
- **Finding (row 1):** TalkBack's **double-tap and hold on the code still ends tuning silently**. Nothing is spoken, the bar closes, the value is kept, and no keyboard opens. The new hint even announces "Double-tap and hold to say the number being tuned". This was tried three times, in three fresh sessions, with the finger at the code's centre and on the tuned number. TalkBack's log shows "Double Tap and Hold: … STATE_GESTURE_COMPLETED" and then **no accessibility action at all** (no LONG_CLICK): TalkBack 17 hands the long press to the view as a touch. The app's long-click action is never called, and the touch long press lands on plain code, which ends tuning.

## Pixel_API_37 (Android 17)

| Row | Expected | Seen | Result |
|---|---|---|---|
| 1 Hint on the code while tuning | — | **"Double-tap to say the number being tuned. Double-tap and hold to say the number being tuned"**; the line is read without ". Edit box" | Pass |
| 1 Plain double-tap | "Tuning" + number, bar stays | **"Tuning 0.66"** (twice), keyboard not shown, bar stays | Pass |
| 1 Actions menu (3-finger tap) while tuning | No "Select all" / "Start or end selection mode" | The TalkBack menu has **no "Actions" entry at all** (10 items, starting with "Describe screen") | Pass |
| 1 Double-tap and hold | "Tuning" + number, bar stays | **Nothing spoken; tuning ends** (bar closed, 0.66 kept, keyboard hidden) — 3 of 3 tries (t1-dth, t1-dth-num, t1-dth3) | **Fail** |
| 2 Type 0.65 (no Enter), tap the eye | Value 0.65 (picture and text), code hides | Statement `(0.65 + …`, field 0.65, picture redrawn, eye back to "Show code", keyboard hidden (r2-eye) | Pass |
| 3 Type 0.65 (no Enter), press + | 0.66 | From 0.70: typed 0.65, + → **0.66** (not 0.71) | Pass |
| 4 Sweep: tuning-6 rows 1, 2, 4 | | Long press on `vec3`: tuning ends, value kept, nothing selected, no toolbar, no keyboard. Double tap on `for`: tuning ends, keyboard open (first tap), nothing selected, no toolbar. Tap on 0.25 and long press on 0.010 move the bar; `q` afterwards does nothing | Pass |

(For comparison, an earlier test made by mistake after tuning had ended: the code's Actions there are "Move cursor to beginning / end", "Select all", "Start or end selection mode", as for any edit box.)

## Pixel_API_23 (Android 6)

| Row | Seen | Result |
|---|---|---|
| 2 | With the code shown, typed 0.65 (adb keys), eye: statement 0.65, picture redrawn, code hidden, keyboard hidden (a2-eye) | Pass |
| 3 | From 0.70: typed 0.65, + → 0.66 | Pass |

## TalkBack speech, verbatim (Android 17)
- On: "TalkBack on", "Allow Android Accessibility Suite to send you notifications?" → "Don't allow. Button" → "Shader Editor", "Shaders"
- Touch line 13 (tuning): "Line 13 of 17. 		1.00); / 	vec3 col = tint * GLOW(d + 0.010) * (0.66 + 0.25 * sin(time)); / 	for (int i = 0; i < 3; i++) { col *= 1.05; }", hint **"Double-tap to say the number being tuned. Double-tap and hold to say the number being tuned"**
- Double-tap: **"Tuning 0.66"**; again **"Tuning 0.66"**
- Double-tap and hold: *(nothing)*. Then a double-tap: "…(line text)…", "Showing Password keyboard" (tuning had ended)
- 3-finger tap while tuning: "TalkBack menu", "Describe screen. 1 of 10. In list. 10 items"

## Oddities
1. My 3-finger-tap check opened TalkBack's own menu. My next double-tap, meant for "Actions", landed on "Describe screen" (there is no Actions entry while tuning), which opened TalkBack's "Detailed image and screen descriptions" consent dialog (Cancel / Turn on). I did **not** activate anything in it: the guards blocked two inputs, and my check before the third double-tap refused because focus was not on Cancel. TalkBack's log shows no click while it was open. The dialog closed by itself.
2. TalkBack's notification dialog came up at each TalkBack start; the bar stayed open behind it.

## Cleanup
- **Pixel_API_37:** earlier fork build back (sha1 f706f08a), app data byte-identical (141/141). TalkBack off, Log output level ERROR, notification permission revoked with flags back to `USER_SENSITIVE_WHEN_GRANTED`. Font 1.0, density 420, rotation 1/0, Gboard. Shut down.
- **Pixel_API_23:** earlier fork build back (md5 f0655b81), app data byte-identical (28/28). LatinIME, font 1.0, density 440, rotation 0/0. Shut down.
- agent-device session list empty. Worktree `ShaderEditor-tune7` removed after this push; no Gradle daemon. The APK and the full verbose logcat are not pushed (`spoken.txt` and `marks.txt` are).
