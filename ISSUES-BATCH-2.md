### Batch 2: running shaders, wallpaper, inputs (Android 16, plus Android 6 for K3 and K6)

**K3, #226 / #237 / #257 / #211: a shader that kills the app on start.** OLD 2.35.2 against master.
1. Settings → Run mode → "Run manually", so typing does not run it.
2. Make and save this shader. If the emulator freezes, lower 20000:
```glsl
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 resolution;
void main(void) {
	float v = 0.0;
	for (int i = 0; i < 20000; ++i) {
		v += sin(float(i) * gl_FragCoord.x / resolution.x);
	}
	gl_FragColor = vec4(vec3(fract(v)), 1.0);
}
```
3. Set Run mode back to "Run in background" (the default) and select that shader in the drawer.
4. Before the toolbar shows an fps value, run `adb shell am force-stop <pkg>`, then start the app again.
- Fixed: on the next start a toast says the shader was disabled after a crash, and its text starts with `// Shader Editor disabled this shader because it caused a crash while loading.`; the app can be used.
- Broken (2.35.2): the same shader loads and hangs again.
5. False alarm (master only, our #309): select a normal shader, press Home, run `adb shell am kill <pkg>`, and start the app again. It must NOT be disabled.

**K6, #37: the `runtime` uniform of a wallpaper.** OLD 2.36.2 against master (or 2.36.3). Android 16 and Android 6.
1. Make this shader: a white bar that grows across the screen in 20 s.
```glsl
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 resolution;
uniform float runtime;
void main(void) {
	float x = gl_FragCoord.x / resolution.x;
	gl_FragColor = vec4(vec3(step(x, fract(runtime / 20.0))), 1.0);
}
```
2. Menu → Set as wallpaper.
3. Go Home and note how long the bar is. Open the Settings app for 10 s, then go back Home. Do this three times.
- Fixed: the bar goes on from where it was every time, with no jump. As a contrast, change `runtime` to `time`: then the bar jumps.
- Broken (2.36.2): `runtime` does not exist, so the bar stays empty.

**K9, #253: a camera wallpaper.** OLD 2.36.3 against master. Android 16, and Android 11 if an image exists. The AVD needs a back camera (VirtualScene or Emulated).
1. Menu → Load sample → "Back camera" (the camera sample). Grant the camera permission.
2. Menu → Set as wallpaper, then go Home.
3. Watch `adb logcat -b crash` meanwhile.
- Fixed: there is no crash, and the wallpaper stays this app's wallpaper. Note whether it shows the camera image or black; either way, no crash. On master the app asks for the permission once and says when the camera is missing.
- Broken (2.36.3): the wallpaper service crashes (crash log), and the system falls back to the default wallpaper.

**K10, #88 / #214: `micAmplitude`.** OLD 2.35.0 against master.
1. Run `adb shell pm grant <pkg> android.permission.RECORD_AUDIO`.
2. In the emulator's Extended controls → Microphone, turn on "Virtual microphone uses host audio input".
3. Make this shader:
```glsl
precision mediump float;
uniform float micAmplitude;
void main(void) {
	gl_FragColor = vec4(vec3(micAmplitude * 4.0), 1.0);
}
```
4. Speak or clap near the Mac.
- Fixed: the screen flashes brighter with the sound, and Add uniform lists "micAmplitude".
- Broken (2.35.0): the screen stays black, and the uniform is not in the list.

**K11, #138: the RGBA noise texture.** OLD 2.35.3 against master.
1. Menu → Add uniform/texture → swipe to the sampler2D page.
- Fixed: `rgba_noise` is listed beside `noise`. Tap it and insert it; this shader then shows coloured noise:
```glsl
precision mediump float;
uniform vec2 resolution;
uniform sampler2D rgba_noise;
void main(void) {
	gl_FragColor = texture2D(rgba_noise, gl_FragCoord.xy / resolution);
}
```
- Broken (2.35.3): only `noise` is listed.

**K12, #101: `notificationCount`.** OLD 2.33.2 against master.
1. Grant the listener: `adb shell cmd notification allow_listener <pkg>/de.markusfisch.android.shadereditor.service.NotificationService`.
2. Make this shader:
```glsl
precision mediump float;
uniform int notificationCount;
void main(void) {
	gl_FragColor = vec4(vec3(float(notificationCount) / 4.0), 1.0);
}
```
3. Post notifications one by one: `adb shell cmd notification post -t T tag1 one`, then `tag2 two`, then `tag3 three`.
- Fixed: the screen gets brighter with each notification.
- Broken (2.33.2): the screen stays black.

**K13, #105: a texture that cannot load (shows the regression on master).** OLD 2.36.3 against master.
1. Add uniform → sampler2D → + → import any photo, named `big`.
2. Settings → Export database → save to Downloads, then `adb pull` the file.
3. On the Mac, run `sqlite3 <file> "UPDATE textures SET matrix = X'00' WHERE name = 'big';"`.
4. `adb push` the file back, then Settings → Import database.
5. Add uniform → sampler2D → tap `big`.
- 2.36.3 (expected): the toast "Texture was too big to handle", and `big` is gone from the list.
- Master (expected regression): the same toast, the screen closes, `big` stays in the list, and there is no way to delete it.
- Report both. This check decides whether we tell the maintainer before his next release.

