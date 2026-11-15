# Android 6 (API 23) check: ShaderToy mouse.w and date

These two branches of github.com/AntonPieper/ShaderEditor (remote `origin`) are independent and both are cut from markusfisch/ShaderEditor `master` (acacf4d4):

- `upstream-fix-shadertoy-mouse` (06621195)
- `upstream-fix-shadertoy-date` (ec9da885)

Save screenshots to `.foreman/show/upstream-batch-4/shadertoy-mouse/android6/` and `.foreman/show/upstream-batch-4/shadertoy-date/android6/`.

## Build

```sh
git fetch upstream && git fetch origin
for ref in upstream/master origin/upstream-fix-shadertoy-mouse origin/upstream-fix-shadertoy-date; do
  git checkout --detach $ref && ./gradlew :app:assembleDebug &&
  cp app/build/outputs/apk/debug/app-debug.apk "$(basename $ref).apk"
done
```

- The package is `de.markusfisch.android.shadereditor.debug`, which is the same id as the fork's own debug build. Uninstall that build first, and reinstall it at the end if it was there before.
- "Fresh install" means `adb uninstall de.markusfisch.android.shadereditor.debug; adb install <apk>`.
- Start the app with `adb shell am start -n de.markusfisch.android.shadereditor.debug/de.markusfisch.android.shadereditor.activity.SplashActivity`.

## Mouse (master.apk, then upstream-fix-shadertoy-mouse.apk)

1. Fresh install, then start the app.
2. Replace the editor text with the shader below. It declares `mouse` as a vec4 itself, so no ShaderToy conversion is needed.
   ```glsl
   precision mediump float;
   uniform vec2 resolution;
   uniform vec4 mouse;
   uniform sampler2D backbuffer;
   void main() {
   	vec2 uv = gl_FragCoord.xy / resolution;
   	float held = mouse.z > 0.0 ? 1.0 : 0.0;
   	float click = mouse.w > 0.0 ? 1.0 : 0.0;
   	if (uv.y < 0.5) {
   		float count = texture2D(backbuffer, vec2(0.5, 0.25)).b;
   		gl_FragColor = vec4(0.0, 0.0, count + 0.1 * click, 1.0);
   	} else if (uv.x < 0.5) {
   		gl_FragColor = vec4(held, 0.0, 0.0, 1.0);
   	} else {
   		gl_FragColor = vec4(0.0, click, 0.0, 1.0);
   	}
   }
   ```
   - The top left turns red while `mouse.z > 0`, meaning a finger is down.
   - The top right turns green while `mouse.w > 0`, meaning a click in this frame.
   - The bottom half counts the frames with `w > 0`: it gets 0.1 brighter blue per frame (8-bit: about 26 per frame) and is full blue after 10 frames.
3. Tap the eye icon in the toolbar to hide the code, so touches reach the preview. Everything is black.
4. Hold a finger in the lower half of the preview and take screenshots while it is held, then release:
   ```sh
   adb shell input motionevent DOWN 540 1400   # adjust to the screen size
   # screenshots after about 0.7 s, 1.4 s and 2.1 s
   adb shell input motionevent MOVE 600 1450
   # one more screenshot
   adb shell input motionevent UP 600 1450
   # screenshot after the release
   ```
   If `input motionevent` is missing on that image, hold a finger with the emulator's mouse instead.
5. Repeat step 4 once.
6. Tap five times quickly: `adb shell "input motionevent DOWN 540 1400; input motionevent UP 540 1400"`, or `adb shell input tap 540 1400`, about 0.6 s apart. Take one screenshot afterwards.

**Expected on master:**
- While held: red and green in every screenshot.
- The bottom is full blue after the first hold.
- After the release: red and green go off.

**Expected with the fix:**
- While held: red, but green in **no** screenshot.
- After the first hold the bottom is dark blue, about 26 (one frame); after the second hold, about 52.
- After the five taps, about 181: exactly one frame per press.
- After the release: everything off except the blue count.

Read the pixel values with an image tool. On the Android 16 emulator the values were 26, 52 and 181.

## Date (master.apk, then upstream-fix-shadertoy-date.apk)

1. Fresh install, then start the app.
2. Replace the editor text with:
   ```glsl
   precision mediump float;
   uniform vec4 date;
   void main() {
   	gl_FragColor = vec4(vec3(fract(date.w)), 1.0);
   }
   ```
3. Hide the code, then take five screenshots about 0.4 s apart.

**Expected:**
- Master: black in all five (whole seconds, so the fraction is 0).
- Fix: a grey that differs between the screenshots (the fraction of the second).
- On the fix, `floor(date.w)` still equals the seconds of the day: check with `gl_FragColor = vec4(vec3(mod(floor(date.w), 2.0)), 1.0);`, which must blink once a second on both builds.
