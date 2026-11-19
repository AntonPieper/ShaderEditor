# names-2: second device pass of the name actions (PR #822 at 80115fca)

Built the debug app of fork PR #822's head **80115fca3b5b51149b40957946620cd75b170d33** (`gh pr view` gave the same head at start and when the coordinator's skip note arrived). Pixel_API_37 (Android 17, Gboard, TalkBack 17) for everything, Pixel_API_23 (Android 6) for row 5.

Per the coordinator's later instruction, **rows 6 to 11 and 13 are skipped** (the Definition part is being rebuilt). I had already run rows 6–10 and part of 11 on Android 17 before that note arrived; those results are listed separately at the end for information only.

The test folder held no hidden `.shadereditor-*.tmp` file before I started, and none appeared during the pass on either emulator (checked with `find files -name '.shadereditor-*'`).

Test files: `Names/Names.frag`, `Names/lib/sdf.glsl` (as in names-1), `Names/Nowhere.frag` (uses undeclared `glw`, no include), `Names/Other.frag` (includes "lib/sdf.glsl", calls `sdBox`). For TalkBack row 1 Names.frag was used without the `+ glw * 0.0` term so that it builds (file named `Names-ok.frag` in the scratch set); line numbers below are that file's (disc declared on line 5, called on line 14). The user's line numbers (6 and 15) correspond to the names-1 numbering with one more line at the top.

## Results

| Row | Expected | Seen | Result |
|---|---|---|---|
| 1 TalkBack: Go to definition of disc | Only the landing "Line 6: float disc(vec2 p, …) {", no block of code before it | Only **"Line 5: float disc(vec2 p, vec2 center, float radius, float soft) {"** | **Pass** (fixed) |
| 1 Back to line | Only "Line N: …" | Only **"Line 14: 	float glow = disc(p, vec2(0.5), 0.2, 0.01);"** | **Pass** |
| 1 sdCircle → Definition → vec2 row | Only its landing line | Double-tap on the vec2 row spoke a block of code lines first ("	vec2 p = gl_FragCoord.xy / resolution; / 	float d = sdCircle(p - 0.5, 0.2); / 	float glow = …"), then "Line 1 of 4. Editing. … Edit box. Out of list", then **"Line 1: float sdCircle(vec2 p, float r) { return length(p) - r; }"** | **Fail** (extra speech before the landing) |
| 2 glw in Nowhere.frag (running) | "glw, No definition found" (+ Look up hint), then "Uses of glw. Button" | Touch: **"glw, No definition found"**, hint "Double-tap to Look up. Double-tap and hold to long press"; swipe: **"Uses of glw. Button"** | **Pass** |
| 3 Tap a name's form to open Look up | The word once, then item by item; no reverse readout | On `disc`: "float disc(vec2 p, vec2 center, float radius, float soft) **Rename, Uses, Definition, Function · line 5, disc, Drag handle**", then "keyboard hidden", "disc", "Expanded. Drag handle. Button. Drag handle". Repeated twice, same. | **Fail** (reverse readout still there) |
| 4 Row keys named by TalkBack | Tab, ( ; = * { | Android 17: cannot show (hardware keyboard always attached). Pixel_API_23: no TalkBack installed. | **Not possible** |
| 5 [A6] Half-key rule | Half a key when more can be swiped in; all fit → nothing cut | Keys alone: 8 whole keys (Tab ( ) ; = * { }), last ends at x=1000 of 1080: nothing cut. Keys + Tune: `{` cut to 66 of 121 px (≈ half) before Tune; swiping brings `}` in, Tune stays; at the swiped end the first visible key `(` is 66 px (half) on the left. No sliver, no fade seen. **Way back + keys** / **way back + keys + Tune**: could not obtain a way back on Android 6 (Definition tap on `disc` emptied the row for a moment but did not jump; F12 did not jump either) | **Pass** for keys alone and keys + Tune; way-back combinations **not done** |
| 12 Hardware keys (A17) | F12, Shift+F12, F2, Ctrl+Q, Alt+← with/without way back, holding Alt+← | F12 on `disc`: jumps, way back "Line 14". Alt+← with way back: back to line 14. Shift+F12: Uses sheet ("disc"). F2: rename sheet "Rename 3 places". Ctrl+Q: Look up ("disc", "Function · line 5"). Alt+← without a way back: caret moved, no jump (row unchanged). Alt+← held 2 s (keycombination -t 2000) after F12: went back once, stayed (no run-on). Four quick Alt+← after one jump: back once, then plain caret moves. | **Pass** |
| 14 Way back through three jumps | Back walks the jumps in reverse and ends | Jumps: disc l14 → (way back Line 14), brightness… l16 → (Line 16), disc l9 → (Line 9). Back: Line 16 → Line 14 → end (no way back left) | **Pass** |

## TalkBack speech verbatim (Android 17)

**Row 1** (reading control: Actions)
- Actions list on `disc`: "Look up disc" → "Go to definition of disc"
- Double-tap "Go to definition of disc": **"Line 5: float disc(vec2 p, vec2 center, float radius, float soft) {"**
- Actions: "Uses of disc", "Rename disc", "Back to line 14"
- Double-tap "Back to line 14": **"Line 14: 	float glow = disc(p, vec2(0.5), 0.2, 0.01);"**
- Caret moved to `sdCircle` with keys (spoken: a long line window, then words).
- Actions: "Look up sdCircle" → "Go to definition of sdCircle"; double-tap: "Drag handle", "Expanded. Drag handle. Button. Drag handle", "sdCircle"
- Swipes: "sdCircle. Heading" → "2 definitions" → "The call on line 13 passes vec2, float: line 1 fits." → "Names/lib/sdf.glsl. Heading. In list" → "Names/lib/sdf.glsl, line 1: float sdCircle(vec2 p, float r)"
- Double-tap that row: "	vec2 p = gl_FragCoord.xy / resolution; / 	float d = sdCircle(p - 0.5, 0.2); / 	float glow = disc(p, vec2(0.5), 0.2, 0.01);" → "Line 1 of 4. Editing. float sdCircle(vec2 p, float r) { return length(p) - r; } / float sdCircle(vec3 p, float r) { return length(p) - r; }. Edit box. Out of list" → **"Line 1: float sdCircle(vec2 p, float r) { return length(p) - r; }"** → "Shader Editor"

**Row 2**
- Opening Nowhere.frag: "Line 5: 'glw' : undeclared identifier", "Line 5 of 8, error. Editing. … Edit box. Out of list"
- Double-tap the code: "Showing Password keyboard"
- Touch the grey form: **"glw, No definition found"**, hint "Double-tap to Look up. Double-tap and hold to long press"
- Swipe right: **"Uses of glw. Button"**
- (Double-tapping the glw form opened the **Uses** sheet, not Look up: "glw. Heading", "2 places in 2 files", "Names/Nowhere.frag. Heading. In list", "Line 5: float v = glw * 0.0 + p.x;")

**Row 3** (on `disc`)
- Touch the form: "disc takes p, center, radius and soft"
- Double-tap: "Double-tap to Look up. Double-tap and hold to long press" → **"float disc(vec2 p, vec2 center, float radius, float soft) Rename, Uses, Definition, Function · line 5, disc, Drag handle"** → "keyboard hidden" → "disc" → "Expanded. Drag handle. Button. Drag handle"
- Then item by item: "disc. Heading" → "Function · line 5" → "Definition. Button" → "Uses. Button" → "Rename. Button"
- Same sequence on a second try.

**Row 4**: not possible (see table).

## Oddities
1. Row 3: the reverse readout of the whole Look up sheet is still spoken once before "disc".
2. Row 1 (list): choosing a row in the Definition list speaks the old caret's line window before the landing line.
3. Row 2: double-tapping the `glw` form (No definition found) opens Uses, whereas the hint says "Double-tap to Look up".
4. TalkBack's own notification permission dialog reappeared during the pass (answered Don't allow); its flags were cleared again at the end.
5. On Android 6 the row shows keys whenever the on-screen keyboard is up; Definition only appears with the caret on a name, and my taps on it did not jump (row 5 way-back states not obtained).

## Informational: rows 6–11 run on Android 17 before the skip note (not part of this pass's verdict)
- 6: `disc` → jump with way back; `sdCircle` → list of two with "The call on line 13 passes vec2, float: line 1 fits."; `glw` in Nowhere.frag → "glw  No definition found" + Uses, Look up "No definition found"; `x` in `#define SAT(x)` body → "x" + Definition, after tapping: "x" + Uses (no jump visible); `clamp` → "clamp(x, minVal, maxVal)" only; local `k` after `#if 1` → **list of one with "There may be declarations the editor cannot see."** (never a silent jump).
- 7: Other.frag shown while another file runs: `sdBox` → form + Definition at once; jumps into lib/sdf.glsl onto the name. In sdf.glsl itself: `sdCircle` → "sdCircle(p, r) · 2 forms" + Uses.
- 8: Nowhere.frag + `#include "lib/sdf.glsl"`: Definition appears. **After removing the include line, Definition stayed for 40 s and still jumped into lib/sdf.glsl** until the file was saved; after save: "sdBox  No definition found" + Uses (expected: name + Uses only, never "No definition found").
- 9: `nope` with an unreadable include: name + Uses; F12 → Look up with "Name"; "No definition found" nowhere.
- 10: rename sdf.glsl → shapes.glsl from the drawer: dialog offered "Also change line 2 of Other.frag" (not Names.frag, which also includes it); after the rename (unsaved) an error row "File not found: Names/lib/sdf.glsl" and an empty row on sdBox; after saving, sdBox → Definition jumps into shapes.glsl. Renamed back afterwards.
- 11: one try jumped to the right line; my key sequence then failed and I restored the file; not completed.

## Could not do
Row 4 (no on-screen keys on Android 17; no TalkBack on Android 6). Row 5 way-back states on Android 6. Rows 6–11, 13 skipped on instruction.

## Cleanup
- Pixel_API_37: earlier fork build back (sha1 f706f08a), data byte-identical to before (141/141 entries; DB, prefs, draft hashes equal). TalkBack off, Display speech output off, log level ERROR, TalkBack's notification flags back to USER_SENSITIVE_WHEN_GRANTED. Font 1.0, density 420, rotation 1/0, Gboard. Shut down.
- Pixel_API_23: earlier fork build back (md5 f0655b81), data byte-identical (28/28; only Default.frag). Font 1.0, density 440, rotation 0/0, LatinIME. Shut down.
- Worktree ShaderEditor-names2 removed; no Gradle daemon; agent-device session list empty.
