# dimmed-2: device pass of PR #807 at 8944a402 (final report as sent)

Overall: the grey lines and the Here row hold up well on Android 17. Most rows pass and the flips land within 0–2 frames of the key. Three deviations from the checklist's expectations:
- **Row 7f fails:** Pass.frag still shows the last build's grey after an unbuilt edit in Main.frag.
- **Row 13 fails at Android 17's largest font, portrait:** SPIN's Here overflows the sheet and the system wraps lines to the left edge.
- **On Android 6, Here and grey are shown from builds the device refuses.** That is 9 of the 10 examples, because they use `#version 300 es` and this emulator only has GLES 2.0.

Commit `e8e9b640` on `origin/foreman/android6-pass` under `dimmed-2/` holds 236 files: screenshots, frame strips and per-frame line counts (`*-strip*.png`, `*-lines.txt`), the Android 6 burst sheets (`a*-burst.png`), the speech log and a README mapping rows to files. The `.mp4` recordings were not pushed.

Both apps were built from 8944a402 with the checklist's exact steps. **`dbscreen.ps1` worked on Windows first time**: it changed both lines, the variant built, and `git checkout` undid them.

## How timing was measured
- **Android 17:** `screenrecord`, using each frame's own timestamp (45–60 frames a second, frames only when the screen changes). A script counts bright text pixels per code line (a grey line has none). Frames around each key were checked by eye in strips.
- **Android 6:** recording fails there, so a burst of raw `screencap`s, one about every 200 ms, with the same per-line test. It can show whether the typed character and the grey change arrive in the same shot and whether any shot shows a wrong state; it cannot see anything shorter than about 200 ms.

## Pixel_API_37 (Android 17, Gboard)
| Row | Expected | Seen | Result |
|---|---|---|---|
| 1 | Folders and files listed; README in Macros | As expected | Pass |
| 2a `-` | 26–32 grey, 34 plain within 3 frames; 15/37/38/51 stay | Flip in the same frame as the key | Pass |
| 2b | Reverse, as fast | 1 frame (35 ms) | Pass |
| 2c | 34 plain | Same frame | Pass |
| 2d | 26–32 grey | ≤1 frame (16 ms) | Pass |
| 2e | Single flips | Same frame or next | Pass |
| 2f/g `!` | 37/38 plain; 15, 34, 51 grey throughout | 38 plain 1 frame (36 ms) after the key; back 1 frame (18 ms) after the delete | Pass |
| 2 after build | Build changes nothing | No change | Pass |
| 3 | Grey kept; new line grey from first frame; undo restores | As expected; saved file byte-identical | Pass |
| 4a | All `return 0.0;` grey; smooth scroll | Right at top and end; frame gaps during fling median 18–19 ms, same as at rest | Pass |
| 4b | Flip; >50 ms allowed with no grey meanwhile | 2 frames, 114 ms, one frame (≈48 ms) with no grey, then full flip | Pass (allowed) |
| 4c | Flip back | 1 frame (65 ms), no blank frame | Pass |
| 4d | No lag, no grey change | As expected | Pass |
| 5 manual run | a–e | All as expected (Here gone after an edit, back after Run) | Pass |
| 6 `#pragma` edit | Grey goes; back after build | Gone in the key's frame; back 1.03 s later | Pass |
| 7a–e | Pass.frag shows Main's grey; switches work | As expected | Pass |
| **7f** | After an unbuilt space in Main.frag, no grey in Pass.frag | **Line 18 of Pass.frag stays grey** at 0.3 s and 3 s (done twice) | **Fail** |
| 8 | Template only 18 grey; no wrong grey while switching | As expected; only short no-grey moments after a switch | Pass |
| 9 rotation | As listed | As expected | Pass |
| 10 Bright | Grey readable, unlike coloured code | Readable but faint: grey on the disc 3.0:1 (white code 10.2:1; grey on dark 5.3:1) | Pass, borderline |
| 11 old editor | No grey; no Here | No grey with New editor off or on; Look up not offered | Pass |
| 12 Look up with Gboard | Note where | Directly in the bar, keyboard open and closed; not covered | Pass |
| **13** largest font | Fits, formatter layout, fewer breaks in landscape | Landscape fits; **portrait SPIN overflows** | **Fail** (portrait) |
| 14 TalkBack | Here then the expansion as one item | As expected; nothing about grey spoken | Pass |
| A/B/C | — | Below | — |

## Pixel_API_23 (Android 6)
| Row | Seen | Result |
|---|---|---|
| 2a–e | Character and flip in the same shot every time | Pass (200 ms resolution) |
| 2f/g | One shot showed `#if !0` with 38 still grey; repeats all same-shot | Pass, one shot noted |
| 4a | As expected | Pass |
| 4b/c | 5 of 6 transitions show one shot with no grey; complete by next shot | Allowed case |
| 8 Template | Only 18 grey | Pass |
| 12 | Under ⋮ | Pass |
| 13 Here layout | Same as Android 17 | Pass |

Android 6 refuses every `#version 300 es` file ("needs OpenGL ES 3.0"), yet Switches, Long and Parameters show grey and a Here row: from a build the app itself refused.

## Extra A — Here layout (Android 17)
Default font: reads well. App text size "Big": sheet unchanged. Landscape default font: fewer breaks. Android largest font (2.0) landscape: fits. Portrait at 2.0: SPIN's line `* 3.14159265))),` is wider than the sheet; Android wraps it so `*` stands alone and `3.14159265))),` lands at the left edge with no indent, four times (row 13 failure). MIX3 fits but `length(` / `uv))),` is broken inside a 10-character call.

## Extra C — first impression
Android 17: all 10 examples draw at 50–54 fps, nothing broken (ShaderToy's `vec4 color = vec4(1e20);` may puzzle). Android 6: only Template runs.

## TalkBack (row 14, verbatim excerpts)
"Selected RING" … "RING. Heading" → "Macro · line 10" → "#define RING(p, r, w) S(w, 0.0, abs(length(p) - (r)))" → "Here" → "smoothstep(0.02, 0.0, abs(length(uv) - (0.3)))" → "Uses. Button" → "Go to line 10. Button". MIX3: "mix(mix(vec3(0.1, 0.2, 0.5), vec3(0.9, 0.4, 0.2), smoothstep(0.0, 0.5, length(uv))), vec3(1.0, 0.9, 0.6), smoothstep(0.5, 1.0, length(uv)))" as one item.

## Oddities
Error row shifts lines in auto run; long-press always opens Gboard on Android 17; dbscreen asked for notifications (Don't allow); drawer order changes.

## Could not do / deviations
Android 6 timing only via 200 ms bursts; Extra B not possible on Android 6; font set by `settings put`; some typing by key events; three times the app left the screen by my slips (guards blocked inputs; launcher-style return).

## Cleanup
Both emulators back to the earlier fork builds with byte-identical data; settings unchanged; shut down; no worktrees left.
