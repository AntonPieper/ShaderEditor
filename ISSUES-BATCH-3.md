### Batch 3: fork fixes, PR #328, the translation, and the #122 bug (Android 16; KB1 on Android 8 too if an image exists)

**KB1, #90: gravity, linear and orientation without a gravity sensor.** Upstream master against the fork.
1. First run `adb shell dumpsys sensorservice | grep -i -E "gravity|linear|rotation|magnetic|accel"` and write down which sensors exist. The reporter's phone has an accelerometer and a magnetometer, but no gravity or linear-acceleration sensor.
2. Make this shader:
```glsl
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 resolution;
uniform vec3 gravity;
uniform vec3 linear;
uniform vec3 orientation;
void main(void) {
	float x = gl_FragCoord.x / resolution.x;
	vec3 c = x < 0.33 ? abs(gravity) / 9.81
		: x < 0.66 ? clamp(abs(linear), 0.0, 1.0)
		: abs(orientation) / 3.14159;
	gl_FragColor = vec4(c, 1.0);
}
```
3. Turn the device in Extended controls → Virtual sensors.
- Fixed (fork): all three thirds change.
- Broken (master, on a device without TYPE_GRAVITY): the right third (orientation) stays black. Without TYPE_LINEAR_ACCELERATION too, the middle third also stays black.
- If the emulator has a gravity sensor, this cannot be reproduced on it. Say so; the fork's JVM test `SensorSourcesTest` covers the case.

**KB2, #254: the fps label after an error.** Upstream master against the fork.
1. Make a shader that compiles; the toolbar shows fps.
2. Change `gl_FragColor` to `gl_FragColr`.
3. Rotate the device.
- Fork: the fps value disappears as soon as the error shows, and stays away after rotation.
- Master: the last fps value stays frozen until rotation, then disappears.

**KB3, #276 / #137: the fraction of `date.w`.** Upstream master against the fork (or the PR #328 branch).
```glsl
precision highp float;
uniform vec4 date;
void main(void) {
	gl_FragColor = vec4(vec3(fract(date.w)), 1.0);
}
```
- Fixed: a grey that changes in every screenshot.
- Broken (master): always black.

**KB4, #48: the sample list.** Upstream master against a fork RELEASE build. Run `./gradlew assembleRelease` with a throwaway key in the `ANDROID_KEY_*` env vars. The debug build shows examples as a library folder instead.
1. Menu → Load sample.
- Fork: sections "Start here / Device inputs / Techniques / Wallpapers", each sample with a one-line task, and Sound, Notifications, Mirror and others present.
- Master: a flat list of 12.
- This is only a look, with a screenshot of each.

**K14, #247: the Chinese translation.** OLD 2.36.1 against master.
1. Run `adb shell cmd locale set-app-locales <pkg> --locales zh-CN`, then start the app and open the menu and Settings.
2. Repeat with `zh-TW`.
- Fixed: the menus and settings are in Chinese.
- Broken (2.36.1): English.

**KD1, #122: confirm the duplicated precision block (a D bug, not fixed anywhere).** Upstream master, and the fork with both editors.
1. In a new shader, select all, delete, and paste:
```glsl
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 resolution;
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	fragColor = vec4(fragCoord / resolution, 0.0, 1.0);
}
void main() {
	vec4 fragment_color;
	mainImage(fragment_color, gl_FragCoord.xy);
	gl_FragColor = fragment_color;
}
```
2. Select from `void main() {` to the end and Cut.
- Bug confirmed if: the text is rewritten at once, with a second `#ifdef GL_FRAGMENT_PRECISION_HIGH` block at the top and a new `main` at the end. Pasting the cut `main` back then gives "redefinition" errors.
- Report it with screenshots, for a small fix PR.

