# dimmed-2: device pass of the grey lines and the Here row

Build: fork PR #807 head 8944a402844946b5411245cf07b6b92a84d55c11, built on Windows exactly as DEVICE-ROWS.md step 0 says.
`dbscreen.ps1` ran on Windows without error (it changed the two lines, the old-editor variant built, `git checkout` undid them).
Checklist: `origin/foreman/dimmed-rows` a98e6f161.

Names without a prefix (`r…`, `B14…`, `A-…`, `C-…`, `rB-…`) are Pixel_API_37 (Android 17, 1080x2400, Gboard).
Names starting with `a` (lower case) are Pixel_API_23 (Android 6, 1080x2340, GLES 2.0 only).

## How the timing was measured

Android 17: `adb shell screenrecord` (it records only when the screen changes; here 45 to 60 frames a second because the picture animates).
Each frame's own timestamp was used. A script measured per code line how many bright text pixels it has (a grey line has none),
and the frames where line 9 (the edited line) changes. `*-lines.txt` hold those numbers per frame; `*-strip*.png` show the frames
around each key with frame number and time.

Android 6: screen recording does not work on this emulator, so a burst of raw `screencap`s, one about every 200 ms,
with the same per-line test (`a*-burst.png` = every shot of a burst, labelled with ms since start).
That can show whether the typed character and the grey change arrive in the same shot, and whether a shot shows a wrong state,
but not anything shorter than ~200 ms: a 1-frame flash between two shots is invisible to it.

## Rows (Android 17)

| Row | Files |
| --- | --- |
| 1 import | r1.png (= r1-drawer), r1-macros, r1-long |
| 2 a–b | r2-before.png, r2.mp4 → r2-lines.txt, r2-strip-a/b/c.png |
| 2 c–e | r2c.mp4 → r2c-lines.txt, r2c-strip-c/d/e2.png, r2c-chk.png; second take r2e.mp4 → r2e-lines.txt, r2e-strip.png |
| 2 f–g | r2g.mp4 → r2g-lines.txt, r2g-strip.png (r2f.mp4 / r2f-strip-start: first take, caret handle over line 38) |
| 3 | r3.mp4 (landscape) → r3-lines.txt, r3-left.txt, r3-strip.png, r3-errors.png, r3d.png |
| 4 | r4a-top.png, r4a-end.png, r4scroll-pts.txt (scroll smoothness), r4b-top/end.png, r4b-lines.txt, r4b-strip.png, r4b-strip9.png, r4c-lines.txt, r4c-strip.png, r4d-strip.png |
| 5 | r5a.png, r5b.png, r5c.png, r5d.png, r5e-1.png (Here), r5e-2.png (no Here after edit), r5e-3 = Here back (text in report) |
| 6 | r6-start.png, r6.mp4 → r6-lines.txt, r6-strip.png |
| 7 | r7b-0.3s.png, r7-strip.png (c/d), r7c-*.png, r7d-*.png, r7e-0.3s.png, r7f2-main-edited.png, r7f2-pass-0.3s.png, r7f2-pass-3s.png, r7f2-pass-run.png |
| 8 | r8a.png, r8b.png, r8.mp4 analysed by tools (r8-strip-a/b.png, r8-f480.png, r8-f560.png) |
| 9 | r3-land*.png (a), r9b.mp4 → r9b-lines.txt, r9b-strip.png, r9c-a.png (c) |
| 10 | r10-bright-top.png, r10a.png, r10a-zoom.png, r10b.png, r10b-zoom.png |
| 11 | r11-start.png, r11a.png, r11a-minus.png, r11b-back.png, r11b.png, r11c-file.png, r11c-bar.png, r11c-overflow.png (no Look up) |
| 12 | r12-bar-kb.png, r12-bar-nokb.png, r12-sheet.png (= r5e-1) |
| 13 | r13-MIX3-port*.png, r13-SPIN-port*.png (font 2.0, portrait), r13-land-MIX3*.png, r13-land-SPIN.png (font 2.0, landscape) |
| 14 | B14-start/hold/hold2/sheet/mix3-bar/mix3-sheet.png, spoken.txt, marks.txt, logcat-talkback-spoken.txt |
| A Here layout | A-def-MIX3.png, A-def-SPIN.png (default font), A-big-SPIN.png (app text size Big), A-land-MIX3*.png, A-land-RING.png, A-land-SPIN.png; RING at default: r5e-1.png |
| B grey over picture | r10a(-zoom), r10b(-zoom) (Bright), rB-balanced-26-32.png, rB-balanced-zoom.png (Balanced) |
| C first impression | C-pictures.png, C-pictures2.png, C-code1.png, C-code2.png, C-*-picture.png, C-*-code.png |

## Rows (Android 6)

| Row | Files |
| --- | --- |
| start | a-start.png |
| 2 a | a2-before.png, a2a-burst.png |
| 2 b–e | a2b-burst.png |
| 2 f–g | a2f-pre.png, a2f-burst.png, a2f1/2/3-burst.png |
| 4 | a4-top.png, a4b-burst.png, a4b2-burst.png, a4b3-burst.png, a4b-end.png |
| 8 (Template, runs on GLES2) | a8-template.png |
| 12 / 13 Here | a12-bar.png, a12-overflow.png, a13-RING.png, a13-MIX3.png, a13-MIX3-2.png |
| C | a-c-last.png |
