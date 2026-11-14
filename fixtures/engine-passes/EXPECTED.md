# What each project must look like, and the rows to run

Quality 1/1, portrait. A "green band" or "red band" is the shader's own pass/fail verdict.
Open the folder `engine-passes` (the parent) in the app; switch projects by tapping another
`*/main.frag` and "Run this file". Messages then name paths like `d-chain-sizes/p2.frag`.
Project k is green only from build 977a1f28 (#495) on.

## Pictures
- a-two-passes: top tenth solid GREEN (red, even for one frame after a switch = main read an older
  frame of blur). Below: left a soft white disc on black gliding left/right (period ~6.3 s), right the
  same disc with a hard edge; the halves line up where the disc crosses the middle.
- b-feedback: on start and after every reset dark blue with a white cross a little below the middle;
  the cross moves right 1 px per frame and wraps; never smears or doubles. Top band black ramping to
  red over ~4.3 s, then black again. Whole screen red = fail (seed drawn with nonzero time/frame).
- c-ping-pong: top half always GREEN (red = frame order or history swap wrong). Bottom half two greys
  (a left, b right) brightening together, wrapping about every 2.8 s.
- d-chain-sizes: four vertical strips: red/black cells ~68x150 px (p1 at 64x64), green/black 16 px,
  blue/black 8 px, white/black 4 px. One yellow line moves up and down at the same height in all four
  strips (thickest in strip 1). A finger draws a yellow ring that stays under it in every strip,
  blockier towards the left. At quality 1/2 strips 2-4 get coarser, strip 1 does not change.
- e-float: left half (rgba16f): top a full black-to-red gradient, bottom bright green. Right half
  (rgba8): top black to dark red, flat after the first quarter; bottom black. WITHOUT half-float
  rendering: an error on `hdr16.frag` line 1 naming EXT_color_buffer_half_float or
  EXT_color_buffer_float (on GLES 2: OES_texture_half_float). Record which case, with `GL extensions`.
- f-sampling: GLES 3: left a hard-edged 4x4 red/blue checkerboard, right the same board smoothly
  blurred. GLES 2: an error on `main.frag` line 9: "crisp (f-sampling/main.frag:7) and soft read
  f-sampling/pattern.frag with different settings, which needs OpenGL ES 3.0; this device only has
  OpenGL ES 2.0".
- g-gles2-refused: GLES 3: the tile tiled 2x2, each copy with a white disc lower left. GLES 2: an
  error on `main.frag` line 7: "tiles reads g-gles2-refused/tile.frag, a target of WxH, not a power of
  two, so OpenGL ES 2.0 reads it only with wrap=clamp, and this device has no OpenGL ES 3.0; change
  the settings of tiles or the size of the target".
- h-one-pass: red grows to the right, green upwards, blue pulses about every 6.3 s.
- i-fixed-feedback: exactly ONE white square moving right, wrapping every 4.3 s. Top band green to
  yellow, never without green. Losing the green, or a second square = fail.
- j-hdr-ceiling: behind the code at Dim, Balanced and Bright the left half (4.0) and the right half
  (1.0) look identical (at Balanced both read 51 = 0x33). With the code hidden: white.
- k-float-initial: GREEN when the rgba16f seed's 1000 and 0.5/255 survive the initial stretch.
- l-touch-quality: a grid with a yellow ring at the touch and a cyan dot at the touch start. Change
  the quality without touching again: both stay at the same screen spot.
- m-texture-limit: largest texture size below 16384: an error naming that size; otherwise a red
  gradient.
- n-unit-error: an error naming `n-unit-error/blur.frag` (too many samplers / texture units), not
  main.frag.
- o-typed-empties: needs GLES 3: all four quadrants green, four warnings name the samplers, no GL
  errors in the log. On GLES 2: a capability error for `#version 300 es`.

## Rows
1. Run a to i in turn; each matches its picture. GLES 2 rows only on a GLES 2 context.
2. Switching while running: h -> d -> h -> b -> c -> h with Run this file. New picture at once, at
   most one black/garbage frame, nothing left over. b starts with the cross centred; c and i green
   from the first frame.
3. Breaking a pass while it runs: with d running change `d-chain-sizes/p2.frag` line 42 from
   `own(vec3(0.0, 1.0, 0.0))` to `own(vec3(0.0, 1.0))`. Strips keep drawing (line keeps moving), the
   preview is labelled as the last successful build, the error is at `d-chain-sizes/p2.frag:42`. Undo:
   error gone. Repeat in p1.frag (`own(vec3(1.0, 0.0, 0.0))` -> `own(red)`): error at the p1 line.
4. Home and back (context loss) with b, c, d, i: picture comes back; b cross centred, ramp from black;
   c and i bands green; i exactly one square back at its start.
5. Rotation with b, c, d, i: as row 4; d re-lays out (strip 1 cells become wide rectangles).
6. Resize keeping the context (keyboard open/close, or split screen; say which): i keeps moving, one
   square, green band; b resets on each resize; c stays green.
7. Quality change: b: 1/1 -> 1/2 -> 1/1 resets each time, 1/2 coarser. d: strip 1 never changes.
8. (not applicable to folder files)
9. Wallpaper: set d, then b, as live wallpaper; each draws as in the app. Rotate home if possible.
10. Frame time: fps chip for h, a, c, d, e at quality 1/1 and 1/2. Note any project below 30 fps.
11. Memory: `dumpsys meminfo <pkg>` with h, then e, then c; record Graphics / GL mtrack lines
    (expect ~10.4 MB per rgba8 and ~20.7 MB per rgba16f texture at 1080x2400). Also after ten
    switches: no growth.
12. GLES 2: e, f and g give exactly the errors above; everything else draws as on GLES 3.

## Review findings to look at on a device
1. GLES 2: one pass read with `filter=nearest` and with `frame=previous filter=linear` by an initial
   shader is refused, naming both inputs. Runs on GLES 3.
2. rgba16f seed of 1000 and differences below 1/255 survive the stretch (project k).
3. Behind the code at Dim/Balanced/Bright, 4.0 is no brighter than white (project j).
4. Fast typing while a heavy multipass plan swaps in: the newest text ends up running; the clock never
   restarts on an older one.
5. Max texture size 4096: quality 2/1 on a screen wider than 2048 px runs fixed-size targets; a
   full-size scaled target gets the error on its target line.
6. Finger held still while the quality changes: ring stays under it, touchStart does not jump (d, l).
7. A tiny view (smallest split screen, quality 1/32) still draws.
10. A pass over the device's uniform/sampler limits: the error names that pass file (project n).
11. A wallpaper shader that cannot run shows one toast with the error and its place; the last working
    shader, or black, keeps drawing.
12. GLES 3: unprovided usampler2D, isampler3D, sampler2DArray, sampler2DShadow read 0, no GL errors (o).
