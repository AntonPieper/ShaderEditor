# dimmed-3: re-run of rows 7, 13 and 15 (fork PR #807 at f8039a6e)

Checklist: `origin/foreman/dimmed-rows` at **a350f1f47** (RE-RUN section). App: debug build of PR #807's head **f8039a6e1a01e2871510c25dea48d436ad814182**. That was still the head when I wrote this. Pixel_API_37 (Android 17, Gboard, hardware keyboard attached) for rows 1, 7, 13, 2a/2f and ShaderToy. Pixel_API_23 (Android 6, OpenGL ES 2.0 only) for row 15. Test folders were imported as in step 0, without the picker: `Macros`, `Extra/Passes`, `Extra/Long` and `Extra/Es100` were copied into the library with run-as.

**Overall: everything passed.** Row 7 (rewritten) passes in all its steps. Row 13 fits at the largest font in portrait and landscape. No line wraps or continues at the left edge, and none breaks the checklist's rules. At the largest font in portrait, MIX3 comes out at step 1 rather than the checklist's step 2 (fewer lines, so that is the rule working; quoted below). Row 15 passes. Rows 2a and 2f have not regressed: each flip lands in the same frame as the key or the next one.

## Results

| Row | Expected | Seen | Screenshots | Result |
|---|---|---|---|---|
| 1 Import | `Extra/Es100/Rings.frag` listed | Listed next to Macros, Passes and Long | r1 | Pass |
| 7a | Main.frag: line 18 grey | 18 grey, 16 plain; picture orange-tinted | r7a | Pass |
| 7b | Pass.frag runs Main again; 18 grey a moment after | Subtitle "▶ Passes/Main.frag"; 18 grey, 16 plain; picture still Main's tinted rings | r7b | Pass |
| 7c | Delete `1` of line 9 `#define RINGS 1`: 18 plain. Type `0`: 16 grey | After the delete: 16 and 18 plain. After `0`: 16 grey, 18 plain | r7c1, r7c2 | Pass |
| 7d | Delete `0`, type `1`: 16 plain, then 18 grey | After the delete: both plain (r7d0 at once, r7d1 1.5 s later). After `1`: both plain at once (r7d2), 18 grey within 3 s (r7d3) | r7d0–r7d3 | Pass |
| 7e | Tap Main.frag: 18 grey | 18 grey, 16 plain | r7e | Pass |
| 7f-1 | Run manually; delete `1` of line 10 `#define TINT 1`: 16 and 18 plain | Run mode "Run manually"; before the edit 18 grey (r7f0); after the delete both plain (r7f1) | r7f0, r7f1 | Pass |
| 7f-2 | Type `0`: 16 grey, 18 plain, picture keeps its orange tint | 16 grey, 18 plain; background behind the code still orange (median RGB 45/27/14, the same as before the edit) | r7f2 | Pass |
| 7f-3 | Tap Pass.frag: picture loses its tint, Pass.frag line 18 grey | Subtitle "▶ Passes/Main.frag"; picture plain grey (mean RGB 48/48/48); 18 grey, 16 plain | r7f3 | Pass |
| 7f-4 | Tap Main.frag: 16 grey, 18 plain | 16 grey, 18 plain (picture stays untinted, file on disk `#define TINT 0`) | r7f4 | Pass |
| 7f-5 | Restore TINT 1, Run code, Auto run back | TINT 1 on disk; after Run code the picture is tinted again; run mode back to "Auto run on change" (`run_mode` 1) | r7f5 | Done |
| 13 | Largest font, portrait and landscape: fits, no left-edge continuation, formatter layout, fewer breaks in landscape, sheet scrolls | All six layouts fit; no line continues at the left edge; landscape has fewer breaks; the sheet scrolls in each tall case. Layouts quoted below | b13-* | Pass |
| 15a (A6) | Switches.frag: GLES3 message, no grey | "#version 300 es needs OpenGL ES 3.0, but this device only has OpenGL ES 2.0" under line 1; no grey line | a15a | Pass |
| 15b (A6) | `-` before the `2` of line 9: still no grey | `#define AA -2`: still no grey | a15b | Pass |
| 15c (A6) | Parameters.frag RING Look up: no Here | Same message on line 1. Look up (under the bar's ⋮) shows RING, "Macro · line 10", the definition, Uses, "Go to line 10". **No Here row** | a15c-file, a15c-bar, a15c-menu, a15c | Pass |
| 15d (A6) | Main.frag and Pass.frag: no grey | Both show the GLES3 message, no grey line | a15d-main, a15d-pass | Pass |
| 15e (A6) | Es100/Rings.frag runs; line 14 grey; RING on line 16 Here `smoothstep(0.02, 0.0, abs(length(uv) - (0.3)))` | Runs at 56 fps (two rings); line 14 grey; Look up on RING (line 16): "Macro · line 7", **Here** `smoothstep(` / `    0.02,` / `    0.0,` / `    abs(length(uv) - (0.3)))` | a15e, a15e-bar, a15e-sheet | Pass |
| 15f (A6) | Template.frag runs; line 18 grey; lines 2 and 4 not grey | Runs at 56 fps (stripes); line 18 grey; lines 2 and 4 plain | a15f | Pass |
| 2a (A17) | `-` before `2` of line 9: 26–32 grey and 34 plain within 3 frames; 15, 37, 38, 51 stay grey | The `-` appears in frame 69 (1997 ms); 26–32 grey and 34 plain in frame 70 (2015 ms), **1 frame / 18 ms** later. 15, 37, 38, 51 grey in every frame; no frame with all grey gone | r2a-pre, r2a-strip, r2a-lines.txt | Pass |
| 2b (A17) | Delete the `-`: the reverse, as fast | Delete in frame 233 (5798 ms); flip back in frame 234 (5814 ms), **1 frame / 16 ms** | same | Pass |
| 2f (A17) | `!` before `0` of line 36: 37 and 38 plain; 15, 34, 51 grey in every frame | `!` appears in frame 113 (2618 ms) and 38 turns plain **in that same frame**. 15, 34 and 51 grey in every frame. Line 37 is a comment, so its brightness is the same either way and it cannot be judged from pixels (as in dimmed-2) | r2f-pre, r2f-strip, r2f-lines.txt | Pass |
| 2g (A17) | Delete the `!`: 37 and 38 grey again | Delete and 38 grey again in the same frame (277, 6435 ms) | same | Pass |
| ShaderToy (A17) | First impression with the README's new paragraph | See below | st-code, st-picture | — |

Each step in row 7 changed only the lines named. Every file edited for row 7 was put back and checked against the checklist's text.

## Row 13: the Here layouts, line by line

Taken from the sheet's text (uiautomator) and checked against the screenshots. Leading spaces are as shown. "Fits" means no line reaches past the box and Android wrapped nothing.

### Android largest font (2.0), portrait (about 20 columns)

**MIX3** (Parameters.frag line 20), **step 1**, 19 lines (b13-MIX3-p, -p2, -p3):
```
mix(
 mix(
  vec3(
   0.1,
   0.2,
   0.5),
  vec3(
   0.9,
   0.4,
   0.2),
  smoothstep(
   0.0,
   0.5,
   length(uv))),
 vec3(1.0, 0.9, 0.6),
 smoothstep(
  0.5,
  1.0,
  length(uv)))
```
This differs from the checklist's 20-column picture, which uses step 2 and 22 lines. Here step 1 lets ` vec3(1.0, 0.9, 0.6),` stay whole, which gives fewer lines, and everything fits, so it follows the "fewest lines" rule. `length(uv)` is never split. Nothing continues at the left edge.

MIX3 definition at the top of the sheet:
```
#define MIX3(a, b, c,
  t)
 mix(
  mix(
   a,
   b,
   S(0.0, 0.5, t)),
  c,
  S(0.5, 1.0, t))
```
(The checklist shows `#define MIX3(a, b,` / `  c, t)`. Here the head breaks after `c,` with `t)` two steps in. It fits.)

**RING** (Parameters.frag line 19), step 4, 6 lines (b13-RING-p):
```
smoothstep(
    0.02,
    0.0,
    abs(
        length(uv)
            - (0.3)))
```
Definition: `#define RING(p, r, w)` / `    S(w, 0.0, abs(length(p) - (r)))`.

**SPIN** (Nested.frag line 19), **step 1**, 17 lines. This matches the checklist exactly (b13-SPIN-p, -p2, -p3):
```
mat2(
 cos(
  ((time * 0.05)
   * (2.0
    * 3.14159265))),
 sin(
  ((time * 0.05)
   * (2.0
    * 3.14159265))),
 -sin(
  ((time * 0.05)
   * (2.0
    * 3.14159265))),
 cos(
  ((time * 0.05)
   * (2.0
    * 3.14159265))))
```
Definition: `#define SPIN(t)` / `    ROT(TURNS(t))`. No `*` stands alone, `3.14159265` is never split, and the longest line, `    * 3.14159265))))`, fits.

### Android largest font (2.0), landscape

**MIX3**, step 2, 10 lines (b13-MIX3-l, -l2, -l3):
```
mix(
  mix(
    vec3(0.1, 0.2, 0.5),
    vec3(0.9, 0.4, 0.2),
    smoothstep(
      0.0,
      0.5,
      length(uv))),
  vec3(1.0, 0.9, 0.6),
  smoothstep(0.5, 1.0, length(uv)))
```
Definition: `#define MIX3(a, b, c, t)` / `    mix(` / `        mix(a, b, S(0.0, 0.5, t)),` / `        c,` / `        S(0.5, 1.0, t))`.

**RING**, 4 lines (b13-RING-l): `smoothstep(` / `    0.02,` / `    0.0,` / `    abs(length(uv) - (0.3)))`.

**SPIN**, step 4, 13 lines (b13-SPIN-l, -l2, -l3, -l4):
```
mat2(
    cos(
        ((time * 0.05)
            * (2.0 * 3.14159265))),
    sin(
        ((time * 0.05)
            * (2.0 * 3.14159265))),
    -sin(
        ((time * 0.05)
            * (2.0 * 3.14159265))),
    cos(
        ((time * 0.05)
            * (2.0 * 3.14159265))))
```
Definition on one line: `#define SPIN(t) ROT(TURNS(t))`. Landscape has fewer breaks in all three cases.

### Default font (1.0), portrait

**MIX3**, 7 lines (b13-MIX3-d, -d2):
```
mix(
  mix(
    vec3(0.1, 0.2, 0.5),
    vec3(0.9, 0.4, 0.2),
    smoothstep(0.0, 0.5, length(uv))),
  vec3(1.0, 0.9, 0.6),
  smoothstep(0.5, 1.0, length(uv)))
```
**RING**, 4 lines (b13-RING-d): as in landscape at 2.0. **SPIN**, 13 lines (b13-SPIN-d, -d2): as in landscape at 2.0.

### Left edge and readability
- **No line is continued at the left edge without indent**, in any of the nine layouts. The sheet's text has its own line breaks, and Android wrapped nothing (compare dimmed-2, where SPIN at portrait 2.0 wrapped four times).
- **Harder to read than in the editor?** At the default font and in landscape, no: the layout reads better than the one long editor line. At the largest font in portrait it is still readable, but two things are harder than in the editor:
  - SPIN's step-1 staircase (`((time * 0.05)` / `* (2.0` / `* 3.14159265)))`) spreads one product over three lines, one column apart, and you have to scroll the sheet to see all four calls.
  - MIX3 (19 lines) needs scrolling, and its one-column steps make it hard to see which `vec3(` belongs to which `mix(`.
  - The editor shows the same code on one line that you scroll sideways. Nothing is wrong by the checklist's rules; this is only my impression.
- In the same sheet the definition and the Here row can use different steps (landscape MIX3: definition step 4, Here step 2). Each follows the fewest-lines rule; I only note it.

## ShaderToy.frag first impression (Android 17)
- It runs at 59 fps: a cream rounded square turning slowly on blue, with a darker rim (st-picture). The code (st-code) shows lines 1–31 of import header, then `// The usual shorthands of ShaderToy code.` on line 33.
- With the README's new paragraph, `vec4 color = vec4(1e20);` on line 28 is explained: it is the import's, like ShaderToy's site, and the example's own code starts at line 33. That answers what puzzled me in dimmed-2.
- Remaining impression: someone who opens only the file still sees 31 lines of header with no hint that they come from the import. The comment on line 8 ("In the form the ShaderToy import writes, made for this folder.") covers this only partly, and the `1e20` reason lives only in the README.

## Oddities and my own slips
1. **My slips while selecting a name on Android 17 (all undone, files checked):**
   - In Parameters.frag, one tap meant for Look up hit the `(` extra key while `MIX3` was selected and replaced it. A later tap meant for "an empty place" hit Gboard's space bar and replaced it again. Both were undone with Ctrl+Z, and Parameters.frag on disk equals the checklist's file (md5 caeca61c…).
   - In Nested.frag, a completion chip typed `SPINPIN`. Undone; Nested.frag equals the checklist's (md5 9419675d…).
   - In landscape at font 2.0, a long-press while Gboard was opening became a drag-selection over several lines. It was collapsed with an arrow key; nothing was typed.
   - The working method in landscape: open the keyboard first, move the caret with keys, double-tap the word, then tap Look up.
2. Ctrl+Q with the caret in `SPIN` (Android 17, landscape, Gboard up) showed nothing. In names-2, Ctrl+Q opened Look up. I didn't look into it further, so this may be my key event and not the app.
3. Rotating to landscape with the Look up sheet open closed the sheet.
4. Android 6 offers Look up under the selection bar's ⋮ (CUT / COPY / PASTE / ⋮ → LOOK UP, SHARE, SELECT ALL, API DEMOS).
5. Android 17 restore: the first data push failed (Git Bash rewrote `/data/local/tmp`) after the app data had already been removed. It was redone at once with path conversion off, and verified byte-identical.
6. Between the end of row 15 and writing this report, T3 Code restarted, and Pixel_API_23 was already shut down when I came back. Its restore had been finished and verified before that.

## Cleanup
- **Pixel_API_37:** earlier fork build back (sha1 f706f08a), app data byte-identical to the backup (141/141 entries). Run mode back to Auto (it is in the restored prefs), font_scale 1.0, rotation auto 1 / user 0, Gboard. My import folder `/data/local/tmp/se-rows` removed. Shut down through the device panel.
- **Pixel_API_23:** earlier fork build back (md5 f0655b81), app data byte-identical (28/28 entries; the same as before dimmed-3, only Default.frag). Font 1.0, rotation 0/0, LatinIME, never changed. `se-rows` and `d.tar` removed. Shut down.
- agent-device session list was empty after the A17 part; after T3's restart there is no session or command left. No emulator, no Java/Gradle process running. The worktrees `ShaderEditor-rows3` and `ShaderEditor-trial3` are removed after this push. `ShaderEditor-typo` isn't mine and was left untouched.
- Not pushed: the two screen recordings `r2a.mp4` and `r2f.mp4` (kept locally).
