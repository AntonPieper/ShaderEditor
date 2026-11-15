### Batch 1: the editor (Android 16, plus Android 6 for K1 and K5)

**K1, #54: Tab on a hardware keyboard.** OLD 2.27.0 against master.
1. Open any shader and put the caret at the start of a line inside `main`.
2. Press Tab on a hardware keyboard: `adb shell input keyevent KEYCODE_TAB`, or the host keyboard with the emulator window focused.
- Fixed: a tab is inserted at the caret, and the keyboard focus stays in the editor.
- Broken (2.27.0): nothing is inserted, and the focus jumps to another control (the toolbar or the quality spinner).
- Also on master: Settings → turn off "Use Tab for indent" (or its translation), press Tab again; now the focus should move. This shows the setting is what changed.

**K2, #131: opening a shader file from another app.** OLD 2.36.3 against master.
1. `adb push` a file to `/sdcard/Download/opened.txt` with this content:
```glsl
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
// OPENED FROM A FILE
void main(void) {
	gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
}
```
2. Cold start: run `adb shell am force-stop <pkg>`. In the Files app, open Downloads, long-press `opened.txt`, then Share → Shader Editor. (The share path is ACTION_SEND with EXTRA_STREAM.)
3. Warm start: with the app open on another shader, share the file again.
4. Also copy it to `opened.glsl`, tap it in Files and see whether Shader Editor is offered under "Open with" (ACTION_VIEW). Write down whether it is offered at all.
- Fixed: both times the editor shows the text with `// OPENED FROM A FILE` and a red preview, and the title is the file name.
- Broken (2.36.3): the cold start crashes, or shows the last shader; a shared file opens the app without its text.

**K4, #45: keywords inside comments.** OLD 2.31.1 against master. Type these lines below `main`:
```glsl
// uniform float vec3 void return if else
/* for while float sampler2D */
```
- Fixed: both lines have the comment colour all the way through.
- Broken (2.31.1): `uniform`, `float`, `vec3` and the other keywords inside them have keyword colours.

**K5, #75: colours after cut and paste.** OLD 2.36.3 against master. Android 16 and Android 6.
1. Open the default new shader. Select the whole body of `main`, from the line after `{` to the line before `}`.
2. Cut, put the caret at the end of the text, and paste at once, within the update delay (well under a second).
3. Then: Select all → Cut → Paste at once.
- Fixed: the pasted code has its colours, also after waiting 2 s.
- Broken (2.36.3): the pasted text stays white or only partly coloured until it is edited again.

**K7, #220: where the error message appears in landscape.** OLD 2.35.3 against master.
1. Rotate to landscape and open the keyboard in the editor.
2. In `main`, type `float x = ;` and wait for the compile.
- Fixed: the error message appears at the top, over the toolbar, and the line you are typing stays visible.
- Broken (2.35.3): the message appears at the bottom, over the code or just above the keyboard.

**K8, #39: pasting a ShaderToy shader.** OLD 2.36.3 against master. In a new shader, select all, delete, and paste:
```glsl
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 uv = fragCoord / iResolution.xy;
	float pressed = iMouse.z > 0.0 ? 0.3 : 0.0;
	fragColor = vec4(uv, 0.5 + 0.5 * sin(iTime + float(iFrame) * 0.001), 1.0) + pressed;
}
```
- Fixed: the text is converted (a precision block and uniforms at the top, `main` at the end), it compiles, the blue changes over time, and holding a finger down brightens the picture.
- Broken (2.36.3): it is converted, but `iTime` and `iFrame` stay unknown, so you get a compile error.

