## 10. Round 2: tuning

The owner chose "code steps aside, full picture" and asked: "what about precise numbers, colors and non-color values? E.g. I want exactly 0.1 steps, I want large numbers way above/under 0…1 range or positions and not color data?" Round 1 drew a 0..1 slider; that was the wrong starting point. This section starts from the values shaders really have. Pictures: `.foreman/show/editing-2/`.

### 10.1 The design in short

- **One bar for every number.** Opened as before: the strip offers `⇆ 0.3` when the caret was put on a number by a tap. The code and the keyboard step aside, the picture shows as it is (his choice).
- **No range.** The bar is a ruler with one tick per step and no ends, plus − and +. How fast it moves is the step, not a range.
- **The step is chosen, never guessed silently.** It starts at a tenth of the number's size (0.3 → 0.01, 300.0 → 10, 1e-3 → 1e-4, a loop count 3 → 1). The step chip offers six steps around it (for 0.3: 0.001, 0.01, 0.05, 0.1, 0.5, 1). Each tick, each − or + adds exactly one step.
- **Typing is one tap:** the number becomes a field with the number pad; Enter takes it and tuning goes on.
- **Written back once, at Done:** decimal arithmetic (never `0.30000001`), the literal's own style kept, an int stays an int. One undo step.
- **Live at the frame rate:** while tuning, the literal is replaced by a temporary uniform, so a change is a new uniform value, not a compile. Today a change waits at least 0.5 s, compiles, and restarts the picture's clock (10.6).
- **Vectors:** the parts side by side (`x 0.0 · y 1.0 · z -3.0`), one tuned at a time.
- **A colour only when you say so:** a vec3 or vec4 opens as numbers; a Colour button (with the swatch it would be) switches to R, G, B.

![One bar for every number](../show/editing-2/sheet-1-numbers.png)

### 10.2 The kinds of value, and the control for each

| Kind | In the bundled code | Control | First step | What is special |
| --- | --- | --- | --- | --- |
| Float in 0..1 | Shapes 31, the orbit `0.3` | Ruler, − +, step chip | 0.01 | Nothing: 0..1 is not assumed, the ruler goes on past 1 and below 0 |
| Float far outside 0..1 | Meters 41 `960.0`; Horizon 18 `41231.7`; a floor at `-45.0`; `1e-3` | The same | 10, 1000, 1, 1e-4 | The step follows the size, so 960.0 does not crawl; a negative number keeps its sign (10.4) |
| Int | Blobs 27, loop count `3` | The same; steps 1, 5, 10, 50, 100 | 1 (a tenth of the size above 20: 256 → 10) | Never a point. `u` stays and stops at 0. In GLSL ES 1.00 a loop count must be a constant, so it is built each time the finger rests (10.6) |
| Exact steps (0.1, 0.05, 1, 10) | any | The step chip | — | Each step adds exactly that much: 0.37 + 0.1 is 0.47, not 0.4 |
| vec2 or vec3 position or direction | Blobs 37 `vec2(0.0, -0.38)`; a camera `vec3(0.0, 1.0, -3.0)` | The parts side by side; the ruler tunes the selected one | per part, by its size | Never offered as a colour. A direction is not normalised for you |
| vec3 or vec4 colour | Shapes 44 `vec3(1.0, 0.7, 0.3)` | Opens as numbers; Colour switches to R, G, B (A) from 0 to 1 | 0.01 | Above 1 (HDR) the number turns amber and is kept; back to numbers for the ruler |
| Angle | inside `radians(30.0)` | Ruler in degrees, steps 1, 5, 15, 45, 90 | 15 | Only there is it certainly an angle. Elsewhere `1.5707963` is a number like any other |

How the app knows a kind: an int, a float, a vector and its parts are certain from the tokens (a number token, a constructor `vec2(`…`)` around number literals, a `u` or a missing point). An angle is certain only inside `radians(`. A colour is never certain: `vec3(0.0, 0.15, 0.3)` in Blobs 47 is the phase of `palette()`, all inside 0..1 and no colour (`t3-auto`). So the rule is **numbers first; Colour on request**, and the choice is remembered for that literal while the file is open.

![An int, an angle, positions](../show/editing-2/sheet-2-kinds.png)

![Colour only on request](../show/editing-2/sheet-3-colour.png)

### 10.3 Range, step, typing

- **Range: none.** A range needs two more numbers before anything moves, and any default is wrong for 960.0 or -45.0. The ruler has no ends. Bounds exist only where the type has them: an unsigned int stops at 0. In colour mode the sliders are 0..1 because that is a colour's natural range; a value above 1 stays and is shown in amber.
- **Step at the start:** a tenth of the number's size, 10^(⌊log10 |v|⌋ − 1). For 0 the place of the last written digit (`0.0` → 0.1, `0` → 1). For ints 1 below 20. In degrees 15.
- **Changing the step:** the step chip opens six choices, a tenth of the start step to a hundred times it (for 0.3: 0.001, 0.01, 0.05, 0.1, 0.5, 1), and **Other…** to type one. The chosen step stays for that literal while the file is open.
- **Exactly 0.1 per step:** choose 0.1. A tick, − or + adds exactly 0.1, in decimal arithmetic. A fling runs on and stops on a step. Each tick gives a light haptic tick.
- **Typing an exact number:** tap the number. It becomes a field with the number pad (with − and e); Enter takes it and tuning goes on with the same step. What you type is written as you typed it.

### 10.4 What is written back

Once, at Done, into the text; one undo step takes it back (`t5-writeback`). The rules:

- **Digits:** the value is computed in decimal, never in float. It has as many decimals as the step or the literal, whichever has more: `0.3` + 0.01 × 4 = `0.34`; `0.30` + 0.1 = `0.40`.
- **The literal's own style:** `1.` stays without digits after the point, `.5` without its leading zero, `1e-3` in exponent form (`1.3e-3`), `0x1F` in hex (`0x20`).
- **Type:** a float keeps its point (`1.0` − 1 is `0.0`, never `0`, which would be an int); an int never gets one; `u` stays.
- **Sign:** a minus directly in front of the literal (after `(`, `,`, `=`, `?`, `:` or at the start) belongs to the value, so `-45.0` + 50 is `5.0` and the minus goes. After a binary operator, a negative value gets parentheses: `a - 5.0` − 10 is `a - (-5.0)`, never `a --5.0`.
- **Only the tuned digits change.** A vec3 with one part tuned changes that part only.

### 10.5 Large and small

The step is relative once, when tuning starts, and absolute after that. That gives exact numbers and a ruler that starts at a sensible speed for 960.0, for 0.3 and for 1e-3 alike. Crossing zero keeps the step: with 0.01 the value goes 0.01, 0.00, -0.01. For a jump of decades, the step chip or typing. While tuning, the temporary uniform is `highp` where the device has it (as the template's `#ifdef GL_FRAGMENT_PRECISION_HIGH` does); on a device with `mediump` only, a value above about 16,000 or with many significant digits is approximate on screen while tuning, and exact in the text afterwards.

### 10.6 What a change costs, and why tuning needs a temporary uniform

**Today, tuning by changing the text is not interactive.** Read from the code at 5e48862d:

1. A change waits the update delay after the last edit: `view.postDelayed(editPaused, updateDelay)` (`CodeViewSourceEditor.java:352–353`); 0.5 to 5 s, 1 s by default (`preference_update_delay.xml`).
2. Then the run builds the project and hands the text to the engine (`LibraryScreen.java:535–545`).
3. The engine compiles and links on the GL thread (`ShaderEngine.java:466` → `GlDevice.java:463–498`); the old picture draws until the new build is complete (`ShaderSwap.java:6–13`). The time is not measured; it grows with the shader and differs per driver.
4. The new build restarts the clock: `inputs.restart(next.quality(), now)` (`ShaderEngine.java:421`) sets time and frame to 0 and takes a new `startRandom` (`EngineInputs.java:160–166`). An animated picture jumps back to its start on every step.

So tuning by text means one update a second at best, and the moon on its orbit starting over each time.

**The design: a temporary uniform for the session.**

- **Start:** the shader is built once with the tuned literal's text replaced by a uniform name (for example `se_tune0`), declared by one more generated line after `#version`, the way the engine already adds `#define SHADER_EDITOR 1` (`PlanSource.java:58–75`); generated lines map to no line of the user's, so errors keep their lines. One compile.
- **Each change:** the bar hands the value to the engine, which sets the uniform with the next frame. No text, no compile: as fast as the picture draws.
- **End (Done or Back):** the digits are written into the text once (one undo step) and the real text builds once.
- **The clock:** these two builds keep time and frame running instead of restarting them. That is an engine change: a build request that says "same picture, keep the clock". Histories of passes and `backbuffer` start again at these two builds, as SPEC 7 decides for every build.
- **Several files:** a literal in an include reaches every pass that includes it; the uniform is declared in each program and set for each.
- **When it cannot work:** where GLSL needs a constant (a `const` initializer, an array size, a global initializer, a `case` label, a loop count in GLSL ES 1.00, a `#define` used in one of those), the build with the uniform fails to compile. The app does not guess: it tries, and if the compiler refuses, it falls back to building the text each time the finger rests (about 150 ms still), with the update delay skipped, and the bar says so (`t2-int`). That is a few updates per second at best, with the clock kept but histories restarting.

**Engine pieces this needs:** a value the host sets for one named uniform; a build of the current files with one range replaced and one generated line; the "keep the clock" flag. Moderate; none touches the editor's typing path.

![Awkward cases, what is written, what it costs](../show/editing-2/sheet-5-awkward-and-cost.png)

### 10.7 Which control, for one thumb

| Pattern | Verdict | Why |
| --- | --- | --- |
| **Ruler with detents** (a horizontal wheel, one tick per step) | **Yes, the main control** | Needs no range; exact; the whole width is the drag area, where the thumb rests; a tick per step is felt; a fling covers distance and lands on a step |
| **− and +** | **Yes, inside the bar** | One exact step each, 48 dp at the bar's edges; alone they need 30 taps for 30 steps (`t4-stepper`) |
| **A step chooser** | **Yes, a chip** | It is how "exactly 0.1" is said |
| **Scrubbing the number in the code** | **No** | The finger covers the number; the drag fights selection and scrolling; the code covers the picture he chose to see (`t4-scrub`) |
| **A slider with a range** | **No for numbers; yes in colour mode** | Needs two more numbers first; right only where the range is the type's own (`t4-slider`) |
| **A 2D pad for a vec2** | **Optional (question 4)** | Moves x and y together like a trackpad, relative, no range needed; one more control to learn (`t2-pad`) |
| **A vertical drum** (Android's number picker) | **No** | Three times the height for the same one-value-at-a-time |
| **A colour picker** | **Only on request**, as R, G, B | A hue wheel writes numbers nobody chose; shaders are written in linear R, G, B |

TalkBack: the ruler is a seek bar: swipe up or down moves one step and the new value is spoken; Revert, Done and Step are buttons. A hardware keyboard: ← and → step, Shift ×10, Enter is Done, Escape is Revert.

![Which control, for one thumb](../show/editing-2/sheet-4-patterns.png)

### 10.8 Awkward cases

- **A value in the middle of a long line:** the line above the bar is cut on both sides around the number, which stays in the middle (`t5-long`, Horizon 69).
- **A negative large number, an int, a vec3 position, a colour:** `t1-large`, `t2-int`, `t2-vec3`, `t3-numbers` and `t3-colour`.
- **Landscape:** the bar in two rows, the picture above (`t5-landscape`).
- **Undo:** one step per session. Revert puts the start value back and writes nothing. Back keeps the value, as Done does (taken as the default; a minute of tuning should not vanish with a gesture).

### 10.9 Weakest point

Numbers that GLSL needs as constants cannot become a uniform. They fall back to one build per resting finger: a few updates a second, and the passes' histories start again each time. Ints are often exactly these (loop counts in GLSL ES 1.00, array sizes). Second, smaller: the app cannot tell a colour from a position, so a colour costs one tap on Colour.

### 10.10 Questions for the owner

1. **When you tune a vec3 or vec4 of numbers, how does it open?** (a) As numbers; a Colour button switches (default). (b) As a colour when all parts lie between 0 and 1; As numbers switches back. Picture: `sheet-3-colour.png`.
2. **The main control for a number?** (a) A ruler you drag, with − and + and a step choice (default). (b) Only − and + with a step choice. (c) Dragging on the number in the code. Picture: `sheet-4-patterns.png`.
3. **The first step for a number like 0.3?** (a) 0.01, a tenth of its size; the step chip changes it (default). (b) 0.1, the last digit you wrote. Picture: `sheet-1-numbers.png`.
4. **A vec2?** (a) x and y one at a time on the ruler (default). (b) Also a square pad that moves both together. Picture: `sheet-2-kinds.png`.
5. **Numbers GLSL needs as constants (an ES 1.00 loop count, const, array sizes)?** (a) Tune them anyway: built each time your finger rests, and the bar says so (default). (b) Do not offer tuning for them. Picture: `t2-int` in `sheet-2-kinds.png`.

Taken as defaults, reversible: Back keeps the value; degrees only inside `radians()`; the step is remembered per literal while the file is open; the clock keeps running through a tuning session.

### 10.11 Not verified

- Compile and link times on the owner's phone: not measured; the claim "not interactive today" rests on the update delay and the clock restart, which are certain from the code.
- That drivers refuse a uniform in an ES 1.00 loop condition: the specification's Appendix A asks for a constant there; some drivers accept more. The design does not depend on which: it tries and falls back.
- Haptic ticks and fling feel: designed, not tried.
- The temporary uniform's name must not collide with a user's name; the generated line is checked against the file's declarations first.

## 11. Round 3: the impeccable pass

The owner asked for the skill `impeccable:impeccable` to "typeset, layout, animate, delight, quieter, shape, audit and critique first", before building. This section is that pass over the tuning bar as decided and over the Look up sheet, the call in the strip and the Add uniform fix. Pictures: `.foreman/show/editing-3/`, before next to after.

### 11.0 How it ran

- **The skill loaded** (Skill tool, `impeccable:impeccable`). Its setup found no PRODUCT.md and no DESIGN.md. At the coordinator's instruction ("satisfy whatever setup the skill wants from what exists") no interview was held. PRODUCT.md was written from STYLE.md, UX.md, NAVIGATION.md and this proposal, every fact marked with its source, at `.foreman/impeccable/PRODUCT.md`, not in the repository. No DESIGN.md was written: `docs/project-mode/STYLE.md` is the design system and is binding.
- **Modes run, in this order:** shape, critique, audit; typeset, layout, quieter; animate, delight; a last critique. The skill's craft floor was read before drawing.
- **Native platform:** the critique ran as its Assessment A only, in this context. The detector reads web markup and does not apply. The method line is: *design review only (detector not applicable, native platform)*. The audit used its native variant against the skill's Android reference. Motion was specified, not captured: the skill's motion capture and motion reviewer work on web pages only.
- **What the skill would have saved into the repository's `.impeccable/`** (critique and audit snapshots) is this section instead.
- **Where the skill's taste met the owner's principle** ("readable and familiar first: stock, legible UI over quiet or novel") and STYLE.md, those won; 11.10 lists each case.

### 11.1 Shape: the brief

- **Job:** with one thumb, change one number of a running shader until the picture is right, exactly, and get back to the code. Separately, learn what a word in the code is, without leaving the line.
- **Outcome:** the value written exactly as wanted, in one undo step; the doc read in seconds.
- **Direction:** inside STYLE.md's world (Instrument, Material 3, dark, the picture loud and everything else quiet). No new visual world. Operate mode: familiarity is a feature.
- **Scope:** the tuning bar in every state (number, int, vector, colour, constant, typing, step choice, landscape, font scale 2.0); the Look up sheet in its three kinds; the call in the strip; the fix in the error row.
- **Anti-goals:** anything that slows a keystroke, costs frames of the shader, floats over the edited line, or invents a control a fluent Android user would not recognise.

### 11.2 Critique (before)

Method: design review only (detector not applicable, native platform).

| # | Heuristic | Score | Key issue |
| --- | --- | --- | --- |
| 1 | Visibility of system status | 2 | A constant that rebuilds says so in amber but never shows when the picture caught up |
| 2 | Match to the real world | 3 | "Uniform of ShaderEditor"; the `T` notation is explained |
| 3 | User control and freedom | 3 | Revert, Back keeps, one undo step |
| 4 | Consistency and standards | 2 | Glyph − and +; a six-part segmented row; code at 13 sp in the sheet; amber used for information |
| 5 | Error prevention | 3 | The literal's style and type are kept; constants fall back instead of failing |
| 6 | Recognition rather than recall | 3 | The call in the strip; the step on screen |
| 7 | Flexibility and efficiency | 3 | Typing, the step menu, hardware keys |
| 8 | Aesthetic and minimalist design | 2 | Five rows, one of them holding only the step chip; a dashed underline; three tick levels |
| 9 | Error recovery | 3 | The fix is a button; the message stays the driver's |
| 10 | Help and documentation | 3 | Look up at the word |
| | **Total** | **27/40** | **Acceptable** |

**Design specificity.** Specific where it counts: the bar exists because the picture is the product. The tuned line sits above the controls with the new digits, and the code steps aside for the picture. Generic in its parts: glyph buttons, a segmented row standing in for a menu, a dashed underline borrowed from the web.

**Priority issues.**

- **[P1] The call in the strip pushes `)` off the screen.** Inside a call, `)` is the key one needs most, and the call put first moves it past the edge. *Fix:* the six keys never move; the call goes after them and is cut from the left, keeping the current argument and the `)` (`b-strip`). The function's name is on the line just typed. *Layout.*
- **[P1] A constant's rebuild is invisible.** The amber note reads as a warning and nothing shows when the picture has caught up. *Fix:* a neutral note in `colorOnSurfaceVariant`, plus Material's 4 dp linear progress line at the top edge of the bar while a build runs (`a-const`). *Clarify, animate.*
- **[P1] The ruler fails contrast and size.** Ticks at 45 % white over the bar's worst case `#414245` are about 1.9 : 1, under the 3 : 1 that non-text marks need; labels are 11 sp in the code font. *Fix:* ticks in `colorOnSurfaceVariant` (about 4.5 : 1 there), labels `textAppearanceBodySmall` 12 sp Roboto with tabular figures (`a-bar`). *Audit, typeset.*
- **[P2] Glyphs stand in for icons.** − and + were text. *Fix:* Material Symbols `remove` and `add` in 48 dp icon buttons. *Polish.*
- **[P2] The step chooser is a six-part segmented row.** That is more than a segmented button holds and more choices than one glance takes in. *Fix:* the chip opens the library's menu with the six steps (the current one checked) and Other… (`a-step`). *Layout.*
- **[P2] Grouping.** The step chip had a row of its own; Colour sat at the bottom, far from the parts it reinterprets. *Fix:* parts and Colour share a row; Revert, Step and Done share the last row (`a-vec3`, `a-colour-numbers`). *Layout.*
- **[P2] The Look up sheet reads in the wrong order, at 13 sp.** *Fix:* the meaning first, then the form, then the notes that explain the form, then the example; code at 14 sp, STYLE's floor outside the editor (`b-sheet`). *Typeset.*
- **[P3] Wording.** "Uniform of ShaderEditor" becomes "Built-in uniform", the word of Add uniform's tab. "From the comment above it in lib/sdf.glsl." is cut: the subtitle already names the file. *Clarify.*
- **[P3] The fix button was 36 dp tall.** *Fix:* 40 dp in a 48 dp touch target (`b-fix`). *Polish.*

**Persona red flags.**

- **Alex, power user with a keyboard:** nothing stepped the ruler from keys. Added: ← and → one step, Shift ten steps, Enter Done, Escape Revert.
- **Sam, TalkBack:** the ruler was a picture. It must be one adjustable node (range info with the value; scroll forward and backward step once). − and + need the step in their names ("Decrease by 0.01"). The ring needs words ("started at 0.30"). The sheet's code needs a spoken form.
- **Casey, one thumb:** Done is bottom right, in reach; the step menu opens upward from the bottom row. Passed.
- **The learner at the first error** (from PRODUCT.md): the fix sits in the row under the very line, in the accent. Passed. Only the 36 dp height was off.

### 11.3 Audit (native, before)

From the mock-ups and the code the surfaces will sit in (`CompletionsAdapter`, `MessageRow`, `extra_keys.xml`). Nothing of the new surfaces exists in code yet.

| # | Dimension | Score | Key finding |
| --- | --- | --- | --- |
| 1 | Accessibility | 2 | The ruler had no node; tick contrast 1.9 : 1; 11 sp labels; − and + unnamed; the sheet's code blocks unspoken |
| 2 | Performance | 3 | Fine if the ruler is one custom view drawing all ticks; value posts to the engine at most once per frame (Choreographer); no allocation per frame |
| 3 | Appearance and theming | 3 | Roles throughout; the bar level `#CC121316` is STYLE's own token |
| 4 | Platform conformance | 2 | Glyph buttons, a segmented row as a menu, a dashed field |
| 5 | Adaptivity | 3 | Landscape and font scale 2.0 needed a layout each; on wide screens the bar should not stretch past 600 dp |
| | **Total** | **13/20** | **Acceptable** |

After the changes below: accessibility 3, performance 3, theming 3, conformance 3, adaptivity 3: **15/20, Good.** What keeps conformance at 3: the ruler is not a library component, because Material has no horizontal number wheel; Android's `NumberPicker` is vertical and three times the height. It is drawn in the library's colours and given a standard adjustable node.

### 11.4 Typeset

Roles, final. Two families, as STYLE 6.3 says: Roboto for what the app says, the code font for what is GLSL.

| Element | Appearance | Family, size | Colour role |
| --- | --- | --- | --- |
| Tuned line above the controls | code | code font 14 sp / 20 | `code_*` palette; the literal on `code_selection` |
| The value | code | code font 24 sp (HeadlineSmall's size) | `colorOnSurface` |
| Parts `x y z`, their values, `r g b` | code | code font 14 sp | letters `colorOnSurfaceVariant`, values `colorOnSurface` |
| Ruler labels | `textAppearanceBodySmall` | Roboto 12 sp, tabular figures | `colorOnSurfaceVariant` |
| Step chip "Step 0.01" | `textAppearanceLabelLarge` | Roboto 14 sp medium, tabular figures | `colorOnSurface` on 14 % white |
| Step menu items | `textAppearanceBodyLarge` | Roboto 16 sp, tabular figures | `colorOnSurface` |
| Note (constants) | `textAppearanceBodyMedium` | Roboto 14 sp | `colorOnSurfaceVariant` |
| Buttons | `textAppearanceLabelLarge` | Roboto 14 sp medium | per button style |
| Sheet title | TitleLarge's size | code font 22 sp / 28 | `colorOnSurface` |
| Sheet subtitle, notes | `textAppearanceBodyMedium` | Roboto 14 sp | `colorOnSurfaceVariant` |
| Sheet meaning | `textAppearanceBodyLarge` | Roboto 16 sp / 24 | `colorOnSurface` |
| Sheet code blocks | code | code font 14 sp / 20 | `code_*` palette on `colorSurface` |
| Sheet "Example" | `textAppearanceTitleSmall` | Roboto 14 sp medium | `colorPrimary` |
| The call in the strip | code | code font 14 sp | `colorOnSurface`; the current argument `colorPrimary`; the cut "…" Roboto `colorOnSurfaceVariant` |

The step and the ruler's labels are settings and scale marks, not GLSL, so they use Roboto. The value and the parts are what gets written into the code, so they use the code font.

### 11.5 Layout

**Spatial thesis.** Read top to bottom as the task runs: *what* is tuned (the line), *which part* and *as what* (parts, Colour), *the value* (− value +), *how it moves* (the ruler), *the session* (Revert, Step, Done). Parts belong with their representation; the step belongs with the session, beside the ruler it scales; Done is the one filled button, bottom right, in the thumb's reach.

**The tuning bar, final numbers** (portrait; dp unless sp):

| Part | Spec |
| --- | --- |
| Bar | Full width, bottom, above the navigation inset; `se_level_bar` (80 % `se_surface`) over the picture; 1 dp `colorOutlineVariant` hairline on top; padding 12 top, 16 sides, 12 bottom; content at most 600 wide, centred, on wide screens |
| Progress line | Material `LinearProgressIndicator`, 4 thick, indeterminate, along the top edge, only while a constant's build runs (11.6) |
| Row 1: line | One line, cut at word boundaries on both sides with "…", the literal kept in the middle |
| Note (constants only) | 8 below the line; `ic_info` 18 + text; "A constant here: the picture follows when your finger rests" |
| Row 2: parts and Colour (vectors only) | 12 below; Material segmented button, 40 tall, one segment per part, the selected one with the library's check; then an outlined button 40 tall: the swatch it would be (16, 4 corners) + "Colour"; in colour mode "As numbers" |
| Row 3: value | 12 below; − and + are 48 × 48 icon buttons (`se_surface_3`, 8 corners, `remove` and `add`); between them, 8 apart, an outlined text field 48 tall, 1 outline, 8 corners, value centred; a tap focuses it (2 `colorPrimary`) and opens the number pad |
| Row 4: ruler | 8 below; 48 tall; one tick per step, 12 apart; minor 8 tall 1 wide, every tenth 16 tall 1.5 wide with its label 4 under it; centre mark `colorPrimary` 2 × 28, round ends; the start value a ring of 8, 1.5 `colorPrimary`; 32 fades at both ends; the whole bar width is the drag area |
| Row 5: session | 8 below; Revert (text button), Step chip (36 tall, 8 corners, 14 % white, `arrow_drop_down`), Done (filled, `check` + "Done") at the end; 8 apart |
| Heights | Number: 224 + insets. Vector: 276. Colour mode: 288 |
| Landscape | Line on top; one row: Revert, −, value (112 wide), +, ruler (flexible), Step, Done |
| Font scale 2.0 | Text doubles; rows grow to fit (min heights stay); the line shortens to what fits; nothing is cut inside a control |

**The Look up sheet:** the library's modal bottom sheet. Content 24 from the sides (`se_margin_sheet`), the handle, then title; subtitle 4 under it; meaning 16 under; code block 12 under (padding 12, 8 corners, `colorSurface`); notes 8 under the block; "Example" 24 above, its block 12 under; actions end-aligned 24 under the last block. Opens fitted to its content up to 90 % of the height, scrolls inside.

**The strip:** Tab key, then the six keys always in the same place, then the call filling what is left, cut from the left. Hidden when fewer than six characters fit (font scale 2.0 on a narrow phone).

**The fix:** under the message, end-aligned, a text button 40 tall in a 48 tall target, 4 under the message; it does not count toward the row's three lines of text.

### 11.6 Quieter

Removed: the dashed underline under the value (a web habit; the field says "editable" the familiar way); the half-step tick level (two levels read as a ruler, three as noise); amber for information (amber is STYLE's warning); the line naming where a comment came from; the tonal box around the call (it is a hint, not an action).

Kept: the bar's translucency (STYLE 6.2: the picture behind is being looked at); the accent on the current argument (the strip's decided "where you are" colour); the single filled button.

### 11.7 Animate

Thesis: motion only says where the code went and that a build is running. No authored moment: the picture is the moment. Library durations and easings (STYLE 6.7); every animation is a `ValueAnimator`, so the system's "Remove animations" makes it instant.

| Event | What moves | Duration | Easing | With "Remove animations" | Cost to typing and frames |
| --- | --- | --- | --- | --- | --- |
| Tuning starts | Code view alpha 1 → 0; the picture's limit lifts (STYLE 6.7); the keyboard hides (system); the bar fades in and rises 16 | 150 (code, limit), 200 (bar) | standard; bar emphasized decelerate | Instant | View alpha on a hardware layer; the GL thread draws as before |
| Tuning ends (Done, Back, Revert) | Bar fades and sinks 16; code alpha 0 → 1; the limit returns at once (STYLE: limited as soon as code shows); the keyboard returns | 150 | emphasized accelerate | Instant | As above |
| Drag on the ruler | Follows the finger 1 : 1 | — | — | Unchanged (direct manipulation) | One custom view; the value goes to the engine once per frame at most |
| Fling | Runs on with friction, settles on a step | physics; settle 100 | standard decelerate | The fling stays (it is the gesture's own result); the settle is instant | As above |
| − or + | The value changes at once; the ruler shifts one step | 100 | standard | Instant | — |
| Revert | The ruler slides back to the start ring | 250 | emphasized | Instant | — |
| Part selected | Library segmented button; the ruler re-centres at once (another quantity, no slide) | library | library | Instant | — |
| Numbers ↔ Colour | Fade-through | 200 | standard | Instant | — |
| Constant's build | Progress line appears after 150 (no flicker on a fast build), stays at least 300 | indeterminate | library | A static line | Library component |
| Step menu, Look up sheet | The library's menu and bottom sheet | library | library | Instant | — |
| The call in the strip | Nothing | — | — | — | STYLE: nothing moves because a key was pressed |
| Fix applied | Nothing moves; the inserted line is marked with `code_selection` until the next edit | — | — | Same | A fade would redraw the line for a second and break EDITOR R7 (no frames when idle) |

**Haptics, honestly.**
- A tick per step while dragging: `HapticFeedbackConstants.CLOCK_TICK` (API 21), at most one per frame.
- No ticks during a fling, one when it settles.
- A firmer one at the start value: `SEGMENT_TICK` from API 34, `CONTEXT_CLICK` before.
- `View.performHapticFeedback` follows the system's touch-feedback setting.
- On some phones a clock tick is barely felt; on older ones it is a short buzz, which is why the rate is capped.
- The vibrator call returns at once, so it costs no frame.
- Ticks are an addition: every step is also seen in the number and the line.

### 11.8 Delight

**Thesis: the ruler remembers where you started.** A ring marks the start value, a firmer tick is felt passing it, and TalkBack says "started at 0.30". It answers the tuner's constant question ("was it better before?") without Revert, and it belongs to this tool alone (`a-bar`).

**A candidate, the owner's call: curves in Look up.** For the one-variable curve functions (`step`, `smoothstep`, `clamp`, `fract`, `mod`, `sin`, `cos`, `abs`, `sign`, `floor`, `ceil`, `exp`, `log`, `sqrt`, `mix` in its `a`), a small graph under the meaning, in the built-in colour (`b-graph`). Shaders are curves; one look teaches what a sentence cannot. Cost: one small view and a function per entry, no assets.

**Rejected:** a celebration when the fix makes the shader run (the running picture is the reward); rolling digits (they cost legibility); a sound.

### 11.9 Critique (after)

| # | Heuristic | Before | After | Why |
| --- | --- | --- | --- | --- |
| 1 | System status | 2 | 3 | Progress line; the ring |
| 2 | Real world | 3 | 4 | "Built-in uniform"; meaning first |
| 3 | Control | 3 | 3 | Unchanged |
| 4 | Consistency | 2 | 3 | Library icons, menu, field, segmented check; 14 sp code |
| 5 | Error prevention | 3 | 3 | Unchanged |
| 6 | Recognition | 3 | 3 | Unchanged |
| 7 | Efficiency | 3 | 3 | Keys added, but no shortcut to start tuning from a hardware keyboard yet |
| 8 | Minimalist | 2 | 3 | One row less of noise; two tick levels; no underline |
| 9 | Error recovery | 3 | 3 | Unchanged |
| 10 | Help | 3 | 3 | Graphs would make it 4 |
| | **Total** | **27/40** | **31/40** | **Good** |

What still costs points:
- The ruler is the one non-library control.
- No keyboard path starts tuning (proposed: Ctrl+Shift+T on a number).
- The cut call in the strip loses the function's name on a narrow phone. The name is on the line above, but on a phone narrower than 412 dp the call may be hidden entirely.

### 11.10 Where the skill's advice was not taken

| The skill | Kept instead | Why |
| --- | --- | --- |
| Operate mode: "a grey screen with one accent colour is the category default"; colour should do jobs for the shell | STYLE's near-neutral scheme with one accent | Decided direction C: the picture is the only loud thing |
| Android reference: Dynamic Color where it fits | The fixed blue by default, device colours as a setting | Decided S1 |
| Android reference: 48 dp for every target | The strip's keys stay 44 dp | STYLE `se_key_height`, decided: 48 dp costs a line of code |
| Craft floor: no coloured edge bar above 1 px on alerts | The error line's 3 dp bar at the gutter | STYLE 6.10, decided; a line mark, not a card |
| Craft floor and animate: one authored motion moment; reach past opacity (blur, masks) | Library timings only, no blur | STYLE 6.7 "nothing bespoke"; glass rejected in direction B; motion must not cost frames |
| Animate the confirmation of a change | A static mark on the inserted line | EDITOR R7: no frames when idle |
| Critique and audit snapshots in the repository's `.impeccable/` | This section; PRODUCT.md under `.foreman/impeccable/` | Coordinator's instruction |

### 11.11 Punch list for the Look up sheet, `(` completion and the fix (for the worker building them now)

Strings are English, the same text in every locale file.

**Look up sheet**
1. The library's modal `BottomSheetDialog` with its drag handle; opens fitted to content up to 90 % of the height; content in a scroll view; Back, scrim tap and drag close it; when it closes, the code view takes focus again and the keyboard returns if it was up.
2. Content padding 24 dp at the sides (`se_margin_sheet`), 24 dp plus the navigation inset at the bottom.
3. Order: title, subtitle, **meaning**, form block, notes, "Example" and its block, actions. The meaning comes before the form.
4. Title: the word in the code font at 22 sp, line height 28 sp, `colorOnSurface`.
5. Subtitle 4 dp below: `textAppearanceBodyMedium`, `colorOnSurfaceVariant`. Strings: `docs_builtin_function` "Built-in function", `docs_builtin_uniform` "Built-in uniform", `docs_function` "Function", `docs_uniform` "Uniform", `docs_macro` "Macro", `docs_struct` "Struct", `docs_variable` "Variable"; joined with " · " to the versions `docs_es_both` "GLSL ES 1.00 and 3.00", `docs_es_300` "GLSL ES 3.00 only", `docs_es_100` "GLSL ES 1.00 only", or to the place `docs_where` "%1$s, line %2$d", or to the type of a uniform.
6. Meaning 16 dp below: `textAppearanceBodyLarge`, `colorOnSurface`. For own names the comment above the declaration, by the rule of 3.4.
7. Form block 12 dp below: code font **14 sp** (not 13), line height 20 sp, padding 12 dp, 8 dp corners, background `colorSurface`, coloured with the `code_*` palette. A form wider than the block wraps one parameter per line, indented 2 spaces.
8. Notes 8 dp below the block: `textAppearanceBodyMedium`, `colorOnSurfaceVariant`.
9. "Example" (`docs_example`): `textAppearanceTitleSmall`, `colorPrimary`, 24 dp above; its block 12 dp below, as item 7.
10. Actions end-aligned, 24 dp below the last block: own name in another file → text button `docs_open` "Open %1$s"; in this file → text button `docs_go_to_line` "Go to line %1$d"; app uniform not declared → note `docs_not_declared` "Not declared in this file yet, so the shader cannot read it." and the filled button "Add uniform" (the existing string).
11. Remove any line that says where the text came from ("From the comment above it…").
12. Look up appears in the selection toolbar after Paste only when the selected word has docs (a built-in, an app uniform, an own declaration); never as an entry that does nothing. The same is a custom accessibility action on the code view: `a11y_look_up` "Look up %1$s".
13. TalkBack: give each code block a content description in words, e.g. "smoothstep takes edge0, edge1 and x, each a float or a vector", built from the entry's data, not the raw characters.

**The call in the strip**

14. The six keys keep their places; the call comes after them and fills the rest of the strip.
15. Cut from the left: keep the current argument and the closing `)`; the cut is "…" in Roboto, `colorOnSurfaceVariant`; hide the item when fewer than 6 characters fit.
16. Code font 14 sp; the current argument `colorPrimary`; everything else `colorOnSurface`; no box behind it.
17. Tap or long press opens Look up for the function.
18. Accessibility: content description `a11y_signature` "%1$s, argument %2$d of %3$d, %4$s. Double-tap for docs." When a call opens (the `(` is typed after a known function), announce once `a11y_signature_open` "%1$s: %2$s" with the names joined by commas; never on commas.

**`(` completion**

19. A function completes as `name(`: the strip shows `mix(` with the typed prefix in `colorPrimary` and inserts `name(`; no `)` is added.
20. Not for `main` and not for types (`vec3` is also a constructor).
21. Content description "%1$s, function" (`a11y_completion_function`).

**The fix in the error row**

22. Below the message, end-aligned with 4 dp end inset, 4 dp below the message.
23. A text button 40 dp tall with a 48 dp touch target, `textAppearanceLabelLarge`, `colorPrimary`, pressed state 12 % `colorPrimary`. It does not count toward the row's three lines of text.
24. Label `fix_add_uniform` "Add uniform %1$s %2$s" (type, name: "Add uniform float time").
25. After the tap: one undo step; the inserted line gets `code_selection` as a static mark until the next edit (no fade); announce `a11y_added_uniform` "Added %1$s on line %2$d".
26. The same fix as a custom accessibility action on the code view, with the same label; the error's announcement ends with `a11y_fix_available` "Fix in the actions menu: %1$s".
27. In the error list sheet, the same button at the end of the error's row, as a text button 48 dp tall.

### 11.12 Questions for the owner

1. **Where the call sits in the strip.** (a) After the six keys, cut from the left, so `)` never moves (default; `b-strip` against `s1-strip` in `sheet-3-lookup-strip-fix.png`). (b) Before the keys, as in round 1.
2. **Curves in Look up.** (a) Yes, after M1, for about 15 one-variable functions (default; `b-graph` in `sheet-5-motion-delight.png`). (b) No.
3. **The ruler's start ring and ticks.** (a) A ring at the start value, a light tick per step and a firmer one at the start, following the phone's touch-feedback setting (default; `a-bar` in `sheet-1-tuning-bar.png`). (b) The ring without vibration. (c) Neither.

### 11.13 Not verified

- Nothing ran on a device: haptic strength, the feel of the fling and the contrast of ticks over a bright frame are judged from mock-ups and arithmetic.
- The skill's motion capture and its motion reviewer were not run: they work on web pages, and these surfaces are native.
- The worker's implementation of the sheet, `(` completion and the fix was not read; the punch list is against the round 1 pictures it was briefed with.

