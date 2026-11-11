# Examples

Status: accepted on 2026-11-11. This is the design for the bundled examples and the include library. The work is track X of the plan.

## Recommendation

Do not overhaul everything at once, and do not write one shader per uniform. Rebuild the set around one rule: **an example maps one group of inputs to a picture through a mapping a person can check by looking** (tilt left, the ball rolls left). Such an example teaches and also serves as a device check, so one set does both jobs.

Grow the set from 12 to about 20 now, as single-file shaders that work for today's database shaders and stay valid unchanged in project mode. Add four multi-file examples and a small include library when project mode can load them. Cap the set at 24. Present it as one list with four section headings in learning order. In project mode, ship the examples as one read-only project inside the APK that the user opens in place and keeps with **Save a copy**; this needs no new copy or update rules.

## 1. What examples are for

| Job | Today | Where it belongs |
| --- | --- | --- |
| Say what a uniform means | Done by the Add Uniform list, which shows name, type and description, and soon by completion (E14) | The uniform table. Not a shader per uniform. |
| Show how to turn a raw value into something usable | Almost absent. `sample_battery` draws `step(uv.x, battery)`; `sample_gravity` uses `9.80665 / abs(gravity - 4.90332)`, which nobody can reuse | **Bundled examples.** This is the gap the owner describes. |
| Teach a technique (feedback, textures, includes, passes) | Three Conway variants, two texture samples | Bundled examples |
| Be a starting point | Works: loading inserts an editable copy | Bundled examples. No template gallery: an example you own is the template. Keep the single new-shader template. |
| Show off | Weak | The web site (`docs-site/`, which has video). In the app, a few examples good enough to set as wallpaper unchanged. |
| Check that the app works | Only for what samples use: 11 of 39 builtin names (34 table entries plus `cameraBack`, `cameraFront`, `cameraAddent`, `cameraOrientation`, `backbuffer`). 28 have no sample, and there is no cubemap sample although rule 8 of the plan asks for one in every rendering check. | Bundled examples for by-eye checks. Deterministic pixel fixtures stay in tests and in `docs/project-mode/examples/`, which should not be shipped: they are minimal on purpose. |

**Raw display.** It has one real use: finding out what a value reads on this phone. One example serves it (Meters, below), written so that any uniform can be swapped in. Every other input example shows three visible steps: read the uniform, shape it (units to a 0..1 signal, clamp, smooth, the value when the sensor is missing), draw. The second step is the lesson.

**Kitchen sinks.** A shader that reads everything is the wrong kind. The unmerged branch `origin/feat/uniform-showcase-shaders` shows why: its Sensorium declares 22 uniforms in 245 lines and mixes them into terms like `rot(gyroscope.z * 0.14 + magnetic.z * 0.012)`. A reader cannot learn what `magnetic` does from it and a tester cannot tell whether it works. Loading it starts every sensor and asks for notification access, because the notification source requests the permission when it starts. Do not merge that branch; its ideas (time-of-day skyline, front camera) are reused below. The right kitchen sink combines techniques with a purpose: a complete wallpaper. The set has one now and one multi-file one later.

**Coverage.** "Every builtin uniform and every project feature appears in some example" is worth having, as a tested property of the set and not as a design principle for single shaders. One JVM test asserts that each table name is declared by at least one bundled example, with an exemption list that needs a reason per entry. Two uniforms are hard to show legibly on their own (`rotationVector`, `inclinationMatrix`); they get an alternative code path behind a `#define` or an exemption. `format=rgba16f` stays out of bundled examples, because it fails by design on devices without float targets.

## 2. Structure

**What the user sees.** Menu, **Load sample** (rename to Examples), one scrolling list as today: thumbnail, title, one line. New: four section headings, and learning order instead of alphabetical order.

1. **Start here**: coordinates, time, touch, a texture.
2. **Device inputs**: one example per source group. Examples that need a permission say so in their line and are the only ones that read those uniforms.
3. **Techniques**: feedback, textures, GLSL ES 3.00, later includes, cubemaps and passes.
4. **Wallpapers**: finished pieces.

No tabs, search, tags or difficulty levels; 24 rows do not need them. The description line is written as "do this, see that" ("Tilt the phone; the ball rolls downhill"), so it is also the instruction for a by-eye check.

**Today (database shaders).** Unchanged: a tap inserts a copy. Its known flaw, that browsing five samples leaves five shaders in the list, is not worth fixing in code that project mode replaces.

**Project mode.** The examples are one project tree in the APK, shaped like the user's library folder:

```text
examples/
  tilt.glsl
  blobs.glsl            #include "lib/sdf.glsl"
  ink/main.glsl         passes, includes
  lib/sdf.glsl  lib/noise.glsl  lib/color.glsl
  textures/noise.png  textures/sky.png
```

A tap opens the example in place from a read-only store and runs it. UX 5 already defines the rest: on read-only storage the user keeps editing locally and is offered Save a copy, which copies the running shader and its dependencies. Nothing is written until the user wants to keep something, edits belong to the copy, and an app update replaces the bundled tree without touching any copy. There is no example versioning and no update prompt.

The rejected alternative is copying on tap into the open folder with a shared `lib/`. It needs a rule for a `lib/sdf.glsl` that already exists and differs, and an example loaded after an app update can then fail to compile against the user's older copy.

## 3. A shared include library

Yes, small, and only from the point where includes exist (Gate A). It answers the request in upstream issue 191 for bundled 2D and 3D SDF functions, and DECISIONS already fixes the model: bundled library code is copied into the project like a texture. **Add include** offers the bundled files and copies the chosen one to `lib/` at the project root; an existing file is reused, never overwritten.

Rules that keep it from becoming a dumping ground:

- **Every function is called by a bundled example.** This bounds the size and means every function has been seen running on a device. A 3D SDF file arrives together with an example that needs it.
- Three files to start: `sdf.glsl` (2D shapes, smooth union), `noise.glsl` (hash, value noise, fbm), `color.glsl` (palette, HSV). About 80 lines each at most.
- **Pure functions only**: no uniforms, no `precision`, no `#version`, no host directives, no texture lookups. The last point matters: `texture2D` exists only in GLSL ES 1.00 and `texture` only in 3.00, so a library that samples cannot serve both. The sampler example in DECISIONS has this problem.
- Include guards, since `#pragma once` is unsupported. Conventional names (`sdCircle`, `hash21`, `fbm`) without an app prefix, so pasted code reads the same.
- Functions are added, not changed. A copy in a user's folder never updates.
- Each file header states origin and licence. Contributions are welcome under the first rule.

Most examples keep their helpers inline. On a phone, the function the learner wants to read should be on the same screen; only composition examples include the library.

## 4. The set

Status: K keep, R rework, N new, X remove. Lengths are targets.

| # | Section | Title | Teaches | Exercises | Status and reason |
| --- | --- | --- | --- | --- | --- |
| 1 | Start | Shapes | Pixel to centered, aspect-correct coordinates; a circle by distance; a soft edge | `resolution`, `time`, `mouse` | R, replaces Circles, whose rings alias and teach nothing reusable |
| 2 | Start | Loop | Animation that never drifts: `ftime` with `FTIME_PERIOD`, `second` + `subsecond` | `ftime`, `second`, `subsecond`, `frame`, `startRandom` | N. Answers a FAQ entry (`time` loses accuracy) |
| 3 | Start | Touch | A glow under every finger | `pointers`, `pointerCount` | K |
| 4 | Start | Texture | Sampling an image; later wrap, filter and mipmap options | library texture; `se input` options, `mipmap` | K now, R after Gate A |
| 5 | Inputs | Tilt | m/s² to a tilt vector; a ball rolls downhill; a shake kicks it | `gravity`, `linear` | R, replaces Gravity |
| 6 | Inputs | Compass | A heading from the orientation angles; field strength and dip | `orientation`, `magnetic`, `inclination`, `inclinationMatrix` | R, replaces Orientation |
| 7 | Inputs | Window | The phone as a window into a procedural sky; fast turns streak the stars | `rotationMatrix`, `gyroscope`, `rotationVector` | N |
| 8 | Inputs | Meters | The raw-display example: three bars with unit-to-range mapping; swap in any uniform | `light`, `pressure`, `proximity` | N |
| 9 | Inputs | Battery | Charge as a fill level; animates while charging | `battery`, `powerConnected` | R |
| 10 | Inputs | Clock | Angles from time of day; date marks; dark palette at night | `daytime`, `date`, `nightMode` | N |
| 11 | Inputs | Sound | A ring that follows loudness, with smoothing (microphone permission) | `micAmplitude`, `mediaVolume` | N |
| 12 | Inputs | Notifications | A dot per notification, a pulse for a new one; handles the NaN "none yet" value (notification access) | `notificationCount`, `lastNotificationTime` | N |
| 13 | Inputs | Camera | Getting the camera image upright; the orientation maths is the lesson (camera permission) | `cameraBack`, `cameraAddent`, `cameraOrientation` | K; fix the description, which still mentions Ice Cream Sandwich |
| 14 | Inputs | Mirror | A filter on the front camera | `cameraFront` | N. A separate shader because both cameras cannot be open together |
| 15 | Techniques | Trails | Feedback as previous × decay + new: finger painting that fades | `backbuffer` with linear sampling, `touch` | N. The most reusable feedback idiom |
| 16 | Techniques | Game of Life | State in the backbuffer at low resolution | `backbuffer`, quality 1/8; later `se target scale` | K |
| 17 | Techniques | Electric Fade | Starting feedback from an image | backbuffer preset; later `se target initial` | K |
| 18 | Techniques | Swirl | Distorting texture coordinates by a drag | texture, `touch`, `touchStart` | R, small |
| 19 | Techniques | GLES 3 | Something 3.00 only: integer hash, `textureSize` | `#version 300 es` | R. Today it is the default shader with a version line and the default thumbnail |
| 20 | Wallpapers | Horizon | A complete, cheap wallpaper: sky by time of day, layers that shift with the home screen | `offset`, `daytime`, `nightMode`, `se target present` later | N |
| — | — | Cloudy Conway | — | — | X. Third variant of the same automaton; 67 lines that differ from Electric Fade in channel order |
| 21 | Techniques | Blobs | Composing shapes and colour from the library | `#include`, `lib/sdf.glsl`, `lib/color.glsl` | N, after Gate A |
| 22 | Techniques | Skybox | Window again, with the colour read from a cubemap | `samplerCube` strip, `rotationMatrix` | N, after Gate A. Needs a generated, original strip image |
| 23 | Techniques | Bloom | A second pass at quarter size, read in the same frame | shader input, `se target scale`, `filter=linear` | N, after Gate B |
| 24 | Wallpapers | Ink | The technique kitchen sink: a simulation pass with history and a display pass | `frame=previous`, `initial` from a shader, `#include`, a texture, `touch`, `gravity` | N, after Gate B |

Rows 1 to 20 declare all 39 builtin names. With rows 21 to 24 the set uses every project feature except the manifest, a fixed target `size`, and `format=rgba16f`; those three stay with the tests.

## 5. Quality bar

A reviewer checks each example against this list.

1. **One idea**, named in the title, and visible within two seconds of doing what the description says.
2. **Length**: 60 lines at most for Start and Inputs, 120 for Techniques and Wallpapers. No dead code.
3. **Three marked steps** in input examples: read, shape, draw. Comments give the unit and range of each uniform used and nothing that restates the code.
4. **Works without its input.** A missing sensor or a denied permission leaves the uniform at zero (or NaN for `lastNotificationTime`); the example must still draw something sensible, not a black screen.
5. **Permissions**: at most one permission per example, named in its description. No example outside its group reads those uniforms.
6. **Cost**: holds the refresh rate on a mid-range phone at the quality it ships with; no loop over 32 iterations; no raymarching at full resolution. A Wallpapers example must be something one would leave on a home screen: calm, readable behind icons, correct in portrait and landscape.
7. **Both language versions matter**: GLSL ES 1.00 unless the example is about 3.00. Use the `GL_FRAGMENT_PRECISION_HIGH` guard the current samples use.
8. **Own thumbnail**, captured from the running example. No reused default thumbnail.
9. **Original** code and images, or a licence stated in the file. Bundled images are generated by a script kept in the repository.
10. **Strings**: a title of one or two words and a description under 60 characters, in English, with the same text in every locale file.

## 6. Delivery

A third track, X, next to E and M. Each PR follows the plan's rules; content PRs are marked ★ because the user sees new samples, and their evidence is one emulator screenshot per changed shader.

Now, with no dependency on project mode. These help the engine track at once: E6 (camera) and E17 need exactly the by-eye coverage that is missing.

| PR | Change |
| --- | --- |
| X1 | JVM test: every builtin name is declared by a bundled sample; the exemption list starts with the 28 uncovered names and each later PR shortens it |
| X2 ★ | Sample list in learning order with section headings |
| X3 ★ | Start here: Shapes replaces Circles, Loop |
| X4 ★ | Motion sensors: Tilt, Compass, Window |
| X5 ★ | Environment and system: Meters, Battery, Clock |
| X6 ★ | Permission inputs: Sound, Notifications, Mirror |
| X7 ★ | Techniques: Trails, GLES 3, Swirl; remove Cloudy Conway |
| X8 ★ | Wallpapers: Horizon |

After Gate A (needs M11 read-only handling and M13 Save a copy):

| PR | Change |
| --- | --- |
| X9 | Move the examples to one asset tree and rewrite the three texture users in project form (move and edit as separate commits); JVM test that `prepare` accepts every example. M5's converter tests keep the old sample texts as their own test resources. |
| X10 ★ | Examples open in place from a read-only store; remove the insert-from-resource path for samples |
| X11 ★ | Include library with Blobs; Add include offers the bundled files |
| X12 ★ | Skybox with its strip image; Texture gains sampling and mipmap options |

After Gate B: X13 ★ Bloom, X14 ★ Ink and Game of Life on `se target scale`.

Separately, not for upstream review of the app: `docs-site/` holds a full copy of each sample's source in the front matter of 13 walkthrough files. It should read the code from the example files at build time, as its FAQ loader already reads `FAQ.md`.

Changes to the documents:

- **SPEC 4**, last paragraph: bundled include files and examples follow the same rule as bundled textures (copies, no global references, no updates).
- **UX 1 and 4**: Examples as an entry that opens the bundled project read-only; Add include may pick a bundled library file, copied to `lib/`.
- **DECISIONS**: "library code, if it is ever added" becomes a decision, with the pure-function rule and a corrected sampler example.
- **IMPLEMENTATION**: track X; rule 8 names the examples to check and notes that no cubemap example exists before X12.
- **ACCEPTANCE**: one case for opening an example and saving a copy.

## 7. Decisions taken

| Question | Decision |
| --- | --- |
| How an example reaches the user in project mode | Opened in place from a read-only project inside the app; kept with Save a copy |
| Coverage | A JVM test with a justified exemption list: every builtin name is declared by some bundled example |
| Size and existing samples | About 20 now, cap 24. Circles, Gravity and Orientation are replaced; Cloudy Conway is removed |
| Include library | Three small files; every function is used by a bundled example |
| Timing | Phase one (X1 to X8) now, beside the engine track |
| Languages | Titles and descriptions are written in English. The app has no German locale and none is added. The Russian, Ukrainian and Chinese files receive the English text until translators replace it |

A broad community library stays out of the app. The folder model already allows one: a library can be its own repository that a user places next to their shaders and includes by relative path.

## 8. What was checked when this was written

Read in this session: every bundled sample and the code that lists and inserts them; `UniformTable.java` and its sources; the sample strings in all locale files; SPEC, UX, DECISIONS, ENGINE, the plan, the acceptance list and the fixtures; the showcase branch; the `docs-site` examples component and FAQ loader; issue 191 with its comments. The count of 28 uncovered names comes from matching uniform declarations in the sample files against the table.

Not verified:

- Nothing was built, run or rendered. No example in the list has been written, so the line targets and the claim that each mapping is legible are estimates. `rotationVector` and `inclinationMatrix` are the likeliest to need an exemption.
- That lint fails on a missing translation. The lint configuration does not disable the check and the locale files are kept in parity, but lint was not run.
- That `se target scale` takes effect for a single-pass shader before M15. If it does not, Game of Life keeps its quality as app metadata until then.
- How the shader list will show files in subfolders, and how much of read-only handling M11 will deliver; X10 depends on both.
- Emulator support for each sensor used in by-eye checks.
- The licence of any third-party function. None is proposed for inclusion here.
- The upstream maintainer's view on removing or renaming samples he wrote.
- The plan's handoff file still says no PR has started; I took "E1 to E5 and M1 to M3 merged" from the brief and the commit log.
