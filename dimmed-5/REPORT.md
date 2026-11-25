# dimmed-5: rows 2 and 7 again on fork PR #806 at b56b61a6c

Built `feat/dimmed-lines` at **b56b61a6ced7167da845817e7c8f55b18a47fbd1**: `origin/feat/dimmed-lines` after `git fetch` was exactly that. `gh` could not reach GitHub at the time, so the PR's head was taken from the branch ref. Relative to e775f53d1, the branch was rebased onto a new base, 39ea1cb5 "run the directives of what the engine compiles", with the grey-lines commit on top. The checklist is unchanged: `origin/foreman/dimmed-rows` at 113882d5b. I ran step 0 (import) and then rows 2 and 7 on Pixel_API_37 (Android 17), with the methods of dimmed-4: `screenrecord`, lit text pixels per code line per frame, and strips checked by eye.

**Result: rows 2 and 7 pass, and nothing differs from dimmed-4 by more than one frame.** In row 2 every flip lands in the key's frame or the next one. In row 7 every step shows the same grey and the same picture as in dimmed-4.

## Row 2 (Switches.frag; recordings r2.mp4, r2f.mp4)

| Step | Expected | Seen (key frame → flip frame) | dimmed-4 | Same? |
|---|---|---|---|---|
| Before | 15, 34, 37, 38, 51 grey | Those (plus the comment lines) (r2-before) | Same | Yes |
| a `-` | 26–32 grey, 34 plain ≤3 frames; 15/37/38/51 stay; no frame without grey | Frame 110 → 111: **1 frame (17 ms)**. 15, 37, 38, 51 grey throughout | 1 frame (16 ms) | Yes |
| b delete | Reverse | Frame 305: **same frame** | 1 frame | Yes (one frame sooner) |
| c delete `2` | 34 plain; 26–32 stay plain | Frame 465 → 466: **1 frame**. About 1 s later the build adds the error row under line 25, as before. Line 34 stays plain (strip r2-strip-c) | 1 frame, same error row | Yes |
| d `1` | 26–32 grey, nothing else | Frame 666: **same frame** (error row still shown) | 1 frame | Yes |
| e delete + `2` | 26–32 plain; then 34 grey | Delete: frame 860 → 861, **1 frame**. `2`: digit and line 34 grey in **the same frame** (1058); about 1 s later the build removes the error row (frame 1109) and nothing else changes (r2-strip-e2, r2-strip-e3) | 1 frame each | Yes |
| f `!` on line 36 | 37/38 plain; 15, 34, 51 grey throughout | Frame 137: line 38 plain **in the key's frame**; 15 and 34 grey throughout (37 is a comment, so its brightness cannot show the change) | 1 frame (33 ms) | Yes (one frame sooner) |
| g delete | 37/38 grey again | Frame 378: **same frame** | 1 frame | Yes |
| After the last build | Nothing changes | No change in the 6 s recorded after | Same | Yes |

## Row 7 (Passes/Main.frag and Pass.frag)

| Step | Seen | dimmed-4 | Same? |
|---|---|---|---|
| a Main.frag | 18 grey (r7a) | Same | Yes |
| b Pass.frag | Subtitle "▶ Passes/Main.frag"; 18 grey at once and after 2.5 s (r7b0, r7b) | Same | Yes |
| c delete `1` of RINGS / type `0` | 16 and 18 plain / 16 grey, 18 plain (r7c1, r7c2) | Same | Yes |
| d delete `0` / type `1` | Both plain right away and 1.5 s later / plain right away, 18 grey within 3 s (r7d0–r7d3) | Same | Yes |
| e Main.frag | 18 grey (r7e) | Same | Yes |
| f manual run | Before: 18 grey, picture tinted (median 45/27/14). Delete `1` of TINT: both plain, tinted. `0`: 16 grey, 18 plain, tinted. Pass.frag: tint gone (46/46/46), 18 grey. Main.frag: 16 grey, 18 plain. TINT 1 + Run code: tinted, 18 grey. Run mode back to Auto (r7f0–r7f5) | Same | Yes |

Main.frag, Pass.frag and Switches.frag are byte-identical to the checklist's files at the end.

## Oddities
None new. The strips show what the per-line counts cannot: the step e `2` flip, while the error row moves the lines.

## Cleanup
Pixel_API_37:
- Earlier fork build back (sha1 f706f08a), app data byte-identical to the backup (141/141).
- Run mode Auto; font 1.0, density 420, rotation 1/0, Gboard, no accessibility service.
- `/data/local/tmp/se-rows` removed. Shut down, and the agent-device session list is empty.

Machine: the worktrees `ShaderEditor-trial5` and `ShaderEditor-rows4` are removed and no Gradle daemon is running. The APK and the `.mp4` recordings are not pushed.
