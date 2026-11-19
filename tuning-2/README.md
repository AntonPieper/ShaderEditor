# tuning-2: re-run of the tuning-1 faults on fork PR #816 head c90d141d

Build: c90d141d08dabe21ddc46268af4aa8bf4809ce54 (debug). `b*` = Pixel_API_23 (Android 6), `c*` = Pixel_API_37 (Android 17).
Row 7 was run on Android 6 only (on Android 17 it was skipped on instruction; it moves to `debug/tuning-clock`).

| Row | Android 6 | Android 17 |
| --- | --- | --- |
| 1 typed value / walk | b1-bar, b1-enter, b1-next7, b1-plus, b1-file.txt | (text in report) |
| 2 keys after Enter | b2 | c2 |
| 3 Before hold / \ | b3-held-strip (touch holds), b3k-held-strip (\ held via emulator key events), b3-tap-on, b3-bslash | c3-held-strip (touch + emulator key, which did nothing on A17), c3d-held-strip (\ held with `input keyevent --duration`) |
| 4 value input | b4-1e99-typed, b4-1e99-enter, b4-empty, b4-empty-enter | c4-1e99, c4-empty |
| 5 bottom row | b5-001, b5-0001 | c5-def-001, c5-def-0001, c5-13-001, c5-13-0001 |
| 6 Revert / Undo | b6-three, b6-before.txt (lines in report) | c6-before.txt |
| 7 clock | b7a.log (Ctrl+Z), b7b.log (menu Undo), b7c.log (menu Undo, code hidden), b7d.log (menu Undo, keyboard up); b7a/b/c-logcat.txt; samples "ms brightness KB" | skipped |
| 8 TalkBack | – | c8-pre, c8-tbon (bar closed by TalkBack turning on), c8-bar, c8-moved, c8-damage (my touches typed into Gboard, repaired), spoken.txt, marks.txt, logcat-talkback-spoken.txt |
| 9 vec3 statement | b9-020 | c9-020 |
| 10 drawer / switch | b10-drawer, b10-closed, b10-switched | c10-drawer |
| 11 sweep | b11-2tap, b11-2plus, b11-5, b11-3end | c11-2 |

## Delta on head b3db9ddd2ce6181eb887b8116e3d9f8f88c82099 (rows 13–17, sanity 1/3/6) and row 7 on debug/tuning-clock ae187bda

`d*` = Pixel_API_23, `e*` = Pixel_API_37 on b3db9ddd; row 7 on the debug build: `e7*` = Pixel_API_23, `f7*` = Pixel_API_37.
Pixel_API_23 needed a cold boot mid-run (display went black; every screencap all zeros).

| Row | Android 6 | Android 17 |
| --- | --- | --- |
| sanity 3 | d3-held-strip | e3-held-crop |
| sanity 6 | d6-before.txt | e6-before.txt |
| 13 rotation | d13-logcat.txt, d13-big | e13-logcat.txt, e13b-logcat.txt, e13-big |
| 14 Before + tap | d14-a … d14-d | – (text in report) |
| 15 typed, unconfirmed | d15-* | – (text in report) |
| 16 flash | d16-done.log, d16-revert.log, d16-done-loop.log ("ms brightness") | e16-*.log |
| 17 Enter on a button | d17-before, d17-enter | e17-step |
| 7 debug build | e7a/b/c.log (samples), e7a/b/c-tuningclock.txt | f7a/b.log, f7a/b-tuningclock.txt |
