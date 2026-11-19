# Macros

Shaders that show two things the editor learns from the last build of a shader:

- **Here**: what a macro use becomes where it stands, in Look up.
- **Grey lines**: the lines no program compiles, drawn in the grey of comments.

**How to look.**

1. Open a file and let it run once. In manual run mode, tap ▶.
2. Long-press a macro's name where it is used. The name is selected and a bar appears over it.
3. Tap **Look up**. On Android 6 it is under the bar's ⋮; on newer Android it is in the bar, or under its ⋮.
4. To look up another name, close the sheet and tap an empty place below the code first. A long-press while a word is still selected can keep the old word.

The sheet shows the definition as before. Under it, **Here** shows the code this use becomes. Long code is laid out to fit the sheet the way a code formatter would: a call's arguments one per line, while an inner call that fits stays whole on its line.

Both come from the last build:

- After an edit, Here is gone until the next build has run.
- Grey stays through an edit that cannot change a condition, such as typing in a grey line or adding a line inside a grey block.
- After an edit of a line with `#`, a backslash, `/*` or `*/`, the grey above the edit stays, and the grey below it is worked out again right after the key, without a run.
- An edit of an `#include` or `#pragma` line removes all grey until the next run.

Where the app cannot tell, it shows nothing rather than a guess. Every Here below was checked with Mesa's compiler: put in place of the use, it gives the same compiled program. Line numbers are the editor's.

## Parameters.frag: several parameters, one macro inside another

Line 19 uses `RING` three times, each with other arguments. Look up each:

- first `RING`: Here: `smoothstep(0.02, 0.0, abs(length(uv) - (0.3)))`
- second `RING`: Here: `smoothstep(0.03, 0.0, abs(length(uv) - (0.6)))`
- third `RING`, at the end of the line (scroll the code sideways): Here: `smoothstep(0.04 * pulse, 0.0, abs(length(uv) - (0.9)))`

`RING` calls `S`, which is expanded too.

Line 20, `MIX3`, whose arguments go on into line 21 and whose definition takes two lines: Here: `mix(mix(vec3(0.1, 0.2, 0.5), vec3(0.9, 0.4, 0.2), smoothstep(0.0, 0.5, length(uv))), vec3(1.0, 0.9, 0.6), smoothstep(0.5, 1.0, length(uv)))`

## Nested.frag: three deep, a macro built from others, a macro in another's arguments

- Line 19, `SPIN` (it uses `ROT` and `TURNS`, `TURNS` uses `TAU`, `TAU` uses `PI`): Here: `mat2(cos(((time * 0.05) * (2.0 * 3.14159265))), sin(((time * 0.05) * (2.0 * 3.14159265))), -sin(((time * 0.05) * (2.0 * 3.14159265))), cos(((time * 0.05) * (2.0 * 3.14159265))))`
- Line 21, `ROT`: Here: `mat2(cos(((0.125) * (2.0 * 3.14159265))), sin(((0.125) * (2.0 * 3.14159265))), -sin(((0.125) * (2.0 * 3.14159265))), cos(((0.125) * (2.0 * 3.14159265))))`
- Line 21, `TURNS`: **no Here.** It stands inside the parentheses of `ROT`, so what the compiler gets of it is part of `ROT`'s code, which `ROT`'s row shows.
- Line 23, `TAU`, built from `PI`: Here: `(2.0 * 3.14159265)`

## Lines.frag: a definition over three lines, macros that are statements

- Line 18, `CHECKER`, whose definition is joined by backslashes (ES 3.00): Here: `mod(floor((uv).x * (8.0)) + floor((uv).y * (8.0)), 2.0)`
- Line 21, `SWAP`: Here: `{ vec3 t_ = dark; dark = light; light = t_; }`
- Line 24, `CLAMP01`: Here: `col = clamp(col, 0.0, 1.0)`

## ShaderToy.frag: the usual shorthands, as the ShaderToy import writes a shader

- Line 40, `rot`: Here: `mat2(cos(iTime * 0.3), -sin(iTime * 0.3), sin(iTime * 0.3), cos(iTime * 0.3))`
- Line 43, `S`: Here: `smoothstep(0.0, 0.01, d)`
- Line 44, `S`: Here: `smoothstep(0.3, 0.9, length(uv))`
- Line 39, `R`: **no Here.** It becomes `iResolution.xy`, which is word for word what the definition above it already says. The row is left out whenever it would repeat the definition.

## Redefined.frag: one name, defined again between two uses

- Line 14, `SIZE`: Here: `(0.5 * 0.6)`
- Line 24, `SIZE`: Here: `(0.5 * 0.9)`
- Line 14, `SHAPE`: Here: `length(p)`
- Line 24, `SHAPE`: Here: `max(abs((p).x), abs((p).y))`

The definition at the top of the sheet is the editor's own reading: the one declared last before the place. The Here row comes from the build.

## Switches.frag: grey lines

After a run these lines are grey:

- Line 15: the `#ifdef SOFT` arm. The `#define SOFT` in line 11 is commented out.
- Line 34: the `#else` arm of `#if AA > 1`, since `AA` is 2.
- Lines 37 and 38: `#if 0`.
- Line 51: the `#else` arm of `#ifdef GL_FRAGMENT_PRECISION_HIGH`, which ES 3.00 always defines.

These are not grey:

- **Lines 43 and 45**, both arms of `#ifdef GL_EXT_shader_framebuffer_fetch`. Only the phone's driver knows whether it has that extension, so the app cannot tell and dims neither.
- The `#if`, `#else` and `#endif` lines themselves.

Try these:

- Change line 9 to `#define AA 1`. When you delete the `2`, line 34 is no longer grey: with nothing after `AA`, the app cannot tell what `#if AA > 1` does. When you type `1`, lines 26 to 32 turn grey. The other grey lines stay. No run is needed.
- Or type `-` in front of the `2`: `AA` is `-2`, and in that one key lines 26 to 32 turn grey and line 34 is no longer grey.
- Remove `// ` in front of `#define SOFT` in line 11. When the last `/` goes, line 17 turns grey and line 15 is no longer grey.
- Look up `AA` in line 26: no Here, because it becomes `2`, which the definition says.

## Not here: `##`

There is no example of `##`, which pastes two names into one. GLSL ES does not allow it: Mesa refuses a macro that uses it even when the macro is never used. So no shader that compiles can contain it, and the app never shows Here for such a macro.

## Template.frag: the app's ES 1.00 template

- Lines 2 and 4 are **not grey.** In ES 1.00 only the driver knows `GL_FRAGMENT_PRECISION_HIGH`.
- Line 18 is grey: the `#else` arm of `#if STRIPES`.
