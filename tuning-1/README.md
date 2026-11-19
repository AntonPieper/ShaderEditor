# tuning-1: device pass of "tuning several numbers in one session"

Build: fork PR #816 head b49a390646e39b8452436f4b2a708cccbb93fa5f (debug), new editor on, project mode on.
Test file: `Tune.frag` (sha1 4cee305aba15069f4515e0d31ae51cc2002a3baa as pushed).

File names: `A<row>-…` = Pixel_API_23 (Android 6, 1080x2340), `B<row>-…` = Pixel_API_37 (Android 17, 1080x2400, hardware keyboard always attached).
How the session was started: A = caret on 0.75, then the row's "⇆ 0.75" item; B = the same (the row's Tune item).

| Row | Android 6 | Android 17 |
| --- | --- | --- |
| 1 start on 0.75, drag ruler | A1-a … A1-d | B1-caret, B1-bar, B1-drag-mid, B1-drag-end |
| 2 tap 0.25, + twice, 0.75 kept solid | A2* | B2-plus2 |
| 3 › / ‹ walk, vec3 on one line, #define, ends disabled | A3* | B3-end, B3-vec3-z, B3-start |
| 4 RADIUS 0.30→0.35, GLOW 0.080→0.060 | A4-a … A4-e; A5x-* (value field propagates its value to every visited number, see below) | B4-r030, B4-r035, B4-g080, B4-g060, B4-after-next, B4-picture |
| 5 loop bound 3→5 rebuild | A5-* (b1…b4 burst after +) | B5-before, B5-b1…b6 (burst 0.6–2.8 s after +), B5-after |
| 6 Before hold / tap / + with Before on | A6-* (hold latches on Android 6) | B6-hold (while held), B6-released, B6-tap-on, B6-tap-off, B6-plus |
| 7 Undo/Redo from menu while bar open | A7-state, A7-menu, A7-undo1…4, A7-redo1…4 | B7-menu, B7-undo1…6, B7-redo-end |
| 8 Done, text check, Undo after Done | A8-after-done, A8-undo1…4, A8-eye, A8-bar-reopen | B8-after-done, B8-hidden |
| 9 three changes + Revert, one Undo | A9-three-changes, A9-after-revert, A9-one-undo, A9a-*, A9b-*, A9c-* | B9-three, B9-after-revert, B9-one-undo |
| 10 Back = Done, rotate, landscape | A10-after-back, A10-landscape, A10-land-bar, A10-land-vec3, A10-back-portrait | B10-after-back, B10-landscape, B10-land-bar, B10-land-vec3 |
| 11 eye / drawer / other file | A11-return, A11a-eye*, A11b-*, A11c-* | B11a-eye, B11a-eye2, B11b-drawer, B11c-other, B11c-back |
| 12 type 0.5 / abc / 1e99 / empty | A12-field-kb, A12-typed-05, A12-abc-*, A12-1e99-*, A12-empty* (A12-1e99-clean, A12-pos: keys that went into the code, see below) | B12-half-*, B12-abc-*, B12-e99-*, B12-empty-* |
| 13 font 1.3 + small display size | A13-start, A13-run, A13-bar-float/int/vec3 (font 1.3, density 374); A13-f13-d440-* (font 1.3, default density) | B13-code, B13-float, B13-int, B13-vec3 (font 1.3, density 357); B13-d420-* (font 1.3, default density) |
| 14 TalkBack (Android 17 only) | – | B14-perm (TalkBack's notification prompt, answered Don't allow), B14-after-perm, B14-plus, B14-before-on, B14-next, B14-done, B14-after-inc-next, B14-tbprefs-restored; speech: `spoken.txt`, `logcat-talkback-spoken.txt`; touch marks: `marks.txt` |
| 15 hardware keys (Android 17 only) | – | B15-tab, B15-backslash, B15-ctrlz, B15-escape, B15-enter, B15-x-after-field |
| 16 oddities, fps | A16-start | B16-open, B16-state |

Speech log: `spoken.txt` lists, per touch in `marks.txt` order, the hint and the spoken text exactly as TalkBack logged them.
