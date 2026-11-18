# The typo experiment: how good are the drivers' own messages?

Experiment, 2026-11-18. No app code changed. Status: **complete for the emulator compiler stacks** (Mac/Apple and Windows/NVIDIA hosts). The phone run is still to come, and only happens when the owner says the phone is free. The plan in section 1 was written before any output was seen and has not been changed; the deviations are listed in section 10.

## The answer in short

- **Kill criterion 1 is not met.** The criterion asks whether the driver already gives the right line and a usable message in 95 % of typos. On both emulator stacks the driver's first error line alone does that in **73 %** of the 264 typos. What the app shows today does it in **80 %**: its own check catches unterminated comments and missing `#endif` before the driver. Both are 15 to 22 points short of 95 %. Even if a merely "true but unhelpful" message counted as usable, the figures are 84 % and 89 %, still short.
- **Where today's messages are short of that is narrow.** It is almost entirely punctuation:
  - a missing `;` (0 of 22 with a message that names it),
  - a parenthesis (6 of 22; 8 of the others name a wrong problem, such as "dimension mismatch"),
  - a brace (10 of 22).
  
  Names, built-ins, argument counts, types, `.0`, keywords, `#endif` and use-before-declaration are right in 21 or 22 of 22 each.
- **The strict reader, beside the driver, would turn 33 of the 264 typos (12.5 %) from not right into right**, on the study's rules (shown only when the driver refused, and only on or next to the driver's line, or where the driver gives none). All 33 are punctuation: 15 missing `;`, 14 parentheses and 4 braces. It would put a worse sentence beside a right one in 10 brace cases.
- **In plain words: located errors would help in about 12 % of typos on these stacks.** That is far less than the study's "errors at the token on every device", and it says nothing yet about a phone's Mali or Adreno driver.
- **Four small faults of today's app** came out of it (section 7), each a few lines:
  - no line at all when a `}` is missing at the end of a file (7 of 264);
  - the wrong problem named for a comment left open in a library file (2);
  - no line at all on any driver that writes Mesa's log format;
  - no line at all on NVIDIA's native format.

Question: one proposed use of the strict reader (`one-front-end.md`, section 2.1, "located errors") is to show where an error is, in clear words, beside the driver's message when the driver refused a shader. Its worth depends on how good the drivers' own messages already are. The study's kill criterion 1: "the driver already gives the right line and a usable message in 95 % of cases".

## Contents

1. The fixed plan (written before any output)
2. How the texts were made
3. The devices and what compiler stack each really is
4. Results per voice (line and message by typo kind)
5. Ten examples side by side
6. Where the strict reader is worse than the driver, and where better
7. What the app shows today: wrong line or none
8. What it means for the decision
9. Raw data, the tool, and how to run it elsewhere
10. Not verified, and changes to the plan

---

## 1. The fixed plan

Fixed on 2026-11-18 before the first mutated text was compiled anywhere (driver or Mesa). Nothing below was changed after looking at output; any later change is marked as such in section 10.

### 1.1 Corpus

- The bundled examples of project mode (`app/src/debug/assets/examples`, at 6049accc = `origin/integration/project-mode`): every `.frag` that defines `main` is a program; the three library files (`lib/*.glsl`) are mutated too, each compiled through one example that includes it.
- The five ShaderToy fixtures (`app/src/test/resources/shadertoy/*.json`), converted by the app's own import (`ShaderToyProject.ofJson`); typos go into the files the import writes, which are what a user then edits.
- A typo is made in **one source file**, as a person would type it; the program is then assembled by the app's own pipeline (section 2), so the driver gets exactly what it would get in the app.

### 1.2 The typo kinds and how many

Twelve kinds, **22 mutated programs per kind, 264 in total**. Sites are drawn by a seeded random choice (seed 2026) among all eligible places of all files, spread over files round-robin, so no file dominates a kind. Each mutated text is kept with its record: file, line, column, kind, before → after.

| # | Kind | What is changed | Eligible place |
| --- | --- | --- | --- |
| K01 | missing semicolon | a `;` that ends a statement is deleted | inside a function body, not inside a `for (…)` head |
| K02 | parenthesis | half: a `)` is deleted; half: a `)` is typed twice | inside a function body |
| K03 | brace | half: a `}` is deleted; half: a `}` is typed twice | a `}` that closes a block or a function body |
| K04 | misspelled name | one letter of a use of the file's own name (local, parameter, global, function) is wrong: a neighbour key on a QWERTY keyboard, a letter dropped, or two letters swapped (seeded); the result is no declared name and no keyword | a use, not the declaration |
| K05 | misspelled built-in | the same change to a call of a built-in function (`smoothstep` → `smoothsetp`) | a call of a function of the language |
| K06 | wrong number of arguments | half: the last argument is dropped; half: the last argument is given twice | a call with at least two arguments of a function (built-in or the file's own), not a constructor |
| K07 | `vec3` for `vec2` | the type of a local declaration `vecN x = …` becomes `vec(N+1)` (`vec4` becomes `vec3`) | a local declaration with an initializer |
| K08 | missing `.0` | an integer-valued float literal (`2.0`, `1.`) loses its fraction (`2`), making int and float mix | the literal is an operand of a binary operator or the right side of `=` / an initializer, not directly an argument of a call or constructor |
| K09 | keyword typo | a keyword (`uniform`, `float`, `return`, `void`, `const`, `if`, `for`, `else`, `precision`, `in`, `out`) gets one letter wrong as in K04 (`unifrom`) | anywhere in code |
| K10 | unterminated comment | `/* ` is typed at the start of a line of code; it is never closed | a line inside a function body |
| K11 | `#endif` missing | one `#endif` line is deleted | any `#endif` |
| K12 | use before declaration | half: a local's declaration line is moved below the first line that uses it; half: a function of the file is moved behind the function that first calls it | a local declared on its own line and used on a later line; a function called by a later function |

K08: GLSL ES 1.00 and 3.00 both forbid an implicit int → float conversion; drivers that run desktop GLSL underneath may accept it anyway. If a stack accepts a mutated text, that is a result ("this typo is not an error here"), not something to filter out.

### 1.3 The four voices

- (a) **The driver**: the full info log of `glCompileShader` for the fragment shader (and the vertex shader the engine pairs with it), and of `glLinkProgram` when both compile, on each device.
- (b) **The app today**: the app's own parser of driver logs (`opengl/DriverLog.parse`, with the program's `SourceMap`) run on (a). What the user reads is the first diagnostic of severity ERROR (`MainActivity.onInfoLog` shows that one in the snackbar; the editor marks the lines of all of them in the file shown).
- (c) **The strict reader** of the spike (`one-front-end-spike/src/exact`) on the same assembled text, over all configurations as in the spike: nothing (it read the text), or the first failure: line and message; a refusal (`Refuse`, a limit of the reader) shows nothing.
- (d) **Mesa's offline compiler** (`glsl_compiler --version 100|300`), judged by its output.

Lines of (a), (c), (d) are lines of the assembled text and are mapped back to file and line through the program's `SourceMap`, as the app does; (b) is mapped by the app itself.

### 1.4 Scoring rules

**The target lines** of a mutation, in the mutated file:

- K01–K10: the line where the change was made (for K10, the line where `/*` was typed).
- K11: the line of the `#if…` that lost its `#endif`, or the line where the `#endif` was.
- K12: the line of the first use (now before the declaration), or the line of the declaration.

**LINE**, against the nearest target line: **exact** / **±1** / **elsewhere** (another line, or another file) / **none** (no line at all). A voice that reports no error on a text a driver rejects scores *none*.

**MESSAGE**, three steps, judged on the first error message of the voice:

- **2, names the actual problem**: a reader who knows what they typed recognises the problem from the words alone. Per kind:
  - K01: says a `;` is missing or expected.
  - K02: names a parenthesis (missing, expected or unexpected `)`/`(`).
  - K03: names a brace or an unclosed block (`}` expected or unexpected).
  - K04, K05, K12: names the misspelled (or not yet declared) word as undeclared / undefined / no such function.
  - K06: says the number of arguments is wrong, or that no overload of the named function matches.
  - K07, K08: says the types do not match (cannot convert, wrong operand types, no operation for these types), with or without naming them.
  - K09: quotes the misspelled word itself, whatever the label.
  - K10: says a comment is not closed.
  - K11: says a conditional is not closed / `#endif` is missing.
- **1, true but unhelpful**: an error at roughly the right place whose words do not name the problem: "syntax error", "unexpected X" that does not name the missing thing, "unexpected end of file" (for K03, K10, K11), a message about the right word that says something generic.
- **0, misleading or absent**: names a different problem or a different word, says only "compilation failed" / "N compilation errors", or there is no message (no error reported though a driver refused).

**Blindness and consistency.** Messages are normalised (the quoted word kept, numbers kept), each distinct (kind, message) pair is scored once in a shuffled list that does not say which voice produced it; the scores then flow back to every program. The style of a message often betrays its source (Mesa's "syntax error, unexpected …", the reader's plain sentences): the scoring is blind only to the label, not to the style. A random 30 pairs are scored a second time, later, in a new order, and the agreement is reported.

**Accepted texts.** A mutated text that a driver compiles and links is "accepted on that stack" and left out of that driver's and the app's scores (nothing to show), and counted.

---

## 2. How the texts were made

`TypoGenerate` (branch `foreman/typo-experiment`, `typo-probe/jvm/`) runs on the JVM against the app classes built by `./gradlew :app:compileDebugUnitTestJavaWithJavac` at 6049accc.

- **The projects.** The examples folder is written to disk as one project, with its pictures. The examples are each `.frag` at the top level and `<folder>/main.frag` for Bloom, Game of Life and Ink. Each ShaderToy fixture becomes one project through `ShaderToyProject.ofJson`, with entry `main.frag`.
- **The programs.** For each entry, `Preparation.prepare(new DirectoryProjectFiles(dir), entry, ProjectLimits.DEFAULT, UniformTable.builtins())`. Every `RenderPlan.Program` of the plan then goes through `PlanSource.of(program, 3, errors)`, which is package-private in `opengl` and is the engine's last step before `glShaderSource`; its `fragment()` and `vertex()` are the texts. The `#define SHADER_EDITOR 1` line and the expanded includes are in them, and so is the extension line where it applies. This gives 47 programs: 30 examples and 17 ShaderToy passes, 17 of them `#version 300 es`.
- **A typo** is made in one source file and the whole program is prepared again, so the app's own pre-driver checks run as they would in the app. A library file's typo is compiled through the first program whose source map contains that file. Sites are chosen as section 1.2 says: 52 files are eligible in all, chosen round-robin over shuffled files, with seed 2026 + kind.

## 3. The devices

| Result file | AVD / machine | Android | Context the probe got | GL_VENDOR / GL_RENDERER / GL_VERSION |
| --- | --- | --- | --- | --- |
| emulator-5554 | ShaderEditor_Examples_API_36, Mac | 16 (SDK 36) | ES 3 | Google (Apple) / Android Emulator OpenGL ES Translator (Apple M1 Pro) / OpenGL ES 3.0 (4.1 Metal - 91.7) |
| emulator-5556 | ShaderEditor_API_23, Mac | 6.0 (SDK 23) | ES 3 | the same |
| homepc-api37 | Home-PC emulator, Windows | 17 (SDK 37) | ES 3 | Google (NVIDIA Corporation) / Android Emulator OpenGL ES Translator (NVIDIA GeForce RTX 3080/PCIe/SSE2) / OpenGL ES 3.1 (4.5.0 NVIDIA 621.24) |
| homepc-api23 | Home-PC emulator, Windows | 6.0 (SDK 23) | **ES 2** (GLSL ES 1.0.17) | Google (NVIDIA Corporation) / the same translator on the RTX 3080 / OpenGL ES 2.0 (4.5.0 NVIDIA 621.24) |

**What compiles the shader.** On all four devices it is the emulator's own GLES translator. It checks the shader first and writes the log in the format of ANGLE's shader translator (`ERROR: 0:L: 'token' : message`); then it hands the text on to the host's desktop GL, which is Apple's GL 4.1 over Metal on the Mac and NVIDIA's 621.24 driver on Windows. The host's own compiler never speaks to the guest. No log line has Apple's or NVIDIA's own format.

**Two stacks, one first message.** The two Mac files are identical in all 311 records, and so are the two Home-PC files. The Mac and Home-PC files differ in 208 of the 311 records:

- 177 differ only in a vertex-shader warning the Mac writes (`extension 'GL_EXT_gpu_shader5' is not supported`).
- 31 differ in later lines of the fragment log, such as the precision qualifiers named in a type.
- **The first error line is the same in all 264 mutants on both stacks.** The scores of the driver and of the app are therefore identical on both hosts. In the tables below, "driver" and "app" stand for all four devices, except where the ES 2 device differs.

**The ES 2 context compiled the `#version 300 es` programs.** On homepc-api23 the probe got an ES 2.0 context, and that context accepted all 17 ES 3.00 originals. The translator does not hold an ES 2 context to GLSL ES 1.00. **The app never gets that far:** on an ES 2 context, `PlanSource.of` refuses an ES 3.00 program before compiling it ("#version 300 es needs OpenGL ES 3.0, but this device only has OpenGL ES 2.0"), and that is the line it shows. So on that device the 86 mutants of ES 3.00 programs are not scored for the driver, and they are scored for the app only where the app's own pre-driver check comes first (9 cases). Every program the app would compile there gives the same first error as on the other devices. A real ES 2 phone driver would reject `#version 300 es`; the emulator does not, which is one more way an emulator is not a phone.

**Limit.** These are emulator stacks. Their messages come from the emulator's translator, whatever the host GPU is. A phone's Mali, Adreno or PowerVR driver compiles the shader itself and writes its own messages, and nothing here says what those are.

## 4. Results per voice

Each cell gives three numbers. **Line** is the count with the right line (exact or one line off). **Message** is the count whose message names the actual problem (score 2 of the rubric). **Both** is the count with both, which is what kill criterion 1 asks for. Each kind has 22 typos.

| Kind | Driver (all 4 devices) | App today | Strict reader | Mesa (offline) |
| --- | --- | --- | --- | --- |
| K01 missing `;` | 15 / 0 / **0** | 15 / 0 / **0** | 15 / 22 / **15** | 15 / 22 / **15** |
| K02 parenthesis | 22 / 6 / **6** | 22 / 6 / **6** | 22 / 21 / **21** | 22 / 22 / **22** |
| K03 brace | 15 / 10 / **10** | 11 / 10 / **10** | 15 / 7 / **4** | 15 / 10 / **10** |
| K04 misspelled name | 22 / 22 / **22** | 22 / 22 / **22** | 22 / 22 / **22** | 22 / 22 / **22** |
| K05 misspelled built-in | 22 / 22 / **22** | 22 / 22 / **22** | 22 / 22 / **22** | 22 / 22 / **22** |
| K06 argument count | 21 / 22 / **21** | 21 / 22 / **21** | 0 / 0 / **0** | 21 / 22 / **21** |
| K07 `vec3` for `vec2` | 22 / 22 / **22** | 22 / 22 / **22** | 0 / 0 / **0** | 22 / 22 / **22** |
| K08 missing `.0` | 22 / 22 / **22** | 22 / 22 / **22** | 0 / 0 / **0** | 22 / 22 / **22** |
| K09 keyword typo | 22 / 22 / **22** | 22 / 22 / **22** | 22 / 22 / **22** | 22 / 0 / **0** |
| K10 open comment | 2 / 22 / **2** | 20 / 20 / **20** | 22 / 22 / **22** | 2 / 22 / **2** |
| K11 `#endif` missing | 22 / 22 / **22** | 22 / 22 / **22** | 0 / 22 / **0** | 22 / 22 / **22** |
| K12 use before declaration | 22 / 22 / **22** | 22 / 22 / **22** | 22 / 22 / **22** | 22 / 22 / **22** |
| **All 264** | 229 (87 %) / 214 (81 %) / **193 (73 %)** | 243 (92 %) / 212 (80 %) / **211 (80 %)** | 162 (61 %) / 182 (69 %) / **150 (57 %)** | 229 (87 %) / 230 (87 %) / **202 (77 %)** |

How the totals break down:

- Line, exact / one off / elsewhere / none:
  - driver 211 / 18 / 35 / 0;
  - app 226 / 17 / 14 / 7;
  - reader 142 / 20 / 36 / 66;
  - Mesa 211 / 18 / 35 / 0.
- Message, scores 2 / 1 / 0:
  - driver 214 / 42 / 8;
  - app 212 / 42 / 10;
  - reader 182 / 15 / 67;
  - Mesa 230 / 25 / 9.

The ES 2 device (homepc-api23) is scored over the 178 texts it gets as ES 1.00. There the driver reaches both in 137 (77 %) and the app in 156 of 187 (83 %). The kinds behave the same; the share differs only because the ES 3.00 ShaderToy texts are left out.

**A fifth voice: the app if a driver wrote Mesa's format.** `DriverLog` run on Mesa's logs gives **no line in 264 of 264** cases. It shows the raw log line, location and all, as the message (section 7).

**What the numbers say, in words.**

- Every compiler finds the line of a misspelled word, a wrong type, a wrong argument count and a use before declaration. The driver also says what is wrong, almost every time.
- Where the driver is weak is the punctuation:
  - **Missing `;`:** it says only "`'float' : syntax error`", naming the next line's first token.
  - **Parenthesis:** it often names a consequence instead of the cause, such as "`'=' : dimension mismatch`", "`'0.x' : invalid number`" or "`'rgb' : vector field selection out of range`".
  - **Missing `}` at the end of a file:** it says "`'' : syntax error`" one line past the end.
- An open comment and a missing `#endif` are caught by the app before the driver, at the right line, in 42 of 44 cases.
- Mesa names the punctuation (`expecting ',' or ';'`, `expecting ')'`), but not a misspelled keyword: `flaot x` gives "unexpected FLOATCONSTANT, expecting ',' or ';'".
- The driver and Mesa put a missing `;` on the right line in 15 of 22 cases. In the other 7, comment or blank lines follow the `;`, and **every** voice, the reader included, reports the next line of code. Nothing that reads tokens can do better without a heuristic.

**Consistency of the message scores.** 164 distinct (kind, message) pairs were scored once, in a shuffled list without the voice's name. 30 of them, drawn at random, were scored again later in a new order without the first scores: **29 of 30 agreed**. The one disagreement was the reader's "expected ; before )" for an extra `)`: 2 the first time, 1 the second. That pair occurs in 6 texts, all in the reader's column. Counted at 1, the reader's K02 "both" falls from 21 to 15 and its total from 150 to 144, and no conclusion changes.

## 5. Ten examples side by side

In each block, the typo (file:line) comes first, then each voice: the line it gives and its words. The driver is the same on the Mac and on the NVIDIA host in every one.

1. **Missing `;`**, Back camera.frag:14 `vec2 st = cameraAddent + uv * cameraOrientation` (two comment lines follow):
   - Driver: `ERROR: 0:18: 'gl_FragColor' : syntax error`, which maps to line 16.
   - App: `Back camera.frag:16: 'gl_FragColor' : syntax error`.
   - Reader: line 16, "expected ; before gl_FragColor".
   - Mesa: line 16, "syntax error, unexpected NEW_IDENTIFIER, expecting ',' or ';'".
2. **Extra `)`**, Texture.frag:33 `float v = texture2D(noise, uv)).r;`:
   - Driver: `ERROR: 0:34: '=' : dimension mismatch`.
   - App: `Texture.frag:33: '=' : dimension mismatch`, the wrong problem.
   - Reader: line 33, "expected ; before )".
   - Mesa: line 33, "unexpected ')', expecting ',' or ';'".
3. **Missing `)`**, Mirror.frag:40 `…abs(diagonal - 1.15);`:
   - Driver and app: line 40, `';' : syntax error`.
   - Reader: line 40, "expected ) before ;".
   - Mesa: line 40, "unexpected ';', expecting ')' or ','".
4. **Missing `}` at the end of the file**, Battery.frag:52:
   - Driver: `ERROR: 0:54: '' : syntax error`, a line past the end.
   - **App: `ERROR: 0:54: '' : syntax error` with no line.**
   - Reader: line 51, "a block is not closed".
   - Mesa: line 52, "syntax error, unexpected end of file".
5. **Missing `}` between functions**, Shapes.frag:14:
   - Driver and app: line 16, `'{' : syntax error`.
   - Reader: line 16, "a function is declared inside a function".
   - Mesa: line 16, "unexpected '{', expecting ';'".
6. **Misspelled name**, Texture.frag:40 `cloor = …`:
   - Driver and app: line 40, `'cloor' : undeclared identifier`.
   - Reader: line 40, "cloor is not declared".
   - Mesa: line 40, "`cloor' undeclared".
7. **Argument given twice**, lib/noise.glsl:24 `mix(…, u.x, u.x)`:
   - Driver and app: `lib/noise.glsl:24: 'mix' : no matching overloaded function found`, through the include.
   - **Reader: nothing**, because it reads the text.
   - Mesa: line 24, "no matching function for call to `mix(float, float, float, float)'".
8. **`vec3` for `vec2`**, Ink/ink.frag:43 `vec3 down = -gravity.xy / 9.81;`:
   - Driver and app: line 43, `'=' : dimension mismatch`.
   - **Reader: nothing.**
   - Mesa: line 43, "initializer of type vec2 cannot be assigned to variable of type vec3".
9. **Keyword typo**, Tilt.frag:27 `ig (resolution.x > resolution.y) {`:
   - Driver and app: line 27, `'ig' : no matching overloaded function found`.
   - Reader: line 27, "ig is not declared".
   - Mesa: line 27, "unexpected '{', expecting ',' or ';'".
10. **Comment left open in a library file**, lib/color.glsl:14 `/* return a + b * cos(…`:
    - Driver: `ERROR: 0:140: 'EOF while in a comment' : unexpected end of file found in comment`, which maps to the last line of Blobs.frag.
    - **App, before the driver: `lib/color.glsl:8: Conditional is not closed by #endif in this file (included from Blobs.frag:12)`, the wrong problem at the wrong line.** The right diagnostic, `lib/color.glsl:14: Block comment is not closed`, is the second in its list.
    - Reader: line 14, "a comment is not closed".
    - Mesa: last line of Blobs.frag, "Unterminated comment".

Two more show the rest of the pattern:

- **Missing `#endif`**, Shapes.frag:5. The driver gives line 1, "'ifdef' : unexpected end of file found in conditional block". The app gives line 1 too, "Conditional is not closed by #endif" (its own check). The reader gives the last line, "a conditional is not closed".
- **Function moved below its caller**, Shapes.frag:41. Driver and app: line 41, `'disc' : no matching overloaded function found`. Reader: "disc is not declared". Mesa: "no function with name 'disc'".

## 6. Where the strict reader is worse than the driver, and where better

**Worse.**

- **Types and argument counts: 66 of 264 typos, a quarter of the set.** The reader reads the text as valid and says nothing in all 22 wrong argument counts, all 22 `vec3`-for-`vec2` and all 22 missing `.0`. This is what the study predicted ("without a type checker"). The driver is right in 65 of these 66.
- **Missing `#endif`:** it places the error at the end of the text (0 of 22 on the right line). Driver and app name the `#if` line in 22 of 22.
- **Brace:** its words are weaker. "a function is declared inside a function", "expected a type" and "expected a value, not else" scored 1 where the driver's `'}' : syntax error` scored 2. As a result, under the study's rule 2 it would put a worse sentence beside a right row in **10** brace cases (4 on the ES 2 device).

**Better.**

- **Missing `;`:** "expected ; before X" names the problem in 22 of 22, where the driver names it in 0. The line is the same, 15 of 22.
- **Parenthesis:** "expected ) before ;" and "expected ; before )" in 21 of 22, where the driver names a parenthesis in 6. In 8 of the other 16, the driver names a wrong problem instead (dimension mismatch, wrong operand types, no matching overload, not enough data for a constructor, field selection out of range, invalid number).
- **Comment left open:** right line in 22 of 22. The driver points at the end of the file. The app's own check already gets 20 of these 22.
- **The same:** misspelled names, built-ins, keywords and use before declaration, 22 of 22 each, like the driver.

**Beside the driver, on the study's rules**, the reader is shown only when the driver refused, and only on or next to the driver's line or where the driver gave none. Then it would turn **33 of 264 typos (12.5 %)** from not right into right:

- 15 missing `;`,
- 14 parentheses,
- 4 braces.

On the ES 2 device it would be 15 of 187 (8 %). The rest of the 53 typos the app does not get right stay that way:

- 7 missing `;` where nobody gets the line;
- 8 brace cases;
- 1 multi-line call;
- 2 parenthesis cases where the reader's place is not next to the driver's;
- the 2 comments in library files, where the app's own check speaks first.

## 7. What the app shows today: wrong line, no line, or the wrong problem

Of the 264 typos, the app today shows a **wrong line in 14** and **no line in 7** (21, 8 %). It shows the **wrong problem in 10**. The cases by cause:

1. **No line when a `}` is missing at the end of a file: 7 cases** (K03-02, -04, -06, -08, -12, -18, -20), on all four devices. The driver reports the line one past the end of the text (`ERROR: 0:54: '' : syntax error` for a 53-line text). `SourceMap.locate` returns null for it, and `DriverLog` then shows the raw log line with no file and no line. **Fix: about 5 lines.** In `DriverLog` (or `SourceMap.locate`), a line one past the end would mean the last line of the text and be mapped there. That gives the right file. Here that is the right line in 4 of the 7, where the brace belonged at the end; in the other 3 it is the right file only.
2. **The wrong problem for a comment left open in a library file: 2 cases** (K10-01, K10-04). The comment swallows the include guard's `#endif`, and `PreprocessorScan` adds "Conditional is not closed by #endif" (at the `#ifndef` line) before "Block comment is not closed" (at the comment's line). The snackbar shows the first. The editor marks both lines, because both diagnostics are in the list. **Fix: about 2 lines.** Add the comment's diagnostic before the conditional's (`project/PreprocessorScan.java:96-103`), since a comment left open explains a conditional left open, but not the other way round.
3. **No line on any driver that writes Mesa's format.** `DriverLog`'s pattern `^.*(\d+):(\d+):\s+(.*)$` needs `digits:digits:` followed by a space. Mesa writes `0:41(2): error: …`. Run on Mesa's 264 logs, the app gave no line in 264 of 264, and its message was the raw text with the location. This matters wherever Android runs on Mesa (Intel or AMD GPUs, as on Chromebooks and Android-x86, and freedreno-based ROMs). Whether any user has such a device is not known. **Fix: about 3 lines**, one more pattern `^\s*\d+:(\d+)\(\d+\):\s*(?:error|warning):\s*(.*)$`. It may be what upstream #82 ("all errors are claimed to be found in line 1") was; that is not verified.
4. **The NVIDIA host:** nothing to fix here. The guest sees the emulator translator's format, which `DriverLog` reads. NVIDIA's own desktop format, `0(12) : error C1008: …`, never reaches an emulator guest. If a device driver wrote it (an NVIDIA Tegra device, not verified), `DriverLog` would give no line; I checked the pattern against that string. **Fix: about 3 lines**, a pattern `^\s*\d+\((\d+)\)\s*:\s*(?:error|warning)\s*\w*:\s*(.*)$`.
5. **The driver's own wrong problem for a parenthesis: 8 cases.** The app passes on the driver's words, such as "dimension mismatch" or "invalid number". Its parser cannot fix this; only a reading of its own can (section 6). It is part of what located errors would add.
6. **The line after a missing `;`: 7 cases.** Every voice gives the next line of code. A fix would be the heuristic "a syntax error at the first token of a line after a line of code that does not end in `;`, `{` or `}`: show the line before". It would take about 15 lines and is not part of the reader; it would equally help the driver's row.
7. **Where line and problem are right but the words are not: 22 + 8 + 12 cases.** "`'float' : syntax error`" for every missing `;`, and similar for parentheses and braces. This is where the driver is "true but unhelpful": the user is at the right line and has to see the missing character there.
8. One multi-line call (K06-17): the driver gives the call's first line, the typo is 3 lines further. Fine for a reader of the code, but scored "elsewhere".

## 8. What it means for the decision

**Kill criterion 1** (`one-front-end.md`, section 5): "the driver already gives the right line and a usable message in 95 % of cases".

| Compiler stack | Driver: right line (exact or ±1) and a message that names the problem | Same, a "true but unhelpful" message counted as usable | What the app shows today (same rules) | Met? |
| --- | --- | --- | --- | --- |
| Emulator translator on the Mac (Apple M1 Pro, GL 4.1 Metal): emulator-5554, emulator-5556 | **193 / 264 = 73 %** | 221 / 264 = 84 % | **211 / 264 = 80 %** (89 % counting "unhelpful") | **No.** 22 points short (15 for the app) |
| Emulator translator on Windows (NVIDIA RTX 3080, driver 621.24), ES 3: homepc-api37 | **193 / 264 = 73 %** (same first message on every text) | 84 % | **211 / 264 = 80 %** | **No.** 22 points short |
| The same, ES 2 context: homepc-api23 (ES 1.00 texts only) | **137 / 178 = 77 %** | 83 % | 156 / 187 = 83 % | **No.** 18 points short |
| Mesa, offline (the study's stand-in) | 202 / 264 = 77 % | 84 % | n/a (the app finds no line in Mesa's format) | **No.** 18 points short |

So the first half of the criterion fails clearly on every stack measured. The criterion's second half (whether the owner would want the app's token and sentence back after a week) is for the owner and was not tested.

**In plain words: located errors would help in about 12.5 % of typos on these stacks.**

- That is 33 of 264: 15 missing `;`, 14 parentheses and 4 braces, all of them punctuation.
- After it, the app's share right with both line and message would go from 80 % to about 92 %.
- It would hurt in about 4 % (10 brace cases where its sentence is worse than the driver's right one), unless rule 2 is sharpened to "show the reader's sentence only when the driver's says 'syntax error' or names another problem".
- It would add nothing for the half of the typo kinds where the driver is already right (names, built-ins, types, argument counts, keywords, use before declaration). It cannot help with the quarter that needs types (argument counts, `vec3` for `vec2`, `.0`), where it is silent.
- Three of the four app faults of section 7 together take about 10 lines, need no reader, and fix every case of "no line" seen here: the `}` at the end of a file, the order of the two pre-driver diagnostics, and the Mesa pattern.

Put against the study's numbers: the study measured 92 % of Mesa's rejections found by the reader, on the same line 95 % of the time. That holds here in kind: where the reader speaks, it is on the right line. But on this realistic typo set it speaks in only 75 % of typos (198 of 264), and only 12.5 % of all typos gain from it over today's app. The case for "more than rename" on the strength of located errors alone is **weaker than the study assumed, but not nil**: the gain is concentrated in the most common keystroke slips, and the rest of the case rests on dimmed branches.

**What this does NOT cover.**

- **No phone driver.** Every driver number here comes from the Android emulator's GLES translator (ANGLE's message style), on an Apple and an NVIDIA host. A Mali, Adreno or PowerVR driver compiles the shader itself and writes its own words and lines.
  - Some of those are known to be terser (for example "L0001: syntax error" on Mali, from memory, not measured).
  - Some may give worse lines, which is what upstream #82 reports.
  - The phone is exactly where the criterion was meant to be judged ("on the emulator and the owner's phone"). **The owner's phone run is still to come, and only when he says the phone is free.** It is one command (section 9), and then the analysis is run again on the new file.
- **Typos one at a time.** Real editing often leaves two faults at once, or one fault in a half-typed block. Neither is measured.
- **The corpus is the app's own examples and fixtures:** clean, commented code. Third-party ShaderToy code with macros was not mutated.

## 9. Raw data, the tool, how to run it elsewhere

Branch `origin/foreman/typo-experiment`, folder `typo-probe/`:

- **The probe** (`build.gradle.kts`, `src/`), the device tool. The one command is `./gradlew :typo-probe:probe -Pserial=<serial>`, or `gradlew.bat …` on Windows, where Home-PC used it. It installs the probe, runs it, pulls `typo-probe/results/<serial>.jsonl` and uninstalls the probe.
- **The corpus** (`corpus/`): `index.jsonl`, plus `texts/<id>.frag|.vert|.src` holding the assembled texts and the mutated sources.
- **The device results** (`results/`): `emulator-5554.jsonl`, `emulator-5556.jsonl`, `homepc-api37.jsonl`, `homepc-api23.jsonl`.
- **The analyser:**
  - `jvm/analyse.sh` runs `TypoAnalyse` (the app's `DriverLog`, the spike's strict reader copied into `jvm/src/exact/` with a small public `Verdict`, and Mesa) over every result file. It takes about 6 s.
  - `jvm/py/keys.py` normalises the messages, `jvm/py/tabulate.py` makes the tables and `jvm/py/decision.py` makes the numbers of section 8 (all `python3 -I <script> typo-probe`).
- **The scored data** (`analysis/`):
  - `voices.jsonl`: every voice of every text, with its raw log, mapped line and line score;
  - `keys.jsonl`;
  - `sheet.tsv`: the blind list of 164 messages;
  - `scores.tsv`: my scores;
  - `rescore-sheet.tsv` and `rescore.tsv`: the 30 scored twice;
  - `scored.jsonl`: everything joined;
  - `tables.md`: full tables per voice and device, with exact / ±1 / elsewhere / none and the message scores 2 / 1 / 0.

**For the phone run:** run `./gradlew :typo-probe:probe -Pserial=<phone serial>`. Then, on the Mac, run `typo-probe/jvm/analyse.sh`, then the three Python scripts. New messages, if any, have no score yet: `keys.py` writes them into `keys.jsonl`, `tabulate.py` stops with a `KeyError` on them, and they are to be scored blind like the others before the tables are made.

## 10. Not verified, and changes to the plan

**Deviations from the plan in section 1, or details it did not fix.**

- **The app stops before the driver** in 45 mutants (all K10, all K11 and K09-03). For the driver's voice, those texts were made as the original assembled program with the same edit, line for line, a deleted line made empty. **Fault found afterwards:** these 45 texts also brought back the `#pragma se` lines that the app's pipeline blanks (`TypoGenerate.derive` copies every line of the mutated file). Drivers and Mesa only warn about an unknown pragma, and no line shifts. Every voice's first error was checked to be unaffected, so the scores stand. The app's voice for these 45 is its own diagnostic, as the plan said.
- **A line past the end of the text** (a driver's "end of file") is mapped to the text's last line from a file. This is for the driver, reader and Mesa voices; the app's voice uses `DriverLog` unchanged, which gives no line there. The plan did not say this.
- **"The first error message"** of a raw log is its first line that is not a warning and not Mesa's "Info log for <file>" header.
- **Rubric readings decided while scoring, before any table was made:**
  - **K03:** "names a brace" was read as the closing brace `}` (the plan's parenthesis "`}` expected or unexpected"). "unexpected '{'" scored 1.
  - **Wrong expectation:** a parser message that expects a wrong token ("unexpected '{', expecting ';'" for a missing `}`; "expecting ',' or ';'" for a misspelled keyword) scored 0 as misleading.
  - **ANGLE's "no matching overloaded function found"** for a word that is no function (a misspelled name, a moved function, `ig (…)`) scored 2 for K04, K05, K09 and K12, since it names the word.
- **Blindness:** the list hid the voice's name but not its style. Mesa's and the reader's sentences are recognisable. The 30 re-scored items were drawn from the 164 distinct pairs, not from the 1,848 scored rows.
- **The ES 2 device:** the 86 mutants of ES 3.00 programs are not scored for the driver there (section 3), and are scored for the app only where its own check comes first.
- **The Home-PC results** were made by another worker with the same tool and corpus (commit c019e901). I did not see those runs.

**Not verified.**

- **No phone driver.** No Mali, Adreno or PowerVR; see section 8. The emulator translator also accepts `#version 300 es` on an ES 2 context, which a phone's ES 2 driver would not.
- **Other driver formats:** the NVIDIA and Mali log formats quoted in section 7 are from memory and were not seen on a device. Only the pattern was tested, against those strings.
- **The fix sizes in section 7** are estimates from reading the code. No fix was written or tested.
- **The reader** is the spike's (1,813 lines), not a production reader. Its K02 and K03 messages are its own wording; a production reader may word them better or worse.
- **Kill criterion 1's second half**, whether the app's sentence would be missed after a week, is the owner's judgement.
