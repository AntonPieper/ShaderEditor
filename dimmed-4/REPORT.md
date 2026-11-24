# dimmed-4: the grey lines with the smaller evaluator (fork PR #806 at e775f53d1)

Built fork PR #806 (`feat/dimmed-lines`) at **e775f53d10549c7cad11d8f9192cf6a172e95db5**. That was the head after `git fetch` and is the PR's head. The checklist is `origin/foreman/dimmed-rows` at **113882d5b**, which names e775f53d1 and the dropped rows. I built two apps as in step 0: the debug app, and the old-editor variant via `dbscreen.ps1`, which changed both lines; `git checkout` undid them. `gradlew.bat --stop` ran after the builds.

Rows run:
- Step 0 and row 1 on both emulators.
- Rows 2, 3, 4, 5a–d, 6, 7, 8, 9, 10, 11a–b on Pixel_API_37 (Android 17).
- Row 15 a, b, d, e, f on Pixel_API_23 (Android 6).

Methods as in dimmed-2/3:
- **Android 17:** `screenrecord` with each frame's own timestamp. A script counts bright text pixels per code line; a grey line has text but no bright pixels. Frames around each key were checked by eye in strips.
- **Android 6:** screenshots only. Row 15 has no timing.

**Answer to the key question: the smaller evaluator greys exactly the lines the trial greyed, in every row run, and with the same timing.** Every flip lands in the key's frame or the next one (16–33 ms). The only exception is the 2,000-line file, where one frame without grey comes before the flip, as before and as allowed. Nothing differs from dimmed-3, or from dimmed-2 for rows dimmed-3 did not run, beyond a frame either way.

## Pixel_API_37 (Android 17)

| Row | Expected | Seen | Different from dimmed-3 / dimmed-2? | Result |
|---|---|---|---|---|
| 1 Import | Four folders with their files | Es100, Long, Macros, Passes with all files (r1) | No | Pass |
| 2 before | 15, 34, 37, 38, 51 grey | Exactly those, plus the comment lines, which are grey by syntax (r2-before) | No | Pass |
| 2a `-` | 26–32 grey, 34 plain within 3 frames; 15/37/38/51 stay | Key in frame 92, flip in frame 93: **1 frame, 16 ms**. 15, 37, 38, 51 grey in every frame; no frame without grey | dimmed-3: 1 frame/18 ms. Same | Pass |
| 2b delete | Reverse, as fast | Key frame 285, flip 286: **1 frame** (32 ms frame gap) | dimmed-3: 1 frame. Same | Pass |
| 2c delete `2` | 34 plain; 26–32 stay plain | Key frame 461, 34 plain in frame 462: **1 frame**. About 1 s later the build's error row (`#if AA > 1` invalid) appears under line 25 and moves the lines below it down | dimmed-2: same frame, error row noted then too | Pass |
| 2d `1` | 26–32 grey, nothing else | Key frame 650, 26–32 grey in frame 651: **1 frame** (with the error row still shown) | dimmed-2: ≤1 frame. Same | Pass |
| 2e del + `2` | 26–32 plain after the delete; 34 grey after the `2` | Delete: frame 837 → 838, **1 frame**. `2`: frame 1019 → 34 grey in frame 1020, **1 frame**. Then the build removes the error row and nothing else changes | Same | Pass |
| 2f `!` | 37/38 plain; 15, 34, 51 grey throughout | `!` in frame 133, 38 plain in frame 134: **1 frame (33 ms)**. 15 and 34 grey throughout. Line 37 is a comment and looks the same either way. Line 51 was off the measured area in this recording, because the view had scrolled one line | dimmed-3: same frame; dimmed-2: 1 frame. Within one frame | Pass |
| 2g delete | 37/38 grey again | Frame 362 → 363: **1 frame** | dimmed-3: same frame | Pass |
| 3a ` x` on line 40 | No grey changes | Over 814 frames, lines 15, 34, 37, 38 never had a bright pixel. The build then shows the error row for `x` | No | Pass |
| 3b ` // y` on line 34 | 34 grey throughout | 34 never bright except the blinking caret (about 92 px) | No | Pass |
| 3c Enter + `v += 0.0;` | New line 35 grey from its first frame; 15, 34, 35, 38, 39, 52 grey | New line 35 never bright, apart from the caret; afterwards 15, 34, 35, 38, 39, 52 grey (r3c-after) | No | Pass |
| 3d undo | 15, 34, 37, 38, 51 grey | Exactly those (r3d). Saved file byte-identical to the checklist's | No | Pass |
| 4a 2,000 lines | Every `return 0.0;` grey, no `return length` grey; smooth scroll | Top (r4a-top): 15, 23, 31, 39, 47, 55 grey. End (r4a-end): 1943 … 1991 grey; no `return length` grey. Fling frame gaps median 17.0–17.2 ms, the same as at rest (17.0 ms) | dimmed-2: 18–19 ms in both. Same | Pass |
| 4b `-` | All 248 blocks flip; over 50 ms allowed with no grey meanwhile | Key in frame 127 (2646 ms). Frame 128 (2696 ms, **≈50 ms**) has **no grey**. Frame 129 (2713 ms, **67 ms**) has the full flip. Top and end checked after (r4b-top, r4b-end) | dimmed-2: one frame (≈48 ms) without grey, flip at 114 ms. **Same pattern, flip a little sooner** | Pass (allowed case) |
| 4c delete | Flip back | Key frame 360, flip in frame 362 (**65 ms**); frame 361 still shows the old grey; no frame without grey | dimmed-2: 65 ms, no blank frame. Same | Pass |
| 4d typing on line 1997 | No lag, no grey change | `abc`, then three Backspaces: frame gaps median 17.4 ms; the grey of lines 1946–1994 unchanged in every frame | No | Pass |
| 5a manual, Run code | 15, 34, 37, 38, 51 grey | As expected (r5a) | No | Pass |
| 5b `-`, no run | 26–32 grey, 34 plain at once; picture unchanged | As expected (r5b) | No | Pass |
| 5c Run code | Grey unchanged | Unchanged (r5c) | No | Pass |
| 5d delete | Flip back without a run | As expected (r5d) | No | Pass |
| 6a space on `#pragma` line | All grey goes at once; back after the build | Line 18 plain **in the key's frame** (frame 117); grey again in frame 150, **1.05 s** later | dimmed-2: back 1.03 s later. Same | Pass |
| 6b delete | Same | Plain in the key's frame (331), grey again in frame 366, **1.05 s** later | Same | Pass |
| 7a–e | As in dimmed-3 | 7a 18 grey; 7b Pass.frag ("▶ Passes/Main.frag") 18 grey at once; 7c delete → 16 and 18 plain, `0` → 16 grey; 7d delete → plain, `1` → 18 grey within 3 s; 7e Main.frag 18 grey | **Identical to dimmed-3, step for step** | Pass |
| 7f manual | As in dimmed-3 | Before: 18 grey, picture tinted (median 45/27/14). Delete `1`: both plain, still tinted. `0`: 16 grey, 18 plain, still tinted. Pass.frag: tint gone (46/46/46), 18 grey. Main.frag: 16 grey, 18 plain. TINT 1 + Run code: tinted again, 18 grey | **Identical to dimmed-3** | Pass |
| 8a Template | Only 18 grey; 2 and 4 not | Only 18 grey; line 10 is a comment; 2 and 4 plain (r8a) | No | Pass |
| 8b/c switching ×3 | No grey where the shown file has none | r8, every frame checked: Template frames never grey on 15 or 34; Switches frames never grey on 18 except during the drawer's closing scrim, where the whole screen is dimmed (checked by eye: 179, 516, 708, 897). Switches showed its grey as soon as it was drawn | dimmed-2: "only short no-grey moments after a switch". This time no no-grey moment was seen | Pass |
| 9a landscape | 15, 34, 37, 38, 51 grey | 15 grey (r9a); 34, 37, 38 grey after scrolling (r9a2); nothing else | No | Pass |
| 9b 2a/2b in landscape | As row 2 | `-` frame 114 → flip frame 115; delete frame 332 → flip 333: **1 frame each** | No | Pass |
| 9c `-` + portrait | 15, 26–32, 37, 38, 51 grey | Exactly those (r9c); `-` deleted afterwards | No | Pass |
| 10 Bright | Readable, comment grey, unlike coloured code | r10a: line 34 grey at the disc's lower edge (the whole file fits the screen, so it cannot be scrolled further onto the disc). r10b: lines 26–32 grey over the disc. Contrast of grey on the disc **3.0:1**, coloured code 10.2:1 | dimmed-2: 3.0:1 and 10.2:1. Same; readable but faint, as before | Pass (borderline, as before) |
| 11a old editor | No grey; `-` changes no colours | dbscreen variant, share of Switches.frag: all lines in syntax colours (r11a); after `-`: still none (r11a-minus) | No | Pass |
| 11b New editor on | Still no grey | New editor on, same shader: no grey (r11b) | No | Pass |

## Pixel_API_23 (Android 6)

| Row | Expected | Seen | Different from dimmed-3? | Result |
|---|---|---|---|---|
| 15a Switches | GLES3 message, no grey | "#version 300 es needs OpenGL ES 3.0, but this device only has OpenGL ES 2.0" under line 1; no grey; 15, 34, 37, 38, 51 in syntax colours (a15a) | No | Pass |
| 15b `-` on line 9 | Still no grey | No grey (a15b); `-` deleted, file byte-identical | No | Pass |
| 15d Main, Pass | No grey | Both show the GLES3 message, no grey (a15d-main, a15d-pass) | No | Pass |
| 15e Rings.frag | Runs; line 14 grey | Runs at 57 fps (two rings); line 14 grey, nothing else (a15e). Look up part skipped, as the checklist says | No | Pass |
| 15f Template | Runs; 18 grey; 2 and 4 not | Runs at 57 fps (stripes); 18 grey; 2 and 4 plain (a15f) | No | Pass |

## Oddities
1. Row 2c/2e: with `#define AA ` empty, the build adds an error row under line 25 about 1 s after the key, which moves the lines below it down until a build without the error. This is the same as dimmed-2 ("error row shifts lines in auto run"). It is not a grey change.
2. Row 10: line 34 cannot be scrolled over the middle of the disc because the whole file fits on the screen. It sits at the disc's edge.
3. Row 11: the dbscreen variant asked for notification permission (as in dimmed-2). The dialog went away when the variant was uninstalled; I did not answer it.
4. The data I restore has "New editor" off, so I turned it on in Settings before row 1. The checklist expects it on; it went back with the data restore.
5. My slip in row 4d: the first recording typed on line 12 instead of 1997 because my caret tap missed. The letters were deleted, the file is byte-identical, and the recording was redone on line 1997. The first one is not used.
6. Several recordings had the code scrolled by one line. Line positions were measured per recording, so this changes no result.

## Could not do / skipped
- Skipped as the checklist says: rows 12, 13, 14, step 5e, step 11c, and the Look up parts of 15c and 15e. 15c is not in the requested list.
- Row 15 has no timing on Android 6 (screenshots only).

## Cleanup
- **Pixel_API_37:** earlier fork build back (sha1 f706f08a), app data byte-identical to the backup (141/141). The dbscreen variant is uninstalled. Run mode Auto, Picture behind code "Balanced" (both from the restored prefs, and set back in the app before that too). Font 1.0, density 420, rotation 1/0, Gboard, no accessibility service. `/data/local/tmp/se-rows` removed. Shut down.
- **Pixel_API_23:** earlier fork build back (md5 f0655b81), app data byte-identical (28/28). Font 1.0, density 440, rotation 0/0, LatinIME. `se-rows` removed. Shut down.
- agent-device session list empty. Worktrees `ShaderEditor-rows4` and `ShaderEditor-trial4` removed after this push. No Gradle daemon, emulator or adb left running.
- Not pushed: the APKs and the `.mp4` recordings (kept locally). Strips and per-frame line counts (`*-lines.txt`) are pushed.
