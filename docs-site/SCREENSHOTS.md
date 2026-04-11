# Media Guide for ShaderEditor Docs Site

All media files go in `docs-site/public/media/`. Use **WebP** for images
and **WebM** for videos. Currently, PNG screenshots are used as placeholders.

## Recording Tips

- Use **Android screen recorder** (built-in or `scrcpy`) at 720p or 1080p
- Convert to WebM: `ffmpeg -i input.mp4 -c:v libvpx-vp9 -crf 35 -b:v 0 -an output.webm`
- Convert screenshot to WebP: `cwebp -q 85 input.png -o output.webp`
- Keep videos 5–15 seconds, looping seamlessly if possible
- Record on a dark-themed device for consistency with the site

---

## Hero Section

| File | What to capture |
|---|---|
| `hero-editor.webm` | **Video (5–10s)**: Open editor with the Default shader. Type a few characters so the shader recompiles and the gradient shifts. Show the live preview updating. |
| `hero-editor.webp` | **Poster**: Single frame from the video above — editor with colorful gradient visible behind code. |

---

## Feature Section

Each feature needs a 16:10 aspect ratio video or image showing that specific feature in use.

| File | What to capture |
|---|---|
| `feat-live-preview.webm` | Edit code → watch background gradient update in real-time. Show FPS counter changing. ~8s. |
| `feat-live-preview.webp` | Poster frame from above. |
| `feat-highlighting.webm` | Type invalid GLSL → red error markers appear → tap snackbar → bottom sheet opens with error list. ~10s. |
| `feat-highlighting.webp` | Poster: editor with syntax colors visible (keywords blue, types yellow, numbers green). |
| `feat-wallpaper.webm` | Set shader as wallpaper → exit to home screen → swipe between pages → wallpaper scrolls. ~10s. |
| `feat-wallpaper.webp` | Poster: home screen with a beautiful shader wallpaper running. |
| `feat-camera.webm` | Load Back Camera sample → camera feed appears with shader effect applied. Move the phone. ~8s. |
| `feat-camera.webp` | Poster: camera feed visible in the shader. |
| `feat-textures.webp` | **Image only**: Screenshot of the texture import dialog / cube map composer with sampler parameters visible. |
| `feat-editor.webm` | Show: undo/redo (toolbar buttons), completions bar above keyboard, bracket auto-close, font change. ~12s. |
| `feat-editor.webp` | Poster: keyboard open with completions strip visible. |
| `feat-touch.webm` | Load Touch sample → touch with multiple fingers → white dots appear at touch points. ~8s. |
| `feat-touch.webp` | Poster: 3+ touch points visible on screen. |
| `feat-audio.webm` | Show a shader reacting to sound (play music or tap near mic). Media volume bar or mic amplitude visualizer. ~8s. |
| `feat-audio.webp` | Poster: audio-reactive shader frame. |

---

## Gallery Section

Screenshots and videos of the app's various screens. Phone-portrait aspect ratio (9:14).

| File | What to capture |
|---|---|
| `screen-editor.webm` | Main editor screen: scroll through code, preview updating behind it. ~6s. |
| `screen-editor.webp` | Poster: editor with code and preview visible. |
| `screen-keyboard.webm` | Keyboard open: type code, completions bar shows suggestions, tap one. ~8s. |
| `screen-keyboard.webp` | Poster: keyboard + completions visible. |
| `screen-errors.webm` | Introduce error → snackbar appears → tap → bottom sheet → tap error to jump to line. ~10s. |
| `screen-errors.webp` | Poster: error bottom sheet open. |
| `screen-shaderlist.webp` | **Image**: Drawer open showing 5+ saved shaders with their preview thumbnails. |
| `screen-uniforms.webp` | **Image**: Add Uniform screen, Presets tab showing the uniform list. |
| `screen-textures.webp` | **Image**: Add Uniform screen, 2D Textures or Cube Maps tab with imported textures. |
| `screen-wallpaper.webm` | Home screen with shader wallpaper running. Swipe between pages, open/close app drawer. ~8s. |
| `screen-wallpaper.webp` | Poster: home screen with shader wallpaper. |
| `screen-settings.webp` | **Image**: Settings screen showing editor preferences (font, tab width, run mode, etc.). |

---

## Examples / Samples Section

Each sample gets a portrait (9:16) video showing the shader running.

| File | What to capture |
|---|---|
| `sample-touch.webm` | Default shader: touch with 1–3 fingers, ripples appear. ~6s loop. |
| `sample-touch.webp` | Poster: frame with touch ripples visible. |
| `sample-gol.webm` | Game of Life: random pattern evolving, touch to spark new cells. ~8s loop. |
| `sample-gol.webp` | Poster: active GoL pattern. |
| `sample-electric.webm` | Electric Fade: automaton with cyan afterglow trails spreading. ~8s loop. |
| `sample-electric.webp` | Poster: trails visible. |
| `sample-gravity.webm` | Gravity sensor: tilt device → colors shift. ~6s. |
| `sample-gravity.webp` | Poster: mid-tilt colors. |
| `sample-camera.webm` | Back camera feed with shader. Move phone around. ~6s. |
| `sample-camera.webp` | Poster: camera feed frame. |
| `sample-battery.webm` | Battery gauge bar. If possible, show level changing (use developer options). ~4s. |
| `sample-battery.webp` | Poster: white bar at current battery %. |

---

## Optional: OG Image

Create `docs-site/public/media/og-image.png` (1200×630 px) for social sharing.
Show the app icon + "ShaderEditor" text + a shader preview.

---

## Workflow

1. Record on device using screen recorder or `scrcpy --record=file.mp4`
2. Convert: `ffmpeg -i file.mp4 -c:v libvpx-vp9 -crf 35 -b:v 0 -an -t 10 output.webm`
3. Extract poster: `ffmpeg -i file.mp4 -ss 2 -frames:v 1 output.webp`
4. Place in `docs-site/public/media/`
5. Rebuild: `cd docs-site && npm run build`
