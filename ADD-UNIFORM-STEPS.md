# Android 6 (API 23) check: Add uniform removes only the same name

Branch `upstream-fix-add-uniform-whole-name` (98f6cd6f) on github.com/AntonPieper/ShaderEditor (remote `origin`), cut from markusfisch/ShaderEditor `master` (acacf4d4). Save screenshots to `.foreman/show/upstream-batch-4/add-uniform-whole-name/android6/`.

## Build

```sh
git fetch upstream && git fetch origin
git checkout --detach upstream/master && ./gradlew :app:assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk master.apk
git checkout --detach origin/upstream-fix-add-uniform-whole-name && ./gradlew :app:assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk fix.apk
```

- The package is `de.markusfisch.android.shadereditor.debug`, which is the same id as the fork's own debug build. Uninstall that build first, and reinstall it afterwards if it was there before.
- Do a fresh install of `master.apk` (`adb uninstall de.markusfisch.android.shadereditor.debug; adb install master.apk`).
- Start the app with `adb shell am start -n de.markusfisch.android.shadereditor.debug/de.markusfisch.android.shadereditor.activity.SplashActivity`.
- Then repeat everything with `fix.apk`.

## Steps

For each case:

1. Replace the editor text with the shader of the case. Each shader ends its uniform block with a **blank line**, which is what the bug needs.
2. Take a screenshot.
3. Menu (⋮) → Add uniform/texture.
4. Pick the uniform listed for the case. Use the search icon and type the name, then tap the row with that exact name.
5. Take a screenshot of the editor and note the uniform lines.

**Case 1: `time`** (row "time (float)")
```glsl
precision mediump float;

uniform vec2 resolution;
uniform float timeScale;

void main() {
	gl_FragColor = vec4(gl_FragCoord.xy / resolution, timeScale, 1.0);
}
```
- Master: `uniform float timeScale;` is gone and `uniform float time;` takes its place; the shader fails to compile (error button).
- Fix: `timeScale` stays and `time` is added below it; the shader runs.

**Case 2: `resolution`** (row "resolution (vec2)")
```glsl
precision mediump float;

uniform vec2 resolutionScale;

void main() {
	gl_FragColor = vec4(fract(gl_FragCoord.xy / resolutionScale), 0.0, 1.0);
}
```
- Master: `resolutionScale` is gone and `uniform vec2 resolution;` appears at the very top.
- Fix: `resolutionScale` stays and `resolution` is added below it.

**Case 3: `backbuffer`** (row "backbuffer (sampler2D)", then "Insert Code" on the texture parameters screen)
```glsl
precision mediump float;

uniform vec2 resolution;
uniform sampler2D backbufferOld;/// min:n;mag:n;s:c;t:c;

void main() {
	gl_FragColor = texture2D(backbufferOld, gl_FragCoord.xy / resolution);
}
```
- Master: the `backbufferOld` line is gone.
- Fix: it stays, and `uniform sampler2D backbuffer;` is added below it.

**Case 4: same name, no regression** (fix only; row "time (float)")
```glsl
precision mediump float;

uniform vec2 resolution;
uniform float time;

void main() {
	gl_FragColor = vec4(gl_FragCoord.xy / resolution, fract(time), 1.0);
}
```
- Fix: still exactly one `uniform float time;` line, because the existing one is replaced.

**Control: no blank line** (both builds; row "time (float)")
```glsl
precision mediump float;

uniform vec2 resolution;
uniform float timeScale;
uniform float battery;

void main() {
	gl_FragColor = vec4(gl_FragCoord.xy / resolution, timeScale * battery, 1.0);
}
```
- Both builds: nothing is removed and `time` is added after `battery`.
