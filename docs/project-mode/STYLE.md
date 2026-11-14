# A visual language for ShaderEditor

Status: proposal of 2026-11-12, revised the same day after the owner's decisions (last section). Two decisions are open: S6, the shape rule, and S8, where the drawer ends (section 9). No production code was written, nothing was pushed. Pictures are in `.foreman/show/style/`; every sheet named below is a PNG there with the HTML it was rendered from next to it (section 11 says how to rebuild them).

What is evidence and what is taste is marked. "Measured" means computed from pixels of real screenshots by `show/style/data/measure.mjs`. "Computed" means arithmetic on colour values. Everything about how the recommended design looks is a mock-up; none of it has run on a device.

## Summary

**The identity, as found in the app.** ShaderEditor is a dark instrument laid over a live picture. The picture is the only thing allowed to be loud; code is white monospace with a few fixed syntax colours; everything the app adds is a quiet neutral layer whose opacity says how much of the picture is needed right now.

**What is decided.** Direction C, "Instrument": the library's standard Material components, fed a fixed near-neutral dark scheme with one accent, and translucency only where the picture is being looked at, the code and its bars. The accent is the blue of the launcher icon by default; a setting lets it follow the device's colours on Android 12 and later (6.1). The drawer is solid. Code is made readable by limiting the brightness of the picture in the engine's presentation step, at the Balanced level by default with a setting of three levels, and by a syntax palette of seven colours and two greys; together they give every code colour at least 5.0 : 1 on the eight measured frames, where today's worst is 1.3 : 1. The way there is twelve small pull requests, the theme only after asking the maintainer. Where they pull apart, readable comes before familiar and familiar before quiet.

**What is open.** S6: one rule for corners, because the frames until now mixed square, rounded and pill shapes. I recommend "one radius": 8 dp for anything up to 56 dp tall, 16 dp for larger things that float, square where something touches a screen edge. S8: where the solid drawer ends. I recommend the standard full-height drawer, behind both system bars, with its header in the toolbar's place.

**Why today looks accidental: six root causes** (section 1).

1. Three sources of colour side by side: Material's dynamic roles, hard-coded values, and platform defaults.
2. One translucency (53 %) used for every surface, with no floor for contrast.
3. No type scale: nine text styles set layout by layout.
4. No grid for size, spacing or shape; fixed widths and heights that cut text.
5. Components and feedback chosen per screen: 22 toasts and 1 snackbar, platform spinners next to Material dialogs, empty screens.
6. No wording rules: three capitalisations, the object repeated in every label, questions as labels.

| Sheet | Shows |
| --- | --- |
| `sheet-audit.png` | 24 real screenshots of today's app with findings |
| `sheet-direction-a-material.png`, `-b-glass.png`, `-c-instrument.png` | The three directions, the same five screens each |
| `sheet-reading-1-options.png`, `sheet-reading-2-levels.png`, `sheet-reading-3-questions.png` | Code over real shader frames: eight treatments; the three levels; a mostly black picture with bright spots and a picture that keeps changing brightness |
| `sheet-palette.png` | Syntax palette today and proposed, simulated for three colour-vision deficiencies |
| `sheet-recommended-1-editor.png`, `-2-drawer.png`, `-3-screens.png` | The recommended design, 20 frames; each also alone at twice the size as `c-NN-*.png` |
| `sheet-shapes.png` | S6: the shapes in use until now and three complete systems, four screens each |
| `sheet-drawer-edges.png` | S8: four ways for the drawer to end, with and without code, and docked |
| `sheet-accent-setting.png` | The accent as a setting: icon blue and three device accents under one rule |
| `sheet-decided.png` | What S1 and S2 were chosen from (record) |

---

## 1. Audit

![audit](../show/style/sheet-audit.png)

The sheet has the screenshots; each caption names what is wrong and which root cause it comes from. The root causes, each with where it sits in the code:

**R1. Three sources of colour.** The theme is `Theme.Material3.DynamicColors.Dark` (`styles.xml`), so menus, dialogs, switches, section headings, buttons and selection handles take the device's wallpaper colour. `colors.xml` adds 17 fixed values built from pure white and black (`#fff`, `#8000`, `#8222`, `#8a000000`). A third group comes straight from the platform: `android.R.color.primary_text_dark` (`PresetUniformAdapter.java:107`), `0x88888888` (`LineNumberEditText.java:57`), `@android:style/TextAppearance.Medium`, `Widget.EditText`. Consequences seen: the drawer's "No shaders" text is black at 54 % on a dark panel (`drawer.xml`, `@color/secondary_text`) and cannot be seen; the snackbar is the only light surface in the app; the text-selection toolbar is light because the platform draws it for the system's light mode while the app forces dark (audit 24).

**R2. One translucency for everything, and no floor.** `#8222` is the toolbar on every screen, the extra-keys row, the gesture-bar strip, the tab row, the status bar colour (`Preferences.java:97`), the filled key and a divider. `#8000` is the editor, the drawer and the progress overlay. Both are 53 %. Nothing checks what ends up behind text. Measured on real frames (section 5): comments fall to 1.3 : 1 and keywords to 1.7 : 1 over the Clock example; the white toolbar title has 3.5 : 1 over a white shader. On screens without a picture the toolbar is still translucent, over nothing. The drawer lets the code through, so two texts overlap (audit 6, 22).

**R3. No type scale.** Nine styles for six jobs: the platform's `Medium` (18 sp) for toolbar titles and list names, `Small` (14 sp) for second lines, 12 sp for the subtitle, 14 sp in `row_shader.xml`, 16 sp in two empty states, AppCompat `Body2` for section headings, Material `Body1` for keys, 14 dp painted in `CubeMapView`, 12 sp code. `error_item.xml` sets a text size and colour on a `LinearLayout`, where they do nothing. The code font appears only in the editor: uniform names, GL constants and completion keys are set in the UI font.

**R4. No grid.** Paddings in use: 3, 4, 8, 13, 16, 20, 24 and 32 dp. Corner radii: 8 (keys), 10 (menu), 16 (ripple mask), 28 (Material's sheets and dialogs), 999 and 1000. The same shader picture is 24 dp in the drawer and in Settings, 48 dp in Examples and textures. Drawer and menu are fixed at 240 dp and the toolbar at one height, so at a font scale of 1.75 the frame rate is cut in half and three menu labels and one Settings title are cut off (audit 19 to 21, captured today). Completion keys are 40 dp high.

**R5. Components and feedback chosen per screen.** Four `ListView`s with hand-made rows beside two `RecyclerView`s; six platform spinners; platform `EditText`, `SeekBar`, checkbox and progress wheel inside Material screens; a hand-built popup menu; eight floating buttons whose icon is the only hint ("arrow" means insert, a floppy disk means save, on one screen two of them left and right). Feedback has no rule: 22 `Toast` calls, one snackbar that is moved to the top over the title, five dialogs, one sheet. A list with nothing in it is an empty screen. Selected, running and failed have no shared signs: selected is brighter text, an error is a coloured band.

**R6. No wording rules.** "Show Extra Keys", "Use Ligatures", "Sensor Delay", "Insert Code" in title case beside "Save battery" and "Run mode". Five menu entries end in "shader". The screen is "Preferences", its menu entry "Settings". Summaries repeat titles ("Sort by last modification / Sort shaders by last modification"). A checkbox is labelled with a question. The delete dialog asks "Are you sure you want to delete this shader?", does not say which, and is confirmed with "OK". Three uniform descriptions say "only on Ice Cream Sandwich or better" in an app whose minimum is Android 6. Tabs are the lowercase words "preset", "sampler2D", "samplerCube". Three of the five toolbar buttons are announced to TalkBack by their resource id (read from the accessibility tree today).

What is *not* wrong, so that it is not "fixed": the icons in `res/drawable` are already one family (Material Symbols, outlined, weight 400). The main screen's structure, one bar and the code, is right. The app is dark only, and should stay so (section 8).

## 2. What is characteristic and must stay

Argued from the app, not from preference:

- **The picture is the screen.** `activity_main.xml` puts the `ShaderView` behind everything and every other view on top of it. No other Android code editor does this; it is the product.
- **The app gets out of the way.** One bar. The eye button removes the code; the extra keys hide with the keyboard; the frame rate is the only number shown. Chrome is something to be reduced, not designed up.
- **Code is white monospace on dark with a few colours.** The launcher icon says exactly this: a dark tile, `vec3(` in blue, `1.` in green, a white caret, and a strip of the picture along the top. Blue, green, white and dark grey are the app's colours already; nobody chose them for the UI yet.
- **Dark only.** The picture is the light source. A light theme would put a bright frame around it and invert the code's palette for no user's benefit.
- **Translucency has a reason here**, unlike in most apps: it shows the thing being worked on. That reason is also its limit, and today it is not applied as one.

**The identity in three sentences, for a screen nobody has drawn yet.** ShaderEditor is a dark instrument laid over a live picture: the picture is the only loud thing, and code is white monospace with a few fixed colours. A surface is translucent only when the picture is directly behind it and being looked at, and then only as far as its own text stays readable over white; everything else is a plain, solid, neutral dark, built from the platform's familiar parts. The app's own colour is one blue, used for what is active, and its own voice is two typefaces: the UI font for what the app says, the code font for what is GLSL. Where readable, familiar and quiet pull apart, they win in that order.

## 3. Directions compared

**Decided: C.** The comparison is kept as the record of why. Three directions, each on the same five screens: the editor over the GLES 3 example, the editor over a bright shader (Clock), the open drawer, a sheet, Settings. The structure of toolbar and drawer is the accepted one from NAVIGATION.md in all three; only the style differs.

### A. Material 3 by the book

![A](../show/style/sheet-direction-a-material.png)

- **Says:** an Android app like the others on this phone.
- **What it is:** what the theme already asks for, carried through: dynamic colour, tonal opaque surfaces, pill selection, 28 dp sheets, standard components everywhere, hard-coded colours deleted.
- **Cost to build:** the lowest. Mostly deleting overrides. It does not answer the app's own problem, text over a picture; that needs section 5 in any direction.
- **Cost to keep consistent:** low. The library does it; a Material update can shift the look.
- **Bright, busy or white shader:** unchanged in the editor. Elsewhere fine, because surfaces are opaque.
- **Below Android 12:** the accent is Material's default violet on every device. The app looks different by Android version.
- **Large fonts:** good, standard components scale; the fixed toolbar and menu still need fixing.
- **The maintainer has to accept:** nothing new.
- **Worst at:** identity. Three colour systems meet on the main screen (the device's accent, the syntax colours, the picture), and the accent is whatever the wallpaper gives: green beside green numbers, pink beside red errors (`sheet-accent-setting.png`).

### B. Glass over the shader

![B](../show/style/sheet-direction-b-glass.png)

- **Says:** the picture is everything; the app is a pane on it.
- **What it is:** every surface is a translucent pane with the picture blurred behind it, on every screen; white is the only accent; colour comes from the picture.
- **Cost to build:** the highest. Android cannot blur a GL surface behind views: the window-blur API (Android 12) blurs what is behind the window, a `RenderEffect` blurs a view's own content. The blur has to be drawn by the app's presentation step for each pane's rectangle, every frame. Settings, Examples and Add uniform are separate activities without the GL surface; they would have to become panels of the main activity or carry a frozen frame.
- **Cost to keep consistent:** high. Every component gets a custom background; every new surface has to be checked against bright and busy pictures.
- **Bright, busy or white shader:** the weakest. Blur removes fine patterns (GLES 3 becomes calm) and does nothing against brightness: over the Clock the code is as unreadable as today (frame 2; measured 1.3 : 1 for comments with blur).
- **Below Android 12:** the same everywhere, which is good.
- **Large fonts:** fine.
- **The maintainer has to accept:** a rendering feature that exists for the chrome, extra passes that cost frame rate in an app that displays its frame rate, and restructured activities.
- **Worst at:** cost and legibility at once. And a settings list over a moving picture is harder to read for no gain.

### C. Instrument (recommended)

![C](../show/style/sheet-direction-c-instrument.png)

- **Says:** a precise tool; your picture and your code are the colour.
- **What it is:** a fixed neutral dark scheme of four surface tones, one accent (icon blue), an error red and a warning amber. The library's standard components with their standard shapes and behaviour, fed that scheme through the theme. Translucent only where the picture is being looked at: the code and its bars. The picture behind code limited in brightness; a lighter syntax palette. The code font for everything that is GLSL.
- **Cost to build:** medium. One theme file with about 25 fixed values; one translucent colour; one uniform in the presentation shader; then screen by screen, mostly replacing hand-made parts by the library's.
- **Cost to keep consistent:** low to medium. Components read the theme, so consistency is the default; the fixed scheme is ours to keep when Material adds roles.
- **Bright, busy or white shader:** handled by rule, not by luck: every level is computed for a white picture (section 6.2).
- **Below Android 12:** identical on every version.
- **Large fonts:** needs the same toolbar and menu fixes as A (in the path).
- **The maintainer has to accept:** that the app no longer follows the device's dynamic colour by default. It became a setting (6.1), which makes that easier to accept.
- **Worst at:** being native. A user with a themed phone gets an app that ignores the theme, and a fixed palette is a commitment somebody has to keep.

### Comparison

| | A Material | B Glass | C Instrument |
| --- | --- | --- | --- |
| Keeps the picture in view | on the main screen | everywhere | on the main screen |
| Code readable over any shader | no, separate fix | no, separate fix | yes, part of it |
| Colour sources on the main screen | 3 | 2 | 2 |
| Same look on Android 6 to 16 | no | yes | yes |
| New rendering work | none | blur per pane, per frame | one line in an existing shader |
| Screens restructured | none | 3 activities | none |
| Values to maintain by hand | about 5 | about 15 plus per-surface checks | about 25 |
| Review cost upstream | low | high | medium |
| Worst at | identity | cost; bright pictures | following the device's theme |

## 4. The direction, and its weak points

**Direction C.** It is the only one of the three that treats text over a picture as its first problem, which is this app's first problem. It keeps what section 2 found and nothing else: the picture, the reduction, the icon's colours. And it gets consistency cheaply, because it changes what the components are fed, not the components.

It is judged in this order: **readable, familiar, quiet.** An earlier draft of the mock-ups got that order wrong in three places, which the owner pointed out: a home-made segmented control whose selected half was barely darker than the other, section headings in 12 sp grey, and the frame rate in 12.5 sp code font inside a hairline. All three are now the library's component at the library's size (`c-08`, `c-15`, `c-01`), and the same test removed three more inventions: values at the end of settings rows, a tinted field without an outline, and a translucent drawer. For shape I then fell back on the library's defaults, and those are the mixture of square, rounded and pill the owner named next; S6 replaces them with one rule.

Its weak points, plainly:

- **Two accents have to keep working.** With "Use device colours" on, the accent is whatever the wallpaper gives: it can sit next to a syntax colour of the same hue, or look like the error red. The rules of 6.1 keep that harmless, and every screen has to be looked at in both.
- **The limit costs picture.** It is a fixed curve, not an adaptation (5.5), but it pulls everything bright under one ceiling: a white shader appears as #333 grey, bright detail that differs only in brightness is squeezed into a narrow range, and **small bright features on a dark picture become faint**: the white discs of the Touch example stand out 1.7 : 1 instead of today's 4.7 : 1 (`sheet-reading-3-questions.png`). The eye button shows the truth, one tap, as it does today. The Bright level of the setting is for those who weigh the picture higher.
- **The accent is also the keyword colour.** Inside code, blue cannot mean "interactive". The selection therefore is a dark blue, not the accent, and still leaves comments at 3.8 : 1 in the worst case (computed).
- **A solid drawer makes it look less particular** than it could. The character is left to the main screen, where the picture is.
- **The scheme is hand-made.** I chose the tones by eye and checked them by contrast arithmetic, not with Material's tone generator, and never on a device. An OLED panel at low brightness may want the surfaces a step lighter.
- **Blue is taste**, argued from the icon.

## 5. Reading code over the shader

The owner's example is GLES 3. The good part to keep: the live picture stays visible behind the code.

### 5.1 Evidence

Frames captured today from the running app with the code hidden by the eye button (so they are what is behind the code), 1080 × 2030 px each. Every fourth pixel was sampled without averaging, so that thin bright lines survive. For each syntax colour the WCAG contrast ratio against each pixel after today's treatment was computed. "Worst" is the 1st percentile over the frame, "typical" the median, "area" the share of the frame under 4.5 : 1. The blending model (encoded values, `#8000` = 53.3 % black) was checked against a real screenshot with code: predicted 111, 108, 102 for the Clock's face, found 111, 108, 102.

| Frame | White text, worst / typical | Keyword `#2196F3`, worst / typical, area | Comment `#808080`, worst / typical, area |
| --- | --- | --- | --- |
| **GLES 3** (busy, bright thin lines) | 7.8 / 19.7 | **2.5** / 6.3, 21 % | **2.0** / 5.0, 21 % |
| **Clock** (large near-white area) | 5.2 / 17.4 | **1.7** / 5.6, 33 % | **1.3** / 4.4, 99 % |
| **Texture** (grey noise at pixel scale) | 8.2 / 17.3 | **2.6** / 5.5, 20 % | **2.1** / 4.4, 56 % |
| **Electric Fade** (saturated, changes every frame) | 6.8 / 19.4 | **2.2** / 6.2, 2 % | **1.7** / 4.9, 23 % |
| Swirl | 7.2 / 18.6 | 2.3 / 5.9, 9 % | 1.8 / 4.7, 37 % |
| Horizon by day (earlier capture) | 8.5 / 11.6 | 2.7 / 3.7, 81 % | 2.2 / 2.9, 93 % |
| A user's shader with a white corner (earlier capture) | 5.7 / 10.1 | 1.8 / 3.2, 89 % | 1.4 / 2.5, 100 % |
| A bright green-to-magenta gradient (earlier capture) | 7.0 / 13.0 | 2.2 / 4.2, 57 % | 1.8 / 3.3, 86 % |

Two facts explain all of it. First, 53 % black over white is mid-grey `#777`; the comment colour is mid-grey `#808080`. Second, of the twelve syntax values only white and the amber type colour have a luminance above 0.5; keyword blue has 0.29 and reaches 4.5 : 1 only on backgrounds darker than `#2B2B2B`.

A flat layer of black cannot fix this without removing the picture: to bring a white pixel down far enough for the keyword blue it has to be 83 % black, and then a dark shader, which needed nothing, is shown at 17 %.

### 5.2 Options, on the same frames

![options](../show/style/sheet-reading-1-options.png)

Lowest "worst" contrast over the eight frames, for comments and for the weakest other code colour (measured; the full tables are in `show/style/data/report.txt`):

| Treatment | Today's colours | Lighter colours (5.4) | Picture left, mean brightness of 255 (GLES 3 / Clock) | Cost |
| --- | --- | --- | --- | --- |
| Today: 53 % black | 1.3 · 1.7 | 2.0 · 2.4 | 32 / 57 | – |
| Darker: 86 % black | 4.1 · 5.2 | 6.2 · 7.5 | 10 / 17 | One value. The picture is nearly gone, also where it was dark |
| Adaptive: one level of black per frame, from the frame's brightness | 2.6 · 3.3 | 4.0 · 4.8 | 16 / 26 | Reading pixels back on the GL thread every few frames, smoothing against flicker. Fails on pictures that are bright only in places (Texture, Swirl) |
| Band: 88 % dark behind each line of text only | 3.6 · 4.5 | 5.5 · 6.6 | untouched outside the text | New editor: one rectangle per visible line, recorded with the line (EDITOR 4.4), microseconds. Old editor: a span per line over the whole text, which is what EDITOR.md removes. Ragged; the bare picture glares between and beside the bands (Clock) |
| Halo: a dark shadow around the glyphs | not measurable per pixel | | as today | `setShadowLayer` per run, paid when a line is recorded. At 12 sp the shadow fills the strokes; large bright areas stay bright |
| Blur 9 dp, then 53 % black | 1.3 · 1.7 | 2.0 · 2.4 | as today, smeared | Extra passes in GL on every frame, competing with the shader for frame rate. Cures fine patterns (GLES 3: 4.2), not brightness (Clock: 1.3) |
| Grey, then 53 % black | 1.3 · 1.7 | – | as today, without colour | Removes what the user is making; no gain in contrast |
| **Limit: nothing brighter than `#333`** | 3.3 · 4.2 | **5.0 · 6.0** | 27 / 38 | One line in the existing presentation shader, one uniform. No pass added, nothing on the main thread |
| Hide or fade the picture while typing | – | – | – | The same uniform, driven by key presses. Rejected: the screen would pulse with every pause, and seeing the result while typing is the point of running on change. The eye button stays the explicit control |

**What "limit" is, and is not.** It is not automatic dimming: nothing is measured, nothing depends on the frame as a whole or on earlier frames (5.5). The engine already draws every frame into a texture and then presents it with a one-line shader (`ShaderRenderPipeline.SURFACE_FRAGMENT_SHADER`); thumbnails are taken from the texture, not from the screen. The limit is one more line there:

```glsl
uniform float limit;            // 0 = the picture as it is
vec3 c = texture2D(frame, uv).rgb;
c *= mix(1.0, k / (k + max(c.r, max(c.g, c.b))), limit);   // k = ceiling / (1 - ceiling)
```

It scales each pixel by its own brightness: a dark pixel passes almost unchanged, a white one lands on the ceiling, hue is kept. With a ceiling of `#33` (20 %), k is 0.25: brightness 5 % becomes 4 %, 20 % becomes 11 %, 50 % becomes 17 %, 100 % becomes 20 %. Today's black makes them 2 %, 9 %, 23 % and 47 %. So parts of a picture darker than about 30 % are shown *brighter* than today, and everything above is pulled under the ceiling. On a bright frame the picture as a whole ends up darker than today (last column of the table: 38 instead of 57 for the Clock); that is the price, and it is where today's text cannot be read. The view system cannot do this: views are laid over the GL surface with plain alpha, which can only scale everything by the same factor.

Feasibility I checked in the code: the presentation step exists for every frame (`PlanRuntime` → `pipeline.present`), `captureThumbnail` redraws the texture separately, the wallpaper uses the same pipeline (its limit stays 0). Not checked: it has not been compiled or run.

### 5.3 What is decided

- **The limit, default ceiling `#333`, with the lighter palette of 5.4.** The scrim `@color/background_editor` becomes transparent.
- **Target: 4.5 : 1 for every code colour, comments included, against the brightest pixel the limit allows.** That is WCAG's AA for body text. Not 3 : 1, because 12 sp regular monospace is the opposite of large text. Not 7 : 1, because that needs a ceiling near `#1A` and leaves a mean brightness under 20 of 255. Line numbers are held to 3 : 1.
- **It holds for every shader, by construction, not only for the examples**: no pixel behind code is brighter than `#333`, and on `#333` the palette has 4.8 (comments), 5.9 (keywords), 6.5 to 8.7 (the others), 12.6 (text), 3.9 (line numbers). Measured on the eight frames the worst is 5.0 and 6.0, a first-percentile figure on frames that never reach white; in the running app, on shaders that do reach the ceiling (Texture, Electric Fade), the measured worst is the computed 4.85 and 5.9.
- **What stays adjustable:** one setting, "Picture behind code", with three values (`sheet-reading-2-levels.png`): **Bright** (ceiling `#4D`: in the worst case comments 3.2, invalid characters 3.4, keywords 3.9, control-flow words 4.4 and line numbers 2.6, all measured in the app and all below the target; for people who prefer the picture), **Balanced** (`#33`, default), **Dim** (`#1A`: 6.7). A list of three, like the other settings, not a slider: each value has a known guarantee.
- **The eye button** shows the picture untouched, as today.
- **How it fits the rest:** it is the first of the two translucency levels of 6.2, and both follow one rule: fix the brightest thing that can be behind a surface's text, so that the surface's dimmest text keeps 4.5 : 1. For code the limit does it in the engine; for the bars a view colour does it.
- **It changes on one occasion only:** the eye button, which today removes the black and then removes the limit. Nothing else moves it: not the shader, not typing, not the drawer.
- **Line marks and selection darken, never lighten.** A light tint over the ceiling would eat the margin: an error band at 20 % red would leave comments at 3.8. The marks in 6.9 are dark tints: comments keep 5.4 on an error line and 4.8 on a warning line; on a selection text has 9.8, keywords 4.6, comments 3.8 (all computed for the worst case).

![levels](../show/style/sheet-reading-2-levels.png)

### 5.4 The syntax palette

Today: twelve values, of which keyword, sampler, built-in and qualifier are four blues between 3 and 15 ΔE apart, which is one colour at 12 sp; comment and keyword are too dark for any picture. Proposed: seven colours and two greys, none with a luminance under 0.35, hues taken from the icon (blue, green, white) plus orange and one red.

| Resource | Value | L* | On `#333` | Role | Today's resources that take it |
| --- | --- | --- | --- | --- | --- |
| `code_text` | `#FFFFFF` | 100 | 12.6 | Names, operators, punctuation | `editor_text`, `syntax_identifier`, `syntax_operator`, `syntax_field_selection` |
| `code_keyword` | `#6CB6FF` | 72 | 5.9 | Keywords, qualifiers, precision, every type | `syntax_keyword`, `_type_primitive`, `_type_vector`, `_type_matrix`, `_type_sampler`, `_type_image`, `_atomic_counter`, `_type_qualifier`, `_precision`, `_type` |
| `code_builtin` | `#8FE3EE` | 85 | 8.7 | Built-in functions and `gl_` variables | `syntax_builtin_function` |
| `code_number` | `#A5D66F` | 80 | 7.5 | Numbers, `true`, `false`, strings | `syntax_number_literal`, `_bool_literal`, `_string` |
| `code_flow` | `#FFA657` | 76 | 6.5 | `if`, `for`, `return`, `discard` … | `syntax_control_flow` |
| `code_comment` | `#9BA1A8` | 66 | 4.8 | Comments | `syntax_comment` |
| `code_directive` | `#C8CCD2` | 82 | 7.8 | `#version`, `#define`, `#pragma` … | `syntax_directive` |
| `code_invalid` | `#FF7B8A` | 67 | 5.1 | Characters GLSL does not allow | `syntax_invalid` |
| `code_line_number` | `#8A8F96` | 59 | 3.9 | Line numbers | `editor_line_numbers` |

The 21 `syntax_*` names stay as they are and point at these nine, so the highlighter does not change.

**Colour-vision deficiencies** (`sheet-palette.png`; simulation after Machado, Oliveira and Fernandes 2009, full dichromacy; distances are ΔE76, under about 20 two colours are hard to tell apart at this size). The palette is built so that the roles that are all *words* differ in lightness, which no deficiency removes: text 100, built-in 85, keyword 72, comment 66.

| Seen with | Closest pairs in the proposed palette | Why it is acceptable |
| --- | --- | --- |
| Full colour vision | comment / directive 16; text / directive 18 | A directive starts with `#`, a comment with `//` |
| Protanopia | built-in / directive 9; number / flow 10 | Numbers are digits, flow words are words; directives start with `#` |
| Deuteranopia | number / flow 13; built-in / directive 13 | The same |
| Tritanopia | keyword / built-in 14; number / directive 15 | Keyword and built-in still differ by 13 L* |

It is not free of confusions, and no palette with five hues is. The honest weak pair is number against flow for red-green deficiencies. Today's palette is worse in every row (protanopia: keyword / built-in 12, number / flow 11; tritanopia: built-in / qualifier 6).

### 5.5 Two awkward pictures

![questions](../show/style/sheet-reading-3-questions.png)

The owner asked what happens with a shader that is mostly black with a few bright spots, and with one that constantly changes brightness. Both questions assume the backdrop is adjusted dynamically. It is not: the limit is the same fixed curve for every pixel of every frame, and a pixel value always comes out the same. The option that *would* adjust dynamically is "adaptive" in 5.2, and these two cases are why it is rejected.

**Mostly black, a few bright spots** (the Touch example with two fingers down, a real frame: 11 % of it is white).

- Black stays black. Only the pixels of the spots change, each by itself; there is no overall dimming and nothing to get wrong about "how bright is this frame".
- Text on a spot: comments 4.8, keywords 5.9 (today: 1.1 and 1.4, measured).
- **The cost is the spot.** Against the black around it a white disc stands out 1.7 : 1 with the Balanced level, 2.5 : 1 with Bright, 4.7 : 1 today. It keeps its shape, place and hue, and it is faint. For a shader whose whole picture is a few bright things (Touch, Shapes, Trails), that is a real loss of what the user is watching.
- The band is the one treatment that leaves the spots as they are, because it darkens only behind text; it pays for that on bright pictures, and was not chosen.
- An adaptive level would fail here in the other direction: a frame whose bright part is under 5 % counts as dark, gets no black, and the text on the spots is unreadable.

**Brightness that keeps changing** (a whole frame going from black to white).

- Each frame is treated alone, so there is nothing that could lag, pump or overshoot. The UI does not change at all.
- The picture's swing is squeezed from 0…255 into 0…51: a flashing shader flashes less behind the code, and comments stay between 8.1 and 4.8.
- Today the swing is halved (0…119) and the text is lost at the top of it (1.7).
- An adaptive level would be right on paper for a flat frame, but it can know a frame's brightness only some frames later, so its black arrives after the flash and stays after it.

## 6. The style guide

For direction C. **The order of judgement is readable, familiar, quiet.** Concretely: every text has at least 4.5 : 1 and nothing that has to be read is under 14 sp; where the library has a component, it is used with its own shape, size and behaviour and only its colours come from us; small, dim or hairline is allowed only for what need not be read to use the screen.

Names in `code style` are the resource or attribute an implementer uses. Material attribute names are from memory of Material Components 1.14 and must be confirmed against the library when the theme is written.

### 6.1 Colour roles

All fixed, in `values/colors.xml`, mapped once in the theme (`Theme.Material3.Dark.NoActionBar` as parent). Layouts and code use the Material attribute, never the `se_` colour and never a literal.

| Resource | Value | Feeds | Used for |
| --- | --- | --- | --- |
| `se_surface` | `#121316` | `colorSurface`, `android:colorBackground`, `colorSurfaceContainerLowest` | Screens without a picture |
| `se_surface_1` | `#1A1B20` | `colorSurfaceContainerLow`, `colorSurfaceContainer` | Sheets, the conflict bar |
| `se_surface_2` | `#1F2126` | `colorSurfaceContainerHigh` | Menus, dialogs, the selection toolbar |
| `se_surface_3` | `#24262C` | `colorSurfaceContainerHighest` | Switch tracks, the Tab key, snackbar |
| `se_on_surface` | `#ECEDEF` | `colorOnSurface` | Text and icons (15.9 : 1 on `se_surface`) |
| `se_on_surface_dim` | `#A9ADB5` | `colorOnSurfaceVariant`, `colorSecondary` | Second lines, section headings, marks, icons at rest (8.3 : 1) |
| `se_outline` | `#6B7078` | `colorOutline` | Field and control borders (3.7 : 1) |
| `se_line` | `#17FFFFFF` | `colorOutlineVariant` | Hairlines: 9 % white |
| `se_accent` | `#6CB6FF` | `colorPrimary`, `colorControlActivated` | The default accent. The one filled button of a screen, section headings, the running mark, focus, text buttons, switches, the selected tab (8.6 : 1) |
| `se_on_accent` | `#04243D` | `colorOnPrimary` | Text on the accent (7.4 : 1) |
| `se_error` | `#FF7B8A` | `colorError` | Errors, destructive entries (7.5 : 1) |
| `se_warning` | `#FFC857` | – | Warnings, conflicts (12.1 : 1) |
| `se_accent_container` | `#244C72` | `colorPrimaryContainer`, `colorSecondaryContainer` | What is selected: a row, a segment, a chip; tonal buttons. 1.9 : 1 against the panel, so the fill is seen as a shape, not only as a tint |
| `se_on_accent_container` | `#D6EAFF` | `colorOnPrimaryContainer`, `colorOnSecondaryContainer` | All text and marks on it (7.3 : 1) |

**The accent is a setting.** By default it is the fixed blue above. The setting "Use device colours" (`use_device_colours`, under a new category Appearance, off by default, shown only on Android 12 and later, where the device has such colours) lets it follow the wallpaper (`sheet-accent-setting.png`).

![accent](../show/style/sheet-accent-setting.png)

| Follows the device when the setting is on | Stays fixed, always |
| --- | --- |
| `colorPrimary`, `colorControlActivated` ← `@android:color/system_accent1_200` | The four surfaces, `colorOnSurface`, `colorOnSurfaceVariant`, `colorOutline`, `colorOutlineVariant` |
| `colorOnPrimary` ← `system_accent1_800` | `colorError`, `se_warning` |
| `colorPrimaryContainer`, `colorSecondaryContainer` ← `system_accent1_700` | `se_level_bar` and the limit |
| `colorOnPrimaryContainer`, `colorOnSecondaryContainer` ← `system_accent1_100` | Every `code_*` colour, the caret, `code_selection`, the line marks |

What has to hold for the two cases to stay one design:

1. **Nothing names `se_accent`.** Layouts and code use the Material attributes only; then one overlay moves everything at once. The same goes for the container colours.
2. **The accent is never the only sign of a state** (6.6). A device accent can be close to the error red, the warning amber or a syntax colour; icons and shapes carry the meaning.
3. **Code does not change.** `code_keyword` stays `#6CB6FF` whatever the accent is; accent and keyword colour are equal only by default.
4. **Neutral stays neutral.** The device's tinted surfaces are not taken, because a surface in the device's colour would be a third colour source over the picture.

How it is applied: one switch, at activity creation. The theme is always `Theme.ShaderEditor`; a helper called first in every activity's `onCreate` adds the overlay `ThemeOverlay.ShaderEditor.DeviceAccent` (defined in `values-v31`) with `getTheme().applyStyle(…, true)` when the setting is on, and changing the setting recreates the activity. Not `DynamicColors.applyToActivitiesIfAvailable`: that replaces every role, surfaces and error included. The tone numbers are from memory of how the library builds its dark dynamic scheme and have to be checked; that tones 700 and 100 keep at least 4.5 : 1 for every wallpaper is by the palette's construction, not measured.

**Menus take their surface explicitly.** The library's menu default, `colorSurfaceContainer`, is `se_surface_1`, the drawer panel's own colour, so a menu opened over the drawer would not stand out. Every menu therefore draws `colorSurfaceContainerHigh` itself: the main menu through `background_menu`; the drawer's row and order menus and every spinner list through `background_popup` (the theme's `popupMenuBackground` and the spinner style), which adds a hairline in `colorOutlineVariant`. Both have 16 dp corners.

Fixed colours exist because of the picture: a surface that takes the device's colour would be a third colour source over it. `colorTertiary` and its container are set to the neutral values too, so that no component falls back to a tint. Pressed and focused states are the library's ripple (`?selectableItemBackground`), never a colour of ours.

### 6.2 Translucency levels

**The rule: a surface is translucent only when the picture is directly behind it and is being looked at. That is the editor and its bars, nothing else. Each is set so that its own dimmest text keeps 4.5 : 1 over a white picture.**

| Level | What it is | Where | Over white, computed |
| --- | --- | --- | --- |
| **Code** | No colour. The engine limits the picture: `limit` uniform, ceiling from the setting, default `@integer/code_picture_ceiling` = 51 | The editor | Comments 4.8, text 12.6 |
| **Bar** | `se_level_bar` = `#CC121316` (80 % of `se_surface`) | Toolbar with the status bar, the extra-keys row, the gesture or navigation bar strip | Becomes `#414245`; text 8.6, dim text 4.5 |
| **Solid** | `se_surface` … `_3`, opaque | The drawer, sheets, menus, dialogs, the conflict bar, every screen without a picture | – |

Behind the drawer, a sheet or a dialog: the library's scrim. `primary_translucent`, `background_editor`, `background_drawer`, `background_progress` and `background_item_pressed` go.

### 6.3 Type

The UI font is the system's (Roboto). Seven Material text appearances and no others; sizes in layouts are not allowed. Nothing that has to be read is under 14 sp; 12 sp is for the toolbar's second line and for badges.

| Appearance | Size | Used for |
| --- | --- | --- |
| `?textAppearanceTitleLarge` | 22 sp | Toolbar titles of every screen but the main one, sheet titles |
| `?textAppearanceTitleMedium` | 16 sp medium | The main toolbar's title and the drawer header, where width is short; empty-state titles |
| `?textAppearanceTitleSmall` | 14 sp medium | Section headings, in `colorPrimary`, as Android's settings do |
| `?textAppearanceBodyLarge` | 16 sp | The first line of every row, menu entries, field text |
| `?textAppearanceBodyMedium` | 14 sp | Second lines, dialog and sheet text, snackbar, messages |
| `?textAppearanceLabelLarge` | 14 sp medium | Buttons, tabs, the frame-rate and quality control (with tabular figures) |
| `?textAppearanceBodySmall` | 12 sp | The toolbar's and the drawer header's second line |

Dialog titles keep the library's own `?textAppearanceHeadlineSmall` (24 sp); it is the one place an eighth appearance is used, because the dialog is the library's and is not restyled.

**The code font** is the one the user chose in Settings (`TextAppearance.ShaderEditor.Code`, falling back to `monospace`). It is used for what is GLSL, anywhere in the app, at 14 sp or more outside the editor: the editor and gutter, the completion strip and extra keys, uniform names and their types (16 sp), GL constants, source quoted in a message. Numbers the app reports (frame rate, sizes, settings values) and file names are in the UI font: a monospace at small sizes is harder to read than Roboto, and they are not code.

### 6.4 Size, spacing, shape

Spacing is 4, 8, 12, 16 or 24 dp; nothing else.

| Resource | Value | For |
| --- | --- | --- |
| `se_margin_screen` | 16 dp | Left and right of every row and sheet content (24 dp inside sheets and dialogs: `se_margin_sheet`) |
| `se_row_height` / `_two_line` / `_picture` | 48 / 56 / 72 dp | One line; two lines or a 40 dp picture; a 56 dp picture |
| `se_tap_target` | 48 dp | Minimum for anything tappable; 44 dp only for keys in the extra-keys row (`se_key_height`), where a 48 dp row would cost a line of code |
| `se_icon` / `se_mark` | 24 / 18 dp | Icons in a 48 dp target, and the chevron at the end of a row that opens a folder; the wallpaper mark at the end of a row |
| `navigation_state_mark` | 16 dp | The running and read-only marks at the end of a row and in the drawer header (6.6) |
| `navigation_mark_spacing` | 8 dp | Between the text of a row and each mark after it |
| `se_thumbnail` / `_large` | 40 / 56 dp | Shader pictures in lists; in Examples. Never cut to a circle: the corners are part of the picture |
| `se_drawer_max_width` | 328 dp | The drawer: the smaller of this and the screen minus 56 dp |
| `se_hairline` | 1 dp | Dividers, in `colorOutlineVariant`. Between groups, not between rows |

Settings use the library's preference rows as they are, title over current value, with `iconSpaceReserved` off. The frame-rate and quality control is a 36 dp filled chip (14 % white on the bar) with a dropdown arrow. For a shader in the database the frame rate is in the toolbar's second line and the chip shows the quality; for a file of a folder the frame rate stands in the chip ("22 fps · 1/1", NAVIGATION D3) only while the title then keeps the width it needs, at most 108 dp (`toolbar_title_min`: "common.glsl" and the unsaved mark). Otherwise the chip shows the quality alone until there is room: the title, the unsaved mark and the error button come first.

Toolbars and menus are never a fixed size: the toolbar is at least `?actionBarSize`, which is 64 dp in Material 3 (the mock-ups assumed 56), and grows with the text; a menu is between 200 and 280 dp wide.

Shape, as recommended in S6 and open until it is decided: **anything up to 56 dp tall has 8 dp corners (`se_corner`); a larger thing that floats over the screen has 16 dp (`se_corner_container`); whatever touches a screen edge is square on that edge.** So: buttons, the chip, segments, fields, keys, the selected row, pictures and the snackbar 8 dp; menus, dialogs and the top of a sheet 16 dp; bars, the code, the drawer and the bottom of a sheet square. Round only where it is a state or a platform part: switches, the count badge, text handles.

**Buttons in use.** Material Components 1.14 makes every button a pill by default, so each button the app uses has a style of its own that takes `shapeAppearanceCornerSmall` (8 dp), and the theme makes those the defaults: `Widget.ShaderEditor.Button` (filled: the one main action of a screen, such as the drawer's New with its icon in `.Icon`), `.TonalButton` (a second way out beside it, as in the drawer's empty state), `.OutlinedButton` and `.TextButton`, and `.TextButton.Dialog` and `.TextButton.Dialog.Flush` for the buttons of dialogs. Outside the library's buttons: `BarButton` for the extra keys and completions (a text button with 8 dp corners in `colorOnSurface`, at least 44 dp), `BarButton.Tab` for the Tab key (filled with `colorSurfaceContainerHighest`, 40 dp high), `IconButton` for the toolbar's icons (transparent, with a round ripple), `MenuItem` for the rows of the main menu (square, the menu's full width, 48 dp) and `FAB` for the floating buttons of the texture screens (8 dp).

### 6.5 Icons

One family: **Material Symbols, Outlined, weight 400, grade 0, 24 dp optical size, not filled**, as vector drawables named `ic_<symbol name>` with `android:tint="?attr/colorControlNormal"`. The existing 24 already are. One deliberate exception: the running mark is a filled triangle, because filled means "happening now".

Replace: `ic_delete_forever` → `ic_delete` (an ordinary delete) · `ic_quick_reference_all` → `ic_book_2` (Examples) · `ic_forum` → `ic_help` (FAQ) · `ic_file_copy` → `ic_content_copy`. Add for the drawer and sheets, from the same family: `folder`, `folder_open`, `draft` (a file), `image`, `grid_view` (the library), `lock`, `chevron_right`, `arrow_upward`, `edit`, `check`, `sync_problem`, `arrow_drop_down`. The navigation mock-ups and the first implementation use filled pencil, bin and book: those are the wrong family.

An icon alone is a button only in a toolbar and with a `contentDescription`. Anywhere else an action has a word: the floating buttons become labelled ("Insert", "Save", "Crop").

### 6.6 States

Never colour alone: every state has an icon or a shape, and a spoken text.

| State | Shown as | Spoken |
| --- | --- | --- |
| Selected | Filled with `colorSecondaryContainer`, `se_corner`, inset 8 dp from the edges; first line in medium weight; second line and marks in `colorOnSecondaryContainer`. A selected segment or chip is the library's: the same fill and a check mark | selected |
| Running | Filled triangle, 16 dp, `colorPrimary`, at the end of the row; in the toolbar's second line before the file name | running |
| Unsaved | A dot, 4 dp, the size of a "•" in the title, in the title's own colour, 8 dp after the name in the toolbar, in a slot of its own; at the end of a row in lists. After the name, because the name must not move when the dot comes or goes. In its own slot, because a character added to the title would be cut off by the ellipsis of a long name: the slot makes the name shorten before the dot | unsaved changes (the title's spoken text) |
| Error | `ic_error` and a count in `colorError`; in code 6.9 | N errors |
| Warning | `ic_warning` and a count in `se_warning` | N warnings |
| Changed outside | `ic_sync_problem` in `se_warning` | changed outside |
| Read-only | `ic_lock`, 16 dp, dim; the word "Read-only" where there is a second line | read-only |
| Disabled | Content at 38 %, nothing else | (the platform's) |
| Pressed, focused | The library's ripple and focus ring | – |

### 6.7 Motion

The library's defaults and nothing bespoke. Changes inside a surface (a drawer level sliding, a fade) take `motionDurationShort4` (200 ms); sheets and dialogs keep the component's own. The code's opacity and the picture's limit follow the drawer's slide directly. On the eye button the picture is limited at once when the code appears, because code over a bright picture cannot be read, and the limit lifts over 150 ms when the code is hidden. Nothing loops except the caret. Nothing moves because a key was pressed. With the system's animations off, everything is instant.

### 6.8 Wording

1. **Sentence case** everywhere: "Show extra keys", "Sensor delay", "Insert code".
2. **An action is a verb, without its object when the object is in view**: "Save", "Delete", "Share" in the toolbar menu. NAVIGATION 6.5 already lists the labels this way.
3. **A dialog names the thing and its button is the verb**: "Delete sketch.frag?" · Cancel · Delete. Never "OK", never "Are you sure".
4. **A label is not a question and not a sentence.** "Add uniform to source", not "Add uniform sampler2D statement to source?". No full stop after a single sentence or a summary.
5. **A summary says something the title does not**, or is absent.
6. **One word per thing**: Settings (not Preferences), Examples, shader, folder, picture.
7. **No apology, no "please", no Android version names.**
8. **Numbers have a space before the unit** ("1.0 s", "12 sp", "256 × 256") and are in the code font; the ellipsis is "…".
9. **Every icon-only button has a spoken name.**
10. Tabs are words: "Built-in", "Textures", "Cube maps".

Every changed string changes in all five languages (AGENTS 15); a rule about English capitalisation is applied in a translation only where that language has the same rule.

### 6.9 System bars and insets

- Edge to edge on every screen, with the current API (`EdgeToEdge.enable`), not the deprecated flags in `SystemBarMetrics`.
- The status bar area belongs to the toolbar: one view, one level. Status bar icons are always light.
- The gesture bar has no background of its own. With three-button navigation, or under the extra keys, the strip is the bar level.
- On screens without a picture the bars are `colorSurface` with a hairline under the toolbar.
- The drawer, as recommended in S8, draws behind both system bars: its header is padded by the status-bar inset, its bottom bar by the navigation inset.
- The keyboard moves the extra-keys row; nothing else resizes.
- A display cutout: the window draws into it (`EdgeToEdge`'s default). The bars' backgrounds and content run through it and keep out of the system bars only (`SystemBarMetrics`): the status-bar inset pads the toolbar, the left, right and bottom insets of the system bars and the keyboard pad the rest. A cutout on a short edge stays beside the toolbar in landscape, so nothing of the bars is under it. What is read in a column keeps out of the cutout as well: the drawer pads its content by the system bars and the cutout (`NavigationDrawer`), and the new code view starts its gutter, text and caret after both (`CodeView`). The old editor does not; in landscape its line numbers can lie under a cutout.
- The app declares itself as always in night mode (`AppCompatDelegate.MODE_NIGHT_YES`), so that what the platform draws for it (the selection toolbar, handles) is dark too. Not verified.

### 6.10 The code editor

Binding for the new editor view (EDITOR 4.4); the old widget gets the palette and the limit only.

| Part | Rule | Resource |
| --- | --- | --- |
| Text | The user's font and size; 8 dp padding; palette of 5.4 | `code_*` |
| Gutter | A strip that stays in place; numbers right-aligned in the code font; 8 dp to the text. No background: text is clipped at its edge | `code_line_number` |
| Current line | Its number in `colorOnSurface`. No band | – |
| Caret | 2 dp wide, line height, white, 500 ms on and off | `code_caret` `#FFFFFF` |
| Selection | A dark blue that keeps text readable | `code_selection` `#B31F4E79` |
| Handles | The platform's drawables, tinted | `colorPrimary` |
| Composing text | A 1 dp underline in `colorOnSurface` | – |
| Error line | A dark tint over the whole line, a 3 dp bar at the gutter's leading edge and the line number in `colorError`. The bar and the number are the line's mark: a touch target 48 dp tall, centred on the line, and as wide as the gutter (the numbers and their padding), so that a tap on the text always places the caret, that opens and closes the line's message row. With line numbers off there is no mark; the row still opens by itself | `code_mark_error` `#8C5C0A14`, `editor_mark_width` |
| Message row | Under one line with errors at a time, as part of the code: the line's messages in the compiler's order, each from a new line, `?textAppearanceBodyMedium` in `colorError` on `colorSurface`, aligned with the code, at most three lines, ending in an ellipsis when text is cut and "+N more" for errors left out. From the gutter's edge to the screen's right edge: 8 dp corners on the left (`se_corner`), square on the right where it touches the edge, 4 dp above and below the text. The mark's bar runs down beside it. Opening, closing or growing it never moves the caret's line | `se_corner`, `se_row_padding_vertical` |
| Warning line | The same in amber | `code_mark_warning` `#8C4A3000`, `se_warning` |
| Selection toolbar | The platform's floating toolbar, dark (6.9) | – |
| Extra-keys row | Bar level, 44 dp, a hairline on top | `se_level_bar`, `se_key_height` |
| Tab key | 44 × 40 dp, `se_corner`, `ic_keyboard_tab` | `se_surface_3` |
| Keys and completions | Code font 14 sp, at least 44 dp wide, hairlines between completions, all in `colorOnSurface`; nothing is dimmed. The part already typed is regular, the part a tap inserts is bold, in the real bold face each bundled code font ships | – |

### 6.11 Which surface for what

| Need | Surface |
| --- | --- |
| A question with two answers, one field, or one list setting | Dialog: the library's Material dialog (`MaterialAlertDialogBuilder`) for every dialog, list settings included; `colorSurfaceContainerHigh`, 16 dp corners, behind it a dim of 0.6 (`se_dialog_dim`); text buttons at the end, Cancel before the verb. A field in it is the library's outlined field, 24 dp from the dialog's sides, with the keyboard up |
| A list to choose from, several fields, or something to look at | Bottom sheet: the library's modal sheet with its drag handle; `colorSurfaceContainerLow`, 16 dp corners at the top, square at the bottom edge, the dialogs' dim behind it; content 24 dp from the sides |
| Actions on one thing | Menu, anchored to it |
| Saved state | State, not a message: the unsaved dot (6.6) is there while the open shader has changes nobody saved and goes as soon as any save writes them (explicit, auto save, save on run) |
| The success of an explicit save | "Shader saved" for 2 s in the toolbar's second line, in place of the frame rate, which then returns. Auto save and save on run say nothing: the dot going is the signal. One owner of the second line decides what it shows: a notice until its time, otherwise the line of what is open, otherwise the frame rate |
| A failure, or a result with an action | Snackbar at the bottom, above the extra-keys row when it is shown, otherwise above the navigation bar and the keyboard; in a form, above the button that was pressed. `colorSurfaceInverse` (`se_surface_3`), 8 dp corners. An action only where it leads somewhere. Results of actions on other screens (Settings, the texture screens) are shown the same way |
| The compile error | In the new editor, the message row under the failing line (6.10); the list of all errors stays behind the toolbar's error button. Today's editor, and the new one while its code is hidden, keep the snackbar with a Details action over the toolbar, where upstream put it |
| Rule for the editor | Nothing floats over the line being edited. With the keyboard up a snackbar at the bottom covers the caret's line when the caret is on the last visible line, so the editor sends there only failures, which are rare and must be read, and the few results of menu actions that are not a save (wallpaper set, a link that cannot be opened) |
| Where no screen of the app is in front | A toast: the wallpaper service, filling the database on first start, and a screen that closes right after its message |
| Something wrong that stays wrong | In place: the error button with its count, a bar in the file, a line in the list |
| Nothing to show | An empty state: a title, one sentence, the one or two ways out |

### 6.12 The three new surfaces

- **Drawer**: section 7.
- **Project-mode sheets and bars** (`sheet-recommended-1-editor.png`, `-3-screens.png`): New (`c-08`), image (`c-19`) and the error list (`c-05`) are the library's bottom sheet with its drag handle, a `TitleLarge` title, 24 dp side margins, the library's outlined field and segmented button, text buttons and at most one filled button at the end. The conflict (`c-18`) is a solid bar on `se_surface_1` under the toolbar with `ic_sync_problem`, one `BodyMedium` sentence and three text buttons. The error list is grouped by file with `TitleSmall` headings; quoted source in the code font.
- **Code editor view**: 6.10.

## 7. The new drawer in the recommended style

![drawer](../show/style/sheet-recommended-2-drawer.png)

`c-11-drawer-library.png`, `c-12-drawer-folders.png`, `c-13-drawer-project.png` and `c-14-row-menu.png` (drawn with the recommended answers to S6 and S8) redraw `r-02`, `r-03`, `r-04` and `r-21`. Structure, content and gestures are unchanged, and so is more of the look than in my first draft: the pill-shaped buttons and the filled chip in the toolbar were right. Every difference:

| # | Mock-up as accepted | Redrawn |
| --- | --- | --- |
| 1 | Panel `rgba(15,16,20,.97)` over the code, from under the toolbar to the bottom | Solid `se_surface_1`, from the top edge to the bottom edge behind both system bars (S8); the library's scrim over everything beside it |
| 2 | Accent indigo `#B9C3FF` | `se_accent` |
| 3 | Selection: 17 % indigo over the panel, a pill open to the left | Solid `colorSecondaryContainer`, 8 dp corners, inset 8 dp on both sides (S6) |
| 4 | Rows 48, 56 and 44 dp; 15 and 12 sp | 48 and 56 dp; `BodyLarge` (16 sp) and `BodyMedium` (14 sp) |
| 5 | Picture to text 14 dp; picture corners 6 dp | 16 dp; 8 dp |
| 6 | Section headings 13 sp | `TitleSmall`, 14 sp, in the accent |
| 7 | Header 60 dp under the toolbar, 17 sp with the last path part bold; 44 dp buttons, 22 dp icons | Header in the toolbar's place and of its height (S8); `TitleMedium`, `BodySmall`; 48 dp buttons, 24 dp icons |
| 8 | Bottom bar: tonal "New", a pill | The filled accent button, 8 dp corners: it is the screen's one main action |
| 9 | Icons: classic filled pencil, bin, book, lock | Material Symbols outlined: `edit`, `delete`, `book_2`, `lock`, `draft`, `grid_view` |
| 10 | Marks in `#9DA1AD`; error `#FFB4AB`, warning `#FFD27A` | `colorOnSurfaceVariant`; `se_error`, `se_warning` |
| 11 | Toolbar chip 13 sp on 10 % white; title 18 sp regular; bar 53 % of `#222` | `LabelLarge` (14 sp) on 14 % white, 36 dp; `TitleMedium`; `se_level_bar` |
| 12 | Row menu: bold 14.5 sp labels, Delete like the rest | `BodyLarge`; Delete set apart and in `colorError` |
| 13 | Examples: "24 examples, read-only" | "20 examples" and the lock mark (the app bundles 20) |

**How opaque the open drawer is: solid** (decided). What is behind the drawer is code, the worst thing to have behind a list; a solid panel with the library's scrim is what every Android user knows and does not depend on the shader. Where it ends is S8.

**The accent**: the icon's blue by default, the device's colours as a setting (decided; 6.1).

**The first implementation** (`show/nav-1/`, three screenshots on the sheet). To change:

1. The code shows through the panel. Make the panel solid, and let it run from the top edge to the bottom edge (S8): today it stops under a translucent toolbar and above a translucent strip.
2. The selected row is a 17 % tint that is hard to find over a bright shader; make it the solid container colour (difference 3).
3. A grey square stands where a shader has no picture ("Plasma") → `ic_draft` in the 40 dp place.
4. Row menu: three icon styles in three rows (filled pencil, outlined copy, the "forever" bin) → one family; Delete set apart and in the error colour; the menu should not cover the row it belongs to.
5. Drawer menu: the check before the label → at the end of the row, as in the toolbar menu.
6. The header title is 20 sp bold → `TitleMedium`; the wallpaper mark is too faint → `colorOnSurfaceVariant`.
7. The toolbar still has the old spinner and the frame rate under the title (D3 of NAVIGATION is not built yet).

Good as built, keep: the 40 dp pictures, the two-line rows, the header's structure, the hairline above the bottom bar. The pill shapes of the selected row and of New depend on S6.

## 8. The path

Small pull requests, none of which changes what the app does. "Upstream" marks those that stand alone as polish for `markusfisch/ShaderEditor`.

**Tokens and theme first? No.** The theme is the one change the maintainer has to *agree* with. The first three need no agreement on any style, fix what a user would call a bug, and are each a few lines of XML or one shader line. After them the app has become visibly better three times and the case for the rest is made.

| # | Pull request | Touches | Visible in | Upstream |
| --- | --- | --- | --- | --- |
| P1 | **Wording.** Sentence case, verbs, "Settings", the delete dialog's title and button, no "Ice Cream Sandwich", spoken names for the three toolbar buttons | `strings.xml` in five languages, three `contentDescription`s | Menu, Settings, dialogs, Add uniform | Yes |
| P2 | **Text that cannot be seen.** The drawer's and the cube-map tab's empty text, the hard-coded `primary_text_dark` and `0x88888888` → theme attributes | 3 layouts, 2 Java lines | Empty lists | Yes |
| P3 | **Syntax colours.** The nine values of 5.4 behind the existing 21 names; line numbers | `syntax_colors.xml`, `colors.xml` | The editor: already better over every shader (worst case 2.0 instead of 1.3) | Yes |
| P4 | **The limit.** One line and one uniform in the presentation shader, set from code visibility; `background_editor` transparent | `ShaderRenderPipeline`, the renderer's setter, `EditorFragment` or `UIManager`, `colors.xml` | The editor | Yes, on its own merits: it is the fix for "I cannot read my code" |
| P5 | **The setting** "Picture behind code", three values | `preferences.xml`, `Preferences`, strings | Settings | With P4 |
| P6 | **Theme.** `se_` colours, fixed roles, the two corner values of S6, night mode declared; delete the colours nothing uses any more | `colors.xml`, `styles.xml` | Every screen at once: accent, surfaces, corners | Only after asking the maintainer. P6b makes the question easy: nothing is taken away |
| P6b | **The accent setting.** "Use device colours": the overlay in `values-v31`, the one call in each activity's `onCreate`, the preference | `styles.xml` (v31), `Preferences`, `preferences.xml`, the activities' base class | Settings; every screen when on | With P6. Upstream may prefer it on by default; that is one default value |
| P7 | **Bars.** One toolbar style at bar level, solid on screens without a picture; the type of 6.3; height grows with text; current edge-to-edge API | `toolbar.xml`, `toolbar_main.xml`, `navbar.xml`, `extra_keys.xml`, `SystemBarMetrics` | Every screen; fixes the cut frame rate at large text | The large-text fix alone, yes |
| P8 | **Menu and icons.** Width from content, theme surface and corner, the four icon replacements, the check at the end | `main_menu.xml`, `MainMenuManager`, drawables | Menu; fixes the cut labels | Yes |
| P9 | **Rows.** The text appearances of 6.3; row heights and margins; 40 and 56 dp pictures with corners; the code font for uniform names; delete the four `ListItem`/`Title` styles | `row_*.xml`, `styles.xml`, adapters | Examples, Add uniform, textures, the shader picker | After P6 |
| P10 | **Dialogs, sheets, messages.** The library's parts in place of hand-made ones: rename with a Material field; the error list with the library's handle, grouped, code font; a dark snackbar at the bottom; toasts → snackbars where a screen is in front | `dialog_rename_shader.xml`, `error_*.xml`, `ErrorListModal`, the 22 call sites (two pull requests) | Dialogs, errors, results | Pieces |
| P11 | **Settings.** No empty icon column, "Editor" split into three groups, summaries per 6.8. No key changes | `preferences.xml`, `PreferencesFragment` | Settings | Yes |
| P12 | **Keys.** Row height, key style, code font, typed part dimmed | `extra_keys.xml`, `extra_key_btn.xml`, `CompletionsAdapter` | Above the keyboard | After P6 |

The three new surfaces are not migration steps: the drawer (N4 onwards), the sheets and the editor view use the tokens of P6 from their first line. A full-height drawer (S8) is part of N4: the drawer layout becomes the outer container instead of a child under the toolbar.

**Deliberately left alone:**

- **No light theme.** Section 2.
- **The launcher icon, the splash, the wallpaper thumbnail.** They are the identity's source.
- **The texture, cube-map and crop screens** beyond what the theme gives them for free. Project mode replaces their flow (UX 4); redesigning spinners that are about to go is waste. `c-16` shows where they would land: the library's dropdown fields and a labelled button.
- **The full-screen preview activity**, the system's picker and permission dialogs.
- **Landscape and tablet layouts**: NAVIGATION N11.
- **The old drawer** (`drawer.xml`, `row_shader.xml`): deleted by N13, not restyled.
- **No blur, no custom UI font, no animation of our own.**
- **Preference keys, the database, behaviour.**

## 9. Open decisions for the owner

Two. Everything else is decided (last section).

### S6. Shape: one rule

![shapes](../show/style/sheet-shapes.png)

**What is in use.** Going through `c-01` to `c-19` as they were, and the first built drawer (`show/nav-1/`):

| Corner | In the recommended frames until now | In the first built drawer |
| --- | --- | --- |
| Square | Status bar and toolbar, code and gutter, extra-keys row, drawer panel, line marks, conflict bar, dividers | Toolbar, panel, header, bottom bar |
| 4 dp | Menu, outlined text field, snackbar | Row menu, drawer menu |
| 6 dp | Shader pictures | – |
| 8 dp | Tab key, the image in the image sheet | Shader pictures |
| 12 dp | Selected row, keys | – |
| 16 dp | The labelled action button on the texture screen | – |
| 28 dp | Top of a sheet, dialog | – |
| Pill | Filled, tonal and text buttons, the frame-rate chip, the segmented control, the selection toolbar | The selected row (open to the left), New |
| Round | Switches, the count badge, text handles | – |

Nine families. Five of them (4, 8, 16, 28, pill) are the library's own defaults, which grade the corner by the size and kind of the component; I added 6 and 12. As a system that is defensible, on one screen next to square bars and code it reads as chance, which is what the owner saw. No option below keeps it.

Common to all three: **what touches a screen edge is square on that edge** (bars, the code, the drawer, the bottom of a sheet), and switches, the count badge and the text handles stay round, because they are states and platform parts.

- **1 (recommended). One radius.** *Anything up to 56 dp tall has 8 dp corners; a larger thing that floats over the screen has 16 dp.* An implementer with a component I never drew measures its height and asks whether it floats. Two values (`se_corner`, `se_corner_container`). Against the stock components: the theme sets the five corner attributes (the three smaller to 8 dp, the two larger to 16 dp), which moves fields, cards, menus, sheets and dialogs; the components whose default is "full" need one style each: the four button styles, the segmented group, the floating button; the snackbar goes from 4 to 8 dp. About ten style entries in one file, no component replaced. Which attribute each component reads is from memory and has to be confirmed once per component.
- **2. Pills and large curves.** *What you press is a pill; what contains has 28 dp.* The least work: buttons, segments and navigation items are pills by default; fields, keys and menus are brought into line. It costs in the result: a 28 dp menu wastes its corners on 48 dp rows, a field as a pill is unusual, and for pictures the rule means circles, which cut the corners off every shader picture. Next to square code and bars the contrast the owner noticed remains, only tidier.
- **3. Square.** *Nothing is rounded except what floats over the screen (menu, sheet, dialog), which has 12 dp.* The same mechanics as 1 with other numbers. All of one piece with the code; the selected row as a full-width band is the cleanest of the three. But a square filled rectangle on a dark flat surface says "button" less clearly than a rounded one, and no current Android app looks like this: it trades familiar for particular.

Why 1: it is the only one of the three that removes the mixture without creating a new oddity (2: circles and bloated menus; 3: unfamiliar buttons). 8 dp is also what the app already uses where it chose for itself: the keys (`BarButton`, 8 dp) and the menu (10 dp).

### S8. Where the drawer ends

![edges](../show/style/sheet-drawer-edges.png)

The owner found it wrong that the solid drawer stops abruptly under the toolbar and above the navigation bar. It does because the drawer layout is a child *under* the toolbar in `activity_main.xml`, and the strip over the gesture bar is a separate translucent view. (`TouchThruDrawerLayout` is not the reason: that class only lets touches through to the shader while the code is hidden, wherever the drawer is, and stays as it is.)

- **a (recommended). A standard full-height modal drawer.** The panel runs from the top edge to the bottom edge, behind the status bar and the navigation bar, with its own insets; its header sits in the toolbar's place and has the toolbar's height, so the list starts where the code starts; the scrim covers everything beside it, the rest of the toolbar included.
  - *The toolbar while open:* its title and menu button are covered. What is edited is the selected row, what runs has the running mark, and both are in view because the drawer opens on the folder of the edited file (NAVIGATION 6.3). Closing is by tapping beside it, swiping it away, or Back, which are the three ways NAVIGATION lists; the menu button was never one of them on paper, though the first build's toggle arrow is.
  - *One header instead of two:* 60 dp more list, one row.
  - *From 840 dp:* the docked panel is the same full-height column and the toolbar starts beside it (bottom of the sheet). One geometry for both.
  - *Edge to edge and gestures:* the header is padded by the status-bar inset and, in landscape, by a display cutout; the bottom bar by the navigation inset. The left-edge swipe remains the system's Back, as NAVIGATION already notes.
  - *Cost:* the drawer layout becomes the outer container of the main screen. That is part of building the new drawer (N4), not of restyling the old one.
- **b. Below the toolbar, one surface with the bars.** While the drawer is open, toolbar and navigation strip turn solid in the drawer's colour. The title and the menu button stay visible. Two headers stack (124 dp before the first row); the bars change their look as the drawer slides; docked, the toolbar would be solid over the list and translucent over the code, a seam in the bar.
- **c. A floating panel.** Inset 8 dp, 16 dp corners, so it has no edge to meet. 16 dp narrower and a row shorter; unfamiliar; and it is a second idea of what a panel is, next to sheets that do touch the edge.

Why a: it is the drawer every Android user knows, it removes both seams instead of hiding them, it gives the list more room, and it is the same shape docked. I looked for something better than the standard and did not find it; c is the best of what I tried and loses on familiarity and space.

## 10. Not captured, not verified

- **Not captured:** the crop screen and the screens after picking an image (they need Android's picker, which I may not drive); the wallpaper picker and the full-screen preview (older captures exist under `show/sweep-3`, `show/camera`); landscape (an older capture exists, `show/X2`). The soft keyboard does not appear on this emulator, so audit 23 and 24 are earlier captures.
- **Five of the eight measured frames are from today's emulator**: GLES 3, Clock, Texture, Electric Fade and Swirl. Three are earlier captures of the same build family, one of which (the white corner) is a user's shader, not a bundled example. Fourteen of the twenty examples were not measured; the limit's guarantee does not depend on them.
- **The limit was simulated on screenshots, not compiled.** The arithmetic is on encoded values, as the screen blends today; I checked that model against one real screenshot. That the presentation shader is the only place the picture reaches the screen is read from the code, not tested.
- **WCAG contrast is a model.** It ignores hue, motion and the thinness of strokes. Nobody has read code over the limited picture on a phone.
- **The fixed scheme was never on a device.** Tones were chosen by eye on a desktop screen.
- **Material attribute names** in 6.1, 6.3 and 6.4 are from memory of the library; that every component takes the theme's shape and container colours as I assume is not verified.
- **`MODE_NIGHT_YES` making the platform's selection toolbar dark** is an expectation, not a test.
- **The full-height drawer (S8 a)** is drawn, not built: that the drawer layout can become the outer container without disturbing the touch-through to the shader, and how the status-bar inset reaches the header, is not tried.
- **The device-accent overlay** (6.1): the `system_accent1_*` tone numbers are from memory; no device's colours were read, the three examples are colours I chose.
- **The corner attributes** (S6): which theme attribute each Material component reads for its shape is from memory.
- **The Touch and Shapes frames** in `sheet-reading-3-questions.png` are earlier captures; the frames of changing brightness are synthetic flat colours, not a running shader.
- **Colour-vision simulation** is one published model at full severity, computed, not checked with people.
- **TalkBack** was not run; the three unnamed buttons are read from the accessibility tree.
- **Left on the emulator** `ShaderEditor_Examples_API_36`: my debug build of `integration/project-mode` in place of the upstream build that was there, with nine example copies in its shader list. Font scale is back at 1.0.

## 11. Files

All under `.foreman/show/style/`.

- `sheet-*.png` (15) and `c-*.png` (20), each with its `.html`. The `c-*` frames are drawn with the recommended answers to S6 and S8.
- `shots/`: the real screenshots. `a*` (28) and `r-<example>-code` / `-picture` (20) were captured today; `x-*`, `n-*` and `r-NN-*` are copies from other folders of `show/`.
- `assets/frames/`: the pictures behind the code, cropped from `r-*-picture.png`, and their limited versions (`-l4d`, `-l33`, `-l1a`).
- `data/measure.mjs` (contrast), `data/palette.mjs` (colour-vision check), `data/frames.mjs` (applies the limit), `data/questions.mjs` (the two cases of 5.5), `data/report.txt` and `data/contrast.json` (results).
- `lib.mjs` (parts), `screens.mjs` (screens per direction), `sheets.mjs` and `build.mjs` (pages), `assets/style.css` (the three directions as CSS variables).
- Rebuild: `node data/measure.mjs > data/report.txt; node build.mjs && ./render.sh`. Rendering uses Playwright's Chromium from the npx cache.

The mock-ups use the app's own material: `jetbrains_mono.ttf`, the thumbnails from `drawable-nodpi`, the bundled sources from `res/raw` coloured by a small lexer, the app's vector icons, and Material Symbols outlined (fetched from Google Fonts) for icons the app does not have yet. The keyboard is a grey placeholder.

## Decisions of 2026-11-12

The owner decided every question; where he wrote text it is quoted. S6 and S8 came out of his remarks on S0 and S2 and were decided on 2026-11-13. Section 9 is kept as the record of the options.

| # | Decision | Taken | Not taken |
| --- | --- | --- | --- |
| S0 | The direction | C, Instrument. "Instrument, but it currently is inconsistent — some UI is sharp, some is rounded and some is pill shaped. This needs to be consistent in some form." → S6 | A, Material by the book; B, glass over the shader |
| S1 | The accent | The icon's blue by default, with a setting to use the device's colours where they exist. "What about making it configurable, blue by default but allowing dynamic color?" | Dynamic only; white |
| S2 | How opaque the open drawer is | Solid. "solid, but also it looks weird the way the drawer is opaque but suddenly/abruptly stops at the navbar and top navigation" → S8 | 90 % over the picture with the code hidden; 97 % over the code |
| S3 | How code is made readable | The brightness limit in the presentation step | A dark band behind each line; darker black over everything; lighter colours only |
| S4 | The default level | Balanced (nothing above `#33`), with a setting of three levels | Bright as default; no setting |
| S5 | The syntax palette | Seven colours and two greys (5.4) | Lightening only the values that fail; leaving it |
| S6 | Shape | One radius: anything up to 56 dp tall has 8 dp corners; a larger thing that floats has 16 dp; whatever touches a screen edge is square on that edge | Pills and large curves; square |
| S7 | What goes upstream first | Small pull requests first; the theme only after asking the maintainer | Everything as one later series; asking about the whole direction first |
| S8 | Where the drawer ends | Full height: a standard modal drawer behind both system bars; its header takes the toolbar's place; the same column when docked from 840 dp | One surface with the bars; a floating panel |

`sheet-decided.png` keeps the pictures S1 and S2 were chosen from; the pictures for S3 and S4 are the three `sheet-reading-*.png`, for S5 `sheet-palette.png`, for S0 the three `sheet-direction-*.png`.

This guide is binding for the navigation drawer, the sheets and bars of project mode and the code editor view, and it wins over the placeholder look of the mock-ups in NAVIGATION.md. Where NAVIGATION.md says the drawer sits under the toolbar and is 97 % opaque, this guide's full-height solid drawer applies. The pictures this document refers to are not part of the repository. Nothing here has run on a device except where a later section says so.

## Built and measured (2026-11-13)

The palette, the limit and its setting are built. On an emulator, over five bundled examples (GLES 3, Clock, Texture, Trails, Electric Fade), the brightest pixel behind code was exactly the ceiling of each level and every measured contrast matched the computed one. The limit is one uniform, `c /= 1 + s·max(c)`, the same curve as 5.2's when fully on; the levels are constants in `opengl/PictureLimit`, not resources, because the engine reads no resources. The picture is untouched with the code hidden, in the full-screen preview, in the wallpaper and in thumbnails. Not verified: the 150 ms ease on the eye button, and a GLES 2 device.
