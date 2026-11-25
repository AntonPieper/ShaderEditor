# tuning-6: device delta on fork PR #824 (`tuning-several/6-code` at f2bf9c3d2)

Built the debug app at **f2bf9c3d24662e265cf51f697e91f1f52c75073a**, which was exactly the head of `origin/tuning-several/6-code` after `git fetch`. The setup is as in tuning-5: `Tune.frag`, New editor on, a session on 0.75 → 0.76, and the code shown with the eye. Pixel_API_37 (Android 17, Gboard, hardware keyboard attached, TalkBack 17) first, then Pixel_API_23 (Android 6). The fork app's data was backed up before installing and restored after.

**Summary:**
- Rows 1, 2 and 4 pass on both emulators. Row 3's hints, the "Tuning" double-tap, the action list and the after-Done hint are as expected.
- **One finding (row 3):** TalkBack's **double-tap and hold** (its long press) on the code **ends tuning silently**. It says nothing, keeps the values, opens no keyboard, and the bar closes. EDITOR.md says a screen reader's long press on the code is heard at the caret, which is on the tuned number, so the bar should have stayed. It happened twice.

## Pixel_API_37 (Android 17)

| Row | Expected | Seen | Result |
|---|---|---|---|
| 1 Long press on `vec3` (line 9) | Tuning ends, values kept, nothing selected, no toolbar, no keyboard | Bar closed; line 13 keeps 0.76; **nothing selected, no selection toolbar, keyboard hidden** (r1-lp) | Pass |
| 2 Double tap on `for` (line 14) | Tuning ends, values kept; keyboard may open from the first tap; nothing selected, no toolbar | Bar closed; 0.77 kept; Gboard open with completions for the caret word (`faceforward(` `false` `flat` `float`); **nothing selected, no toolbar** (r2-dt) | Pass |
| 3 TalkBack hint while tuning | Something like "Double-tap to say the number being tuned"; no "edit text", no long-press action | Hint: **"Double-tap to say the number being tuned. Actions available, use Tap with 3 fingers to view"**. The line is read without ". Edit box". Actions (3-finger tap → Actions): **"Select all", "Start or end selection mode"** only, no long press | Pass |
| 3 Double-tap on the code | "Tuning" + number, bar stays | **"Tuning 0.77"**, no keyboard, bar stays (t3-dt) | Pass |
| 3 Double-tap and hold on the code | (Expected by EDITOR.md: heard at the caret, bar stays) | **Nothing spoken; tuning ends** (bar gone, 0.77 kept, no keyboard). Touching the code afterwards gives the edit-box hint. Same on a second try (t3-dth, t3-dth2) | **Finding** |
| 3 After Done | Code an edit box again | Touch Done: "Done. Button" → double-tap → "Shaders" (focus on the toolbar). Touch code: hint **"Double-tap to edit text. Double-tap and hold to long press. Proofreading is available. 1 misspelled words. Swipe down with 3 fingers and use "Proofread" to view. Actions available, use Tap with 3 fingers to view"**, line read with **". Edit box"** | Pass |
| 4 Sweep | Tap and long press on a number move the bar; a letter does nothing | Tap `0.25` → bar on 0.25; long press on `0.010` → bar on 0.010; no keyboard either time; `q` → nothing typed, value 0.010, code still shown | Pass |

## Pixel_API_23 (Android 6)

| Row | Seen | Result |
|---|---|---|
| 1 Long press on `vec3` | Bar closed, 0.76 kept, nothing selected, no CUT/COPY bar, no keyboard (a1-lp) | Pass |
| 2 Double tap on `for` | Bar closed, 0.77 kept, LatinIME open with completions, nothing selected, no bar (a2-dt) | Pass |

**Gestures on Android 6 were sent as real touches through `adb input` (`swipe x y x y 900` for the long press, two `tap`s 100 ms apart for the double tap), not through agent-device.** After the cold boot, agent-device's `longpress` failed every time with "Android automation helper is still holding device automation ownership" (also after `close` and a new `open`). Its `open` also switched the keyboard to agent-device's test IME, which I set back to LatinIME at once.

## TalkBack speech, verbatim (Android 17)
- On (session open, code shown): "TalkBack on", "Allow Android Accessibility Suite to send you notifications?" → "Don't allow. Button" → "Shader Editor", "Shaders"
- Touch line 13 while tuning: "Line 13 of 17. 		1.00); / 	vec3 col = tint * GLOW(d + 0.010) * (0.77 + 0.25 * sin(time)); / 	for (int i = 0; i < 3; i++) { col *= 1.05; }", hint **"Double-tap to say the number being tuned. Actions available, use Tap with 3 fingers to view"**
- Double-tap: **"Tuning 0.77"**
- Double-tap and hold: (nothing), tuning ended
- 3-finger tap → "TalkBack menu", "Actions. 1 of 11" → "Select all. 1 of 2. In list. 2 items", "Start or end selection mode. 2 of 2", "Close. Button. Out of list"
- Done: "Done. Button" → "Shaders"
- Touch line 13 after Done: "Line 13 of 17. … (0.78 + 0.25 * sin(time)); … Edit box", hint "Double-tap to edit text. Double-tap and hold to long press. Proofreading is available. 1 misspelled words. Swipe down with 3 fingers and use "Proofread" to view. Actions available, use Tap with 3 fingers to view"

## Oddities
1. TalkBack's notification-permission dialog appeared at each TalkBack start, and the bar stayed open behind it every time (answered "Don't allow").
2. The TalkBack settings coming to the front ends the session, as documented (the screen leaving the front). I started each TalkBack check from a fresh session.
3. **Pixel_API_23 showed an all-white, then all-black display** after the install. I cold-booted it with the agreed command (`emulator.exe -avd Pixel_API_23 -no-snapshot-load -no-audio -no-window -no-metrics -gpu host -no-boot-anim -port 5554`); after that it was fine.

## Cleanup
- **Pixel_API_37:** earlier fork build back (sha1 f706f08a), app data byte-identical (141/141). TalkBack off, Log output level ERROR, notification permission revoked with flags `USER_SENSITIVE_WHEN_GRANTED`. Font 1.0, density 420, rotation 1/0, Gboard. Shut down.
- **Pixel_API_23:** earlier fork build back (md5 f0655b81), app data byte-identical (28/28). Font 1.0, density 440, rotation 0/0, LatinIME. Shut down.
- agent-device session closed, list empty. Worktree `ShaderEditor-tune6` removed after this push; no Gradle daemon. The APK and the full verbose logcat are not pushed (`spoken.txt` and `marks.txt` are).
