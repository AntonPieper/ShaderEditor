# tuning-8: TalkBack's double-tap and hold on the code (fork PR #824 at 4a13f4209)

Built the debug app at **4a13f42090840e4ebf9deb985f48db6fe401693f**, which was exactly the head of `origin/tuning-several/6-code` after `git fetch`. The change since tuning-7 is in `CodeView.heardAsTap`: while a screen reader explores by touch, a gesture heard as a tap now acts at the caret instead of at the touch point. Pixel_API_37 (Android 17, TalkBack 17) only. Same setup as tuning-7: `Tune.frag`, session on line 13 (0.75 → 0.76 with +), code shown with the eye.

**Result: all six TalkBack tries pass, and the regression row passes.**

## 1. TalkBack 17: double-tap and hold on the code while tuning
Each try: touch the code (focus on it), then double-tap and hold, sent as real touches through the emulator console. Speech is from TalkBack's verbose log; the state is checked in a screenshot per try (no UI dumps while TalkBack is on).

| Try | Finger at | Spoken | Bar / value / selection / keyboard | Picture | Result |
|---|---|---|---|---|---|
| N1 | On the tuned number `0.76` (line 13) | **"Tuning 0.76"** | Bar open, 0.76, nothing selected, no toolbar, keyboard hidden | n1.png | Pass |
| N2 | Same | **"Tuning 0.76"** | Same | n2.png | Pass |
| N3 | Same | **"Tuning 0.76"** | Same | n3.png | Pass |
| P1 | Plain code: `vec3` of line 9 | **"Tuning 0.76"** | Same | p1.png | Pass |
| P2 | Empty space below the last line (centre of the code view) | **"Tuning 0.76"** | Same | p2.png | Pass |
| P3 | Plain code: line 14 near `for` | **"Tuning 0.76"** | Same | p3.png | Pass |

In every picture the statement reads `…W(d + 0.010) * (0.76 + 0.25 * sin(t…`, the field shows 0.76, and the code is unchanged. The code is not yet written (line 13 still `0.75`), as by design while tuning.

## 2. Regression without TalkBack
TalkBack off, the same session still open (bar on 0.76, code shown).

| Gesture | Expected | Seen | Picture | Result |
|---|---|---|---|---|
| Long press on the number `0.25` | Bar moves there, as before | Bar on **0.25**; 0.76 written for the number left; no keyboard, no selection | r2-lp-num.png | Pass |
| Long press on plain code (`vec3`, line 9) | Tuning ends as in tuning-7: values kept, nothing selected, no toolbar, no keyboard | Bar closed; line 13 keeps 0.76; nothing selected, no toolbar, keyboard hidden | r2-lp-text.png | Pass |

## Cleanup
- **Pixel_API_37:**
  - Earlier fork build back (sha1 f706f08a), app data byte-identical (141/141).
  - TalkBack off as found (`enabled_accessibility_services` null, `accessibility_enabled` 0), Log output level back to ERROR. Its notification permission was answered "Don't allow"; it is revoked with flags `USER_SENSITIVE_WHEN_GRANTED`, as found.
  - Font 1.0, density 420, rotation 1/0, Gboard. Shut down.
- agent-device session list empty. Worktree `ShaderEditor-tune8` removed after this push; no Gradle daemon. The APK and the full verbose logcat are not pushed (`spoken.txt` and `marks.txt` are).
