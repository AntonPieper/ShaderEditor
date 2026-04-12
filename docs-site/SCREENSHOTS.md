# Media Capture Guide

Complete guide for capturing every screenshot and recording used on the docs
site. Raw files go in `docs-site/raw/`, the conversion script outputs
web-ready WebM/WebP to `docs-site/public/media/`.

---

## 1. Device & Environment Setup

- **Device**: physical Android phone, 1080p+ display. Emulators work for
  static screens but produce choppy recordings.
- **Theme**: dark system theme. The site is dark — light screenshots clash.
- **Clean state**: dismiss all notifications, hide the status bar clock if
  possible (or crop later), turn off "demo mode" battery overlays unless
  you want a consistent battery icon.
- **ShaderEditor settings** (for consistency across captures):
  - Font: `JetBrains Mono` or whichever ships as default.
  - Tab width: 4 (the code in examples uses tabs rendered at this width).
  - Line numbers: on.
  - Extra keys: on.
  - Run mode: auto (for live-preview captures) or manual (when explicitly
    showing manual-run flow).
- **Orientation**: portrait (9:16 or 9:19+ with notch) for everything except
  OG image.
- **Screen recording resolution**: native device resolution is fine; the
  conversion script scales down.

---

## 2. Recording Tools

| Tool | Notes |
|------|-------|
| `scrcpy --record=file.mp4` | Best for desktop capture. Low overhead, no on-device watermarks. Add `--no-audio` to skip mic. |
| Built-in Android recorder | Works but may add UI overlays (countdown, stop button). Crop if needed. |
| `adb shell screenrecord /sdcard/file.mp4` | Headless, no overlays. Limited to 3 min and H.264. Pull with `adb pull`. |

For screenshots (static screens): `adb shell screencap -p /sdcard/file.png`
then `adb pull`.

---

## 3. Directory Structure

```
docs-site/
├── raw/                          ← raw captures (git-ignored)
│   ├── hero/
│   │   ├── hero-editor.mp4
│   │   └── hero-editor.thumb    ← optional poster sidecar
│   ├── features/
│   │   ├── feat-live-preview.mp4
│   │   ├── feat-textures.png    ← screenshot-only items
│   │   └── ...
│   ├── gallery/
│   │   ├── screen-editor.mp4
│   │   ├── screen-settings.png
│   │   └── ...
│   ├── examples/
│   │   ├── sample-touch.mp4
│   │   └── ...
│   └── og/
│       └── og-image.png
├── public/media/                 ← web-ready output (committed)
│   ├── hero-editor.webm
│   ├── hero-editor.webp
│   └── ...
└── scripts/
    └── convert-media.sh
```

Naming rule: **raw file basename = output file basename**. Subdirectories
are for human organization only — the script flattens output to
`public/media/`.

Accepted raw formats: `.mp4`, `.mov`, `.mkv`, `.avi`, `.webm` (video);
`.png`, `.jpg`, `.jpeg`, `.tiff`, `.bmp` (image).

---

## 4. Poster Sidecar (`.thumb`)

For any video, create a `.thumb` file alongside it to control which frame
becomes the poster image (the `.webp` shown before the video loads or plays).

**Format**: plain text, one timestamp per line (seconds). Lines starting
with `#` are comments. Blank lines ignored.

```
# hero-editor.thumb
# Primary poster — used as hero-editor.webp
2.5
# Comparison candidates — saved as hero-editor.2.webp, hero-editor.3.webp
5.0
8.0
```

- **Line 1** → primary poster: `<name>.webp`
- **Line 2+** → numbered variants: `<name>.2.webp`, `<name>.3.webp`, …

Variants let you compare candidate frames side-by-side before deciding.
Delete extras once you pick the best one. Reorder lines if a different
frame should be primary.

**If no `.thumb` file exists**, the script extracts a frame at **2 seconds**
by default.

---

## 5. Conversion Script

```bash
# Convert everything (skips up-to-date files)
cd docs-site && bash scripts/convert-media.sh

# Force re-convert all
bash scripts/convert-media.sh --force

# Preview what would be done
bash scripts/convert-media.sh --dry-run

# Process only one file
bash scripts/convert-media.sh --only "hero-editor"

# Custom settings
bash scripts/convert-media.sh --video-width 540 --crf 30 --webp-quality 90
```

Options:

| Flag | Default | Description |
|------|---------|-------------|
| `--force` | off | Re-convert even if output is newer than input |
| `--dry-run` | off | Print actions without executing |
| `--only <pattern>` | all | Only process files whose basename matches (glob) |
| `--video-width <px>` | 720 | Max video width. Height auto-scales. |
| `--image-width <px>` | 1080 | Max image width. Height auto-scales. |
| `--crf <n>` | 35 | VP9 CRF (lower = bigger + sharper, 0–63) |
| `--webp-quality <n>` | 85 | WebP quality (0–100) |
| `--poster-time <sec>` | 2 | Default poster timestamp when no `.thumb` file |

Requires: `ffmpeg` with `libvpx-vp9` and `libwebp`. Install:
`brew install ffmpeg` (macOS) or `apt install ffmpeg` (Linux).

There is also an npm alias: `cd docs-site && npm run media`.

---

## 6. Capture Guide — Hero

### `hero-editor` — Recording (8–10 s)

The marquee video in the phone mockup at the top of the page.

**Setup**: open ShaderEditor with the default rainbow gradient shader loaded.
Keyboard closed. Preview visible behind code.

**Record**:
1. Hold still for ~1 s showing the editor + live gradient.
2. Tap into the code and change a number (e.g., tweak the `vec3(0, 2, 4)`
   palette offset). The gradient should shift color.
3. Pause typing — gradient settles into new color.
4. Optionally scroll the code a few lines to show line numbers moving.
5. Hold still for ~1 s at the end.

**Poster** (`hero-editor.thumb`): pick a frame where colorful gradient is
visible behind well-lit code. Roughly 2–3 s in.

---

## 7. Capture Guide — Features

Each feature card shows the relevant capability. Aspect ratio is portrait
(9:19) inside a phone mockup. Width displays at 220 px on desktop (440 px
@ 2×), so fine detail doesn't matter — broad strokes do.

### `feat-live-preview` — Recording (6–8 s)

**Show**: type a few characters in the editor → preview updates behind the
code in real-time. FPS counter in toolbar changes.

**Steps**:
1. Start with a working shader and keyboard open.
2. Change a value (e.g., multiply a color by `2.0`).
3. Pause — preview recompiles and updates.
4. Show the FPS counter briefly.

**Poster**: mid-edit frame with preview gradient visible.

---

### `feat-highlighting` — Recording (8–10 s)

**Show**: syntax highlighting + error flow.

**Steps**:
1. Start with a correct shader. Show colorful syntax (keywords blue, types
   yellow/green, numbers tinted).
2. Deliberately introduce a typo (e.g., change `vec4` to `vec5`).
3. Red error underline appears. Snackbar pops up at the bottom.
4. Tap the snackbar → bottom sheet slides up with error list.
5. Hold for a moment showing the error details.

**Poster**: editor with syntax colors visible, no errors. ~1 s in.

---

### `feat-wallpaper` — Recording (8–10 s)

**Show**: setting a shader as live wallpaper and using it.

**Steps**:
1. Start from ShaderEditor with a pretty shader running.
2. Tap menu → "Set as wallpaper" (or show the wallpaper preview screen).
3. Cut to the home screen with the shader wallpaper running.
4. Swipe between home screen pages — wallpaper offset uniform shifts the
   shader.
5. Hold on a visually striking frame.

Alternatively, splice two clips: one of the "set wallpaper" action, one of
the home screen result.

**Poster**: home screen with shader wallpaper.

---

### `feat-camera` — Recording (6–8 s)

**Show**: live camera feed processed by a shader.

**Steps**:
1. Load the Back Camera sample (or write a shader using `cameraBack`).
2. Point the phone at something visually interesting.
3. Move the phone slowly — camera feed updates in the preview.

**Poster**: camera feed visible in shader preview.

---

### `feat-textures` — Screenshot only

**Show**: the texture import / uniform-add dialog with sampler parameters.

**Capture**: open Add Uniform → 2D Textures tab (or Cube Maps). Show at
least 2–3 imported textures with thumbnails visible. If possible, also show
the sampler parameter controls (wrap mode, filter) in a second screenshot
or scroll.

Save as `feat-textures.png`.

---

### `feat-editor` — Recording (10–12 s)

**Show**: editor power features — undo/redo, completions, bracket close,
font.

**Steps**:
1. Open keyboard, type an opening brace `{` → auto-close inserts `}`.
2. Type a GLSL function name partially → completions bar shows suggestions
   → tap one.
3. Tap undo (toolbar) a couple of times → changes revert.
4. Tap redo → changes return.
5. Optionally show the extra keys row (tab, braces, etc.).

**Poster**: keyboard open with completions strip visible above it.

---

### `feat-touch` — Recording (6–8 s)

**Show**: multi-touch input driving shader visuals.

**Steps**:
1. Load the Touch Ripples sample.
2. Touch with 1 finger → ripples appear.
3. Add 2–3 more fingers → more ripple centers.
4. Move fingers around.

**Poster**: 3+ touch points visible with ripple patterns.

---

### `feat-audio` — Recording (6–8 s)

**Show**: audio-reactive shader.

**Steps**:
1. Load a shader that uses `micAmplitude` or `mediaVolume` (write one or
   modify a sample to react to audio).
2. Play music or tap near the mic — shader visuals pulse with the sound.
3. Change volume — show the visual response change.

**Poster**: mid-pulse frame with visible audio reaction.

---

## 8. Capture Guide — Gallery

Gallery cards display inside small phone frames (170 px wide, 340 px @ 2×).
All items play as videos with a "▶ Video" badge. Record everything as a
short clip even for mostly-static screens — a slight scroll or tap adds
life.

### Editing group

#### `screen-editor` — Recording (5–6 s)

Scroll through shader code with the preview updating behind it. Show line
numbers and toolbar.

#### `screen-keyboard` — Recording (6–8 s)

Keyboard open, type GLSL code. Completions bar shows identifier suggestions.
Tap a suggestion to insert it. Show the extra keys row if enabled.

#### `screen-errors` — Recording (8–10 s)

Introduce an error → snackbar appears → tap snackbar → error bottom sheet
opens → tap an error line to jump the cursor to it.

### Library group

#### `screen-shaderlist` — Recording (5–6 s)

Open the side drawer. Show 5+ saved shaders with preview thumbnails.
Scroll the list if more shaders exist. Tap one to load it.

#### `screen-uniforms` — Recording (5–6 s)

Open Add Uniform → Presets tab. Scroll through the uniform list. Tap a
sampler preset to show the parameter config dialog.

#### `screen-textures` — Recording (5–6 s)

Open Add Uniform → 2D Textures tab (or Cube Maps). Show imported textures
with thumbnails. Tap one to preview it.

### Output group

#### `screen-wallpaper` — Recording (6–8 s)

Home screen with a shader live wallpaper running. Swipe between pages.
Optionally open/close the app drawer to show the shader persists.

#### `screen-settings` — Recording (4–5 s)

Open Settings. Slowly scroll through preferences: font, tab width, run
mode, save battery, etc. Brief and steady.

If a static screenshot is preferred, save as `screen-settings.png` instead.
The conversion script will produce only a `.webp` (no `.webm`). Note: the
current Gallery component expects video for all items — you would need to
update `Gallery.astro` to handle image-only gallery items, or just record
a short scroll.

---

## 9. Capture Guide — Examples

Each example in the Examples section can have a preview video shown
alongside the code block. Videos display at 180 px wide (portrait 9:16).

**Every example should have its own dedicated capture** — do not reuse the
same recording for different shaders. The video should show only that
specific shader running.

### Basics

#### `sample-rainbow` — Recording (5–6 s) *(new — currently reuses sample-touch)*

Load the Rainbow Gradient code (first example in Basics tab). Show the
animated gradient cycling through colors. Optionally touch the screen to
show nothing happens (no touch uniforms in this shader).

#### `sample-circles` — Recording (5–6 s) *(new — currently no capture)*

Load the Concentric Circles code. Show expanding rings animating outward
from the center.

#### `sample-texture` — Recording (5–6 s) *(new — currently no capture)*

Load the Texture Sampling code. First add a noise texture via Add Uniform.
Show the texture panning with time, the image sliding across the screen.

### Input & Sensors

#### `sample-touch` — Recording (6–8 s)

Load the Touch Ripples code. Touch with 1 finger → ripples. Add 2–3 more
fingers. Move them around. Remove fingers one by one.

#### `sample-swirl` — Recording (6–8 s) *(new — currently no capture)*

Load the Swirl Distortion code. First add a noise texture. Touch the
screen at different points → texture warps into vortex shapes. Move finger
slowly to drag the distortion.

#### `sample-gravity` — Recording (5–6 s)

Load the Gravity Tilt code. Slowly tilt the device left → right → forward
→ back. Colors shift with each tilt.

#### `sample-orientation` — Recording (5–6 s) *(new — currently no capture)*

Load the Orientation Grid code. Rotate the device along its Z-axis (roll)
— the grid pattern rotates to follow. Tilt in other axes for additional
color shifts.

### Camera & System

#### `sample-camera` — Recording (5–6 s)

Load the Back Camera code (grant camera permission if prompted). Point the
phone at something with color/texture. Move the phone slowly to show the
live feed updating.

#### `sample-battery` — Recording (4–5 s) or Screenshot

Load the Battery Gauge code. Show the horizontal bar at the current battery
level. If possible, plug/unplug the charger to show the level changing (or
use developer options to simulate). A screenshot (`sample-battery.png`) is
acceptable since this shader is nearly static.

#### `sample-skyline` — Recording (6–8 s) *(new)*

Load the Signal Skyline code. Play music or tap near the mic so towers and
scanlines react. Trigger or dismiss a notification to show the pulse ring.
Swipe between launcher pages to show the wallpaper offset panning the city.

#### `sample-camera-portal` — Recording (6–8 s) *(new)*

Load Back Camera Portal. Point the rear camera at a colorful scene, then touch
and drag across the preview so portal center follows your finger while the
camera feed bends through it.

#### `sample-front-ghost` — Recording (6–8 s) *(new)*

Load Front Camera Ghost. Switch to the front camera, move your face in and out,
and if the device has a proximity sensor bring a hand near the top bezel to
change the bloom. Capture both dark and bright ambient lighting if possible.

### Backbuffer

#### `sample-gol` — Recording (6–8 s)

Load the Game of Life code. Touch to seed cells → watch them evolve.
Touch again in a different spot to spark a new colony. Let the simulation
run for a few seconds showing patterns stabilize/oscillate.

#### `sample-cloudy` — Recording (6–8 s) *(new — currently no capture)*

Load the Cloudy Conway code. Touch to seed → cells evolve with colorful
afterglow trails. Show the two-tone color trails fading over time. Touch
in multiple spots.

#### `sample-electric` — Recording (6–8 s)

Load the Electric Fade code. The noise-seeded backbuffer should ignite
immediately with electric blue/cyan patterns. Show the automaton spreading
and trails forming. Optionally touch to seed new areas.

#### `sample-fluid` — Recording (6–8 s) *(new)*

Load Feedback Fluid. Drag one finger slowly, then add a second or third finger
for overlapping dye plumes. Tilt the phone so gravity / linear acceleration
nudge the fluid field while old color keeps feeding back.

### Advanced

#### `sample-gles3` — Recording (5–6 s) *(new — currently reuses sample-touch)*

Load the GLES 3.0 Syntax code. Same behavior as Touch Ripples but running
under GLES 3.0. Touch with multiple fingers to show ripples. The point is
to show it works identically.

#### `sample-sensorium` — Recording (6–8 s) *(new)*

Load Sensorium. Slowly rotate and tilt the device so the shrine and sky react
to orientation data. If possible, change room lighting or bring a hand near
proximity sensor to show the core and fog adapting.

---

## 10. Capture Guide — OG Image

### `og-image.png` — Static image (1200×630 px, landscape)

For social sharing previews (Twitter/Open Graph).

**Compose** (in an image editor or via screenshot + crop):
- Left third: ShaderEditor app icon.
- Center: "ShaderEditor" in the display font.
- Right third: a shader preview (screenshot of a colorful running shader
  cropped to a phone silhouette or just the raw output).
- Background: dark (#0c0e14 or similar to match the site).

Save as `og/og-image.png`. The script converts it to WebP in
`public/media/`.

---

## 11. Summary of All Files

Legend: 🎬 = recording, 📷 = screenshot, 🆕 = not yet captured.

### Hero
| Raw file | Type | Duration |
|----------|------|----------|
| `hero/hero-editor.mp4` | 🎬 | 8–10 s |

### Features
| Raw file | Type | Duration |
|----------|------|----------|
| `features/feat-live-preview.mp4` | 🎬 | 6–8 s |
| `features/feat-highlighting.mp4` | 🎬 | 8–10 s |
| `features/feat-wallpaper.mp4` | 🎬 | 8–10 s |
| `features/feat-camera.mp4` | 🎬 | 6–8 s |
| `features/feat-textures.png` | 📷 | — |
| `features/feat-editor.mp4` | 🎬 | 10–12 s |
| `features/feat-touch.mp4` | 🎬 | 6–8 s |
| `features/feat-audio.mp4` | 🎬 | 6–8 s |

### Gallery
| Raw file | Type | Duration |
|----------|------|----------|
| `gallery/screen-editor.mp4` | 🎬 | 5–6 s |
| `gallery/screen-keyboard.mp4` | 🎬 | 6–8 s |
| `gallery/screen-errors.mp4` | 🎬 | 8–10 s |
| `gallery/screen-shaderlist.mp4` | 🎬 | 5–6 s |
| `gallery/screen-uniforms.mp4` | 🎬 | 5–6 s |
| `gallery/screen-textures.mp4` | 🎬 | 5–6 s |
| `gallery/screen-wallpaper.mp4` | 🎬 | 6–8 s |
| `gallery/screen-settings.mp4` | 🎬 | 4–5 s |

### Examples
| Raw file | Type | Duration | Status |
|----------|------|----------|--------|
| `examples/sample-rainbow.mp4` | 🎬 | 5–6 s | 🆕 |
| `examples/sample-circles.mp4` | 🎬 | 5–6 s | 🆕 |
| `examples/sample-texture.mp4` | 🎬 | 5–6 s | 🆕 |
| `examples/sample-touch.mp4` | 🎬 | 6–8 s | exists |
| `examples/sample-swirl.mp4` | 🎬 | 6–8 s | 🆕 |
| `examples/sample-gravity.mp4` | 🎬 | 5–6 s | exists |
| `examples/sample-orientation.mp4` | 🎬 | 5–6 s | 🆕 |
| `examples/sample-camera.mp4` | 🎬 | 5–6 s | exists |
| `examples/sample-battery.mp4` | 🎬 | 4–5 s | exists |
| `examples/sample-skyline.mp4` | 🎬 | 6–8 s | 🆕 |
| `examples/sample-camera-portal.mp4` | 🎬 | 6–8 s | 🆕 |
| `examples/sample-front-ghost.mp4` | 🎬 | 6–8 s | 🆕 |
| `examples/sample-gol.mp4` | 🎬 | 6–8 s | exists |
| `examples/sample-cloudy.mp4` | 🎬 | 6–8 s | 🆕 |
| `examples/sample-electric.mp4` | 🎬 | 6–8 s | exists |
| `examples/sample-fluid.mp4` | 🎬 | 6–8 s | 🆕 |
| `examples/sample-gles3.mp4` | 🎬 | 5–6 s | 🆕 |
| `examples/sample-sensorium.mp4` | 🎬 | 6–8 s | 🆕 |

### Other
| Raw file | Type | Notes |
|----------|------|-------|
| `og/og-image.png` | 📷 | 1200×630 px landscape |

**Total**: 1 hero + 8 features + 8 gallery + 18 examples + 1 OG = **36 media items**.

---

## 12. After Capturing

1. Place raw files in `docs-site/raw/<section>/`.
2. Optionally create `.thumb` sidecars for poster frame control.
3. Run `cd docs-site && bash scripts/convert-media.sh`.
4. Review output in `public/media/`. Delete any `.2.webp` / `.3.webp`
   comparison variants you don't need.
5. For new example captures (🆕 items), add `video:` and `poster:` fields to
   the matching walkthrough markdown frontmatter under
   `docs-site/src/content/walkthroughs/**`.
6. Rebuild the site: `cd docs-site && npm run build`.
7. Spot-check in browser: `npm run preview`.

---

## 13. Quality Checklist

Before committing new media:

- [ ] All videos loop cleanly (no jarring jump at the end).
- [ ] No personal data visible (notifications, account names, etc.).
- [ ] Dark theme used throughout.
- [ ] Poster frame is visually appealing (not a blank/loading frame).
- [ ] File sizes reasonable: videos < 2 MB each, images < 200 KB.
      If oversized, increase CRF (`--crf 40`) or reduce width
      (`--video-width 540`).
- [ ] No audio track in videos (the script strips audio, but verify with
      `ffprobe`).
- [ ] All 36 items have corresponding output files in `public/media/`.
