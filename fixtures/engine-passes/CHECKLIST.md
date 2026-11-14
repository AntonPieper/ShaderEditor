# Engine passes: device checklist

Stack on AntonPieper/ShaderEditor, base `integration/project-mode`:

| PR | Branch | What it covers |
| --- | --- | --- |
| #463 | `engine-passes/1-schedule` | the pass schedule |
| #464 | `engine-passes/2-pass-inputs` | multipass and per-input sampling |
| #465 | `engine-passes/3-formats` | rgba16f and capability checks |
| #466 | `engine-passes/4-resize` | a resize that changes no target keeps the histories, frame and time |

Test the tip: branch `engine-passes/4-resize`, head `09bc883a`. The projects are in `projects/` next to this file. Each one is a folder with `main.frag` and the passes it names.

## Setup

1. Build and install the tip:
   - `./gradlew :app:assembleDebug`
   - wait for `adb shell getprop sys.boot_completed` to return `1`
   - `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Push the projects: `adb push projects /sdcard/Download/engine-passes`.
3. In the app, Open folder → Downloads → `engine-passes`. In DocumentsUI, folder taps need `press --hold-ms 120`. Opening the parent folder lets you switch projects by tapping another `*/main.frag` and using Run this file. In messages, paths then start with the project folder, for example `d-chain-sizes/p2.frag`.
4. Launch with the full component (LeakCanary adds a second launcher):
   `--activity de.markusfisch.android.shadereditor.debug/de.markusfisch.android.shadereditor.activity.SplashActivity`
5. Record the context version. On a GLES 3 emulator, the GLES 2 rows can only be checked if the emulator is switched to OpenGL ES 2.0: Extended controls → Settings → Advanced → OpenGL ES API level → Compatibility, then restart the emulator. Otherwise mark them "not verified on device; covered by JVM tests".

## The projects: what each picture must look like

Unless stated otherwise, quality 1/1 and a portrait screen. "Green band" and "red band" are solid-colour bars written by the shader as a pass/fail verdict.

### a-two-passes: main reads `blur.frag` (current frame)

- Top tenth: a solid green band. Red means `main.frag` read `blur.frag`'s output of an earlier frame, not this one. A flash of red on the very first frame after a switch is a fail too.
- Below the band:
  - left half: a soft-edged white disc on black, gliding left and right (period about 6.3 s);
  - right half: the same disc with a hard edge.
- Where the disc crosses the middle line, its two halves line up at the same height and position.

### b-feedback: a pass reads itself (previous frame) with `initial="seed.frag"`

- On start and after every reset: dark blue with a white cross centred slightly below the middle.
- The cross moves right one pixel per frame, about 1 screen-width every 18 s at 60 fps. It leaves the right edge and comes back in on the left (wraps). It never smears and never doubles.
- Top band: black ramping to full red in about 4.3 s (256 frames), then dropping to black, and so on.
- The whole screen red: fail. The seed was not drawn with `time` and `frame` at 0.
- A reset shows the cross back in the centre and the ramp starting from black.

### c-ping-pong: `a.frag` and `b.frag` read each other's previous frame

- Top half: solid green at all times. Red means the frame order or the history swap is wrong: a history swapped right after its own pass, or a pass read twice or not at all.
- Bottom half: two greys, left `a` and right `b`, both getting lighter at the same rate (about 1.5/255 per frame on average) and wrapping to black about every 2.8 s. Every other frame `b` is one step lighter than `a`; at 60 fps that is not visible.

### d-chain-sizes: four passes of different sizes

Chain `p1.frag` (size 64x64) → `p2.frag` (scale 0.25) → `p3.frag` (scale 0.5) → `main.frag` (scale 1). Four vertical strips, left to right:

| Strip | Colour | Checker cells (1080 px wide screen) | Pass size |
| --- | --- | --- | --- |
| 1 | red and black | about 68 px wide, 150 px high (tall rectangles) | 64x64 |
| 2 | green and black | 16 px | 270x600 |
| 3 | blue and black | 8 px | 540x1200 |
| 4 | white and black | 4 px | 1080x2400 |

- A yellow horizontal line moves slowly up and down. It is at the same height in all four strips at the same moment: one time for all passes. It is thickest in strip 1 and thinnest in strip 4.
- Touch and drag: a yellow ring is centred under the finger, in whichever strip the finger is in. It stays under the finger across strips and gets blockier towards the left. That shows the pointer scaled to each pass.
- Lower the quality to 1/2: strips 2 to 4 get coarser, strip 1 stays the same.

### e-float: an rgba16f target with values above 1

| | Left half (rgba16f) | Right half (rgba8) |
| --- | --- | --- |
| Top | full gradient from black to bright red | black to dark red, reaching only a quarter of full red after a quarter of the half, then flat |
| Bottom | bright green | black |

- If the device cannot draw into half floats: no picture, and an error on `hdr16.frag` line 1: `format=rgba16f needs a device that draws into half-float textures (EXT_color_buffer_half_float or EXT_color_buffer_float), which this device does not have; use format=rgba8`. On GLES 2 without `OES_texture_half_float` the message names that extension instead.
- Record which case you saw, with the GL_EXTENSIONS of the device: `adb shell dumpsys SurfaceFlinger | grep -i extensions`, or note "not known".

### f-sampling: one pass output read nearest and linear in one program

- GLES 3:
  - left half: a 4x4 checkerboard of pure red and pure blue squares with hard edges, each square 1/8 of the screen wide and 1/4 high;
  - right half: the same checkerboard blurred smoothly, pure colour only at the square centres, purple in between, the outer half-squares flat at the edges (clamp).
- GLES 2: no picture, and an error on `main.frag` line 9 that names both inputs: `crisp (f-sampling/main.frag:7) and soft read f-sampling/pattern.frag with different settings, which needs OpenGL ES 3.0; this device only has OpenGL ES 2.0`.

### g-gles2-refused: repeat on a screen-sized target

- GLES 3: the picture of `tile.frag` (red rising to the right, green rising upwards, a white disc in the lower left) tiled twice across and twice up: four copies, each with its own disc in its lower left.
- GLES 2: no picture, and an error on `main.frag` line 7: `tiles reads g-gles2-refused/tile.frag, a target of 1080x2400, not a power of two, so OpenGL ES 2.0 reads it only with wrap=clamp, and this device has no OpenGL ES 3.0; change the settings of tiles or the size of the target`.

### h-one-pass: a single pass, for switching

- Red grows to the right, green grows upwards, blue pulses about every 6.3 s.

### i-fixed-feedback: a fixed 256x256 target that keeps its history

- Exactly one white square, a little left of the lower-middle area, moving right one texel per frame (about 4 screen px per frame) and wrapping around every 4.3 s.
- Top band: red ramping as in b, mixed with full green, so it looks green to yellow. The green must never disappear: a band with no green means `frame` and the history are out of step.
- A second square appearing means the frame number restarted while the history kept going: a fail.

## Checklist

Write down for every step what you saw: pass or fail with a screenshot.

1. Run a to i one after the other. Each matches its description above (GLES 3 rows; GLES 2 rows only on a GLES 2 context).
2. **Switching while running.**
   - h-one-pass → d-chain-sizes → h-one-pass → b-feedback → c-ping-pong → h-one-pass, using Run this file.
   - Each switch shows the new picture within a moment. No black or garbage frames beyond one, and no state left over from the project before.
   - b starts with the cross centred; c and i show green bands from the first frame.
3. **Breaking one pass while it runs.**
   - With d-chain-sizes running, edit `d-chain-sizes/p2.frag` line 42: `own(vec3(0.0, 1.0, 0.0))` → `own(vec3(0.0, 1.0))`.
   - Expected: the four strips keep drawing (the yellow line keeps moving) and the preview is labelled as showing the last successful build.
   - Expected: the error is located at `d-chain-sizes/p2.frag` line 42, not at `main.frag` and not at an assembled line number.
   - Undo the edit: the picture continues, and the error goes away.
   - Repeat with a break in a pass read only through others: in `p1.frag`, change `own(vec3(1.0, 0.0, 0.0))` to `own(red)`. The error must be at the `p1.frag` line.
4. **Home and back (context loss).**
   - Press Home and return to the app, with each of b, c, d and i in turn.
   - Expected: the picture comes back.
   - b: the cross is back in the centre and the ramp starts from black.
   - c and i: the bands are green.
   - i: exactly one square, back at its start.
5. **Rotation.**
   - Rotate with b, c, d and i in turn. The activity is recreated, so this is a context loss: the same expectations as step 4.
   - d: the strips re-lay out for landscape. Strip 1 cells become wide rectangles; the other strips keep their cell sizes in screen pixels.
6. **Resize without context loss: open the keyboard.** Open the soft keyboard in the editor so the preview gets smaller, then close it. If the preview does not change size, resize the app in split screen instead, and note which you used.
   - i-fixed-feedback: the square keeps moving without jumping back, there is still only one square, and the band stays green throughout.
   - b-feedback: it resets (cross back in the centre) when the keyboard opens and again when it closes, because the target is scaled.
   - c-ping-pong: the band stays green (it resets cleanly).
7. **Quality change while running.**
   - With b running, change quality 1/1 → 1/2 → 1/1. Each change resets: cross centred, and the picture gets coarser at 1/2.
   - With d running: strip 1 stays the same at every quality.
8. **Thumbnails.** Save each of a, d and e as it runs. The thumbnail in the list shows the composed final picture (the strips for d, the four quadrants for e), not a single pass.
9. **Wallpaper.**
   - Set d-chain-sizes, then b-feedback, as live wallpaper. Each draws as in the app.
   - Rotate the home screen if the launcher allows it. d re-lays out. b resets only if the wallpaper's size changes. Note whether the wallpaper surface keeps its context; it should, so this exercises the resize path.
10. **Frame time.**
    - Record the fps chip for h-one-pass, a-two-passes, c-ping-pong (three full-screen passes plus present), d-chain-sizes (about 1.8 screens of fill) and e-float, at quality 1/1 and 1/2.
    - Expected: roughly in proportion to the number of full-screen draws. Note the emulator's fps; it is not representative of phones.
    - Note whether any project drops below 30 fps at 1/1.
11. **Memory.**
    - Run `adb shell dumpsys meminfo de.markusfisch.android.shadereditor.debug` with h-one-pass, then with e-float (three 1080x2400 targets, one of them rgba16f) and c-ping-pong (five 1080x2400 rgba8 textures).
    - Record the Graphics or GL mtrack lines.
    - Expected: rough increments of 2,592,000 px × 4 B ≈ 10.4 MB per rgba8 texture and × 8 B ≈ 20.7 MB per rgba16f texture, at a 1080x2400 render size.
12. **GLES 2, only if available.** e, f and g give exactly the errors above. Every other project draws as on GLES 3, except where the device lacks half floats.

## Memory figures

Target memory on a 1080x2400 screen, for a project of four full-resolution passes (`scale=1`) in `format=rgba16f` (8 bytes per pixel). Quality 1/1 is the default, and quality 1/2 is listed for comparison.

| Quality | Render size | One texture | 4 passes, one texture each | 4 passes, all keeping a previous frame |
| --- | --- | --- | --- | --- |
| 1/1 (default) | 1080x2400 | 20,736,000 B (19.8 MiB) | 82.9 MB (79.1 MiB) | 165.9 MB (158.2 MiB) |
| 1/2 | 540x1200 | 5,184,000 B (4.9 MiB) | 20.7 MB (19.8 MiB) | 41.5 MB (39.6 MiB) |
| 2/1 | 2160x4800 | 82.9 MB | 331.8 MB | 663.6 MB |

- At quality 2/1, a device whose GL_MAX_TEXTURE_SIZE is below 4800 refuses the build: "Render size 2160x4800 is larger than ..., lower the quality".
- `rgba8` halves every figure.
- A pass keeps a second texture only when its previous frame is read.
- While an edited revision builds, its targets coexist with those of the plan still drawing. Re-saving such a project can briefly need twice the figure, for example 331.8 MB at quality 1/1 with histories.
- If that allocation fails, the plan still drawing releases its targets, the new one is tried once more, and only then the error is shown.
- No byte budget is enforced; the format's limits are 16 passes per build and the device's texture size and units.

## After the review fixes: what each fix needs on a device

The fixes for the twelve review findings are tested on a recording fake GL device in JVM tests. That fake has no pixels and no driver. These checks are owed on real hardware. The stack is #471, #463–#466 and #472–#478.

| Finding | Fix in | Look at on a device |
| --- | --- | --- |
| 1 GLES 2 reset aliasing | #464 | On a GLES 2 context, the review's project (an initial shader reading one pass with `filter=nearest` and with `frame=previous filter=linear`) is refused with the message naming both inputs. The same project runs on GLES 3. |
| 2 Presentation sampler precision | #465 | Real mobile GPU: an rgba16f seed shader writing 1000 into an initial shader's target, read back by the running pass (green iff about 1000). Also differences below 1/255 survive the stretch. Both on a highp and on a mediump-only fragment device, if one is available. |
| 3 HDR brightness ceiling | #465 | With the code shown (picture behind code), an rgba16f output of 4.0 is no brighter than white behind the code at Dim, Balanced and Bright. |
| 4 Stale candidate activation | #472 | Type quickly so that edits arrive while a heavy multipass plan swaps in. The newest text always ends up running; the clock never restarts on an older one. |
| 5 Render size above the texture limit | #466 | Hard to reach on phones. On a device with GL_MAX_TEXTURE_SIZE 4096: quality 2/1 on a screen wider than 2048 px with a project of fixed `size=` targets runs, while a project with a full-size scaled target gets the error on its target line. |
| 6 Touch coordinates | #474 | d-chain-sizes: hold a finger still, change the quality with the other hand (or wait for a resize). The ring stays under the finger in every strip, and touchStart does not jump. |
| 7 One pixel minimum | #475 | A tiny view (split screen at its smallest, quality 1/32) still draws. |
| 8 Shared initial shader drawn once | #463 | The review's two-consumer project: the reset frame takes about one shader draw, not two (frame time on a real GPU). Both histories start from the seed. |
| 9 GL object failures | #473 | Resource stress on a real GPU: 16 full-size rgba16f passes with histories, then edits. On allocation failure the old picture stays (or the retry succeeds). Nothing is corrupted and nothing draws into the window. Repeated swaps and pause and destroy. |
| 10 Pass file on unlocated errors | #476 | A pass that exceeds the device's uniform or sampler limits. The error names that pass file. |
| 11 Wallpaper errors | #477 | Real wallpaper host: a wallpaper shader that cannot run (rgba16f on a device without half-float rendering, or a compile error) shows a toast with the error and its place, once. The last working shader keeps drawing, or black. |
| 12 Typed empty samplers | #478 | GLES 3 GPU: `#version 300 es` with an unprovided `usampler2D`, `isampler3D`, `sampler2DArray` and `sampler2DShadow`. Each reads 0 (show green iff 0). No GL errors in the log. |

Still owed from before: GLES 2 rows (e, f, g), Android 6 and other old Android versions, a real GPU, a resize that keeps the context, the wallpaper preview, and memory.
