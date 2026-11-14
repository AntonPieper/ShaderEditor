# Where GLSL ES and OpenGL ES are assumed

Status: audit of the code and documents as they are, with a ranked list at the end. Decided before this audit: the app stays on OpenGL ES with GLSL ES. No second backend and no second language is built or designed here. The question is only which assumptions are cheap to keep behind a seam now and expensive to move after a release.

## How to read this

An assumption is **behind a seam** when only code whose job is GLSL or GL knows it: the GL engine in `opengl/`, the tokenizer in `syntax/`, the converter of old shaders. It **leaks** when something that should not care knows it: the project format on disk (SPEC.md), the plan the engine receives (`RenderPlan`), the pass graph and preparation, the engine's interface to its hosts, and the navigation and file layers.

Cost of making a place neutral now: **trivial** (wording, a rename), **small** (one pull request of ordinary size, no behaviour change), **large** (a new abstraction with a second implementation needed to prove it).

"Another language" below means one of the two that have a documented way onto Android: WGSL (through WebGPU) and AGSL (`RuntimeShader`, Android 13). Both have no preprocessor. "Another backend" means Vulkan or WebGPU. What they would need is written down to judge the cost of a leak, not as a design.

The project format weighs highest, because files on users' storage cannot be changed by an app update.

Sections 1 to 7 describe the code and documents as they were audited. The Status column of section 8 says what has been changed since, and how.

## 1. Project format (SPEC.md)

| # | Where | What is assumed | Seam or leak | What another language or backend needs | Cost now |
| --- | --- | --- | --- | --- | --- |
| F1 | SPEC 3, 3.1, 5 | Host settings are `#pragma se ...` lines: the language has a preprocessor whose compiler ignores a pragma it does not know | Leak in wording: the pragma is presented as the format. The text after it (`input "path" key=value`, `target ...`) is already independent of GLSL | A carrier that the language has (section 7, H1) | trivial |
| F2 | SPEC 3.1 | An input attaches to `uniform [precision] sampler-type name;` | Leak in wording, the grammar is GLSL's | Its own declaration form: WGSL declares a texture and a sampler separately, AGSL a `uniform shader` | trivial |
| F3 | SPEC 2.1 | A pass "must have a `main`"; Run this file is offered when the text "defines `main`" | Leak in wording | Its own entry point (H9) | trivial |
| F4 | SPEC 2.1, language table | A text file the table does not list is GLSL, whatever its name: `blur.wgsl` holding GLSL is a valid pass today | **Leak with a cost after release.** A row added later for `.wgsl` would change the meaning of a project that is valid today, which by the format's own rule (SPEC 1, format evolution) needs `projectVersion` 2 | The names other shading languages are known by must not mean GLSL | small |
| F5 | SPEC 8 | `#include "path"`; "included files must be GLSL text"; `#version` and extensions stay with the including file | The rules are the app's own (H2); the spelling and the `#version` rule are GLSL's. No rule says an include is in the language of the file that includes it | That rule, and a carrier | trivial |
| F6 | SPEC 3 | "Do not inject declarations" for engine uniforms; the user declares `uniform float time;` and the host finds it after linking | Correct for GLSL, stated as a rule of the format | WGSL has no loose uniforms, Vulkan GLSL needs a block: the host generates or prescribes the declaration there | trivial (scope the sentence to GLSL) |
| F7 | SPEC 3 | `#define FTIME_PERIOD n` is a host setting written as a macro | Leak, kept for old shaders | A `se` option; the macro stays for GLSL | not now: an addition later, no existing file changes |
| F8 | SPEC 3, 8 | `#version`, `#extension`, line continuation as in GLSL ES 3.00, ASCII-only code | GLSL's own rules, in the two sections that describe GLSL | Nothing: they do not apply | none |
| F9 | SPEC 4, 7 | `(0,0)` is the lower-left corner of a picture and of a pass output; touch positions use the same origin | A convention of the project, implemented in the engine. Nothing on disk records an orientation: a picture is a picture | See H5 | none |
| F10 | SPEC 3.2 | A uniform without a provider "reads zero, as GL defines" | Leak in wording; the rule itself (warn, run anyway) is the project's | A backend that must bind something binds zeros | trivial |
| F11 | SPEC 4, 5 | "On a GLES3 context use sampler objects", GLES2 limits, "not inferred from GLES version" | Capability rules of the GL engine, written into the format document. They decide nothing on disk | Its own capability rules | none now; move to ENGINE.md when the document is next reworked |
| F12 | SPEC 1, manifest | `projectVersion`, `entry`; default entry `main.frag` | Neutral. The default name is a GLSL name by convention, and there is exactly one default | A project in another language names its entry in the manifest | none |
| F13 | SPEC 2, 2.1 | Paths, kind from bytes, role from references | Neutral | Nothing | none |
| F14 | SPEC 10 | "Shared as a single GLSL document", "successful GLSL compilation" | Leak in wording | Nothing | trivial |
| F15 | `AndroidManifest.xml` | The open-with filters repeat the endings of the language table by hand | Unavoidable: a manifest cannot read the table | A new row is two edits | none |

## 2. The plan and its preparation (`project/`)

| # | Where | What is assumed | Seam or leak | What another language or backend needs | Cost now |
| --- | --- | --- | --- | --- | --- |
| P1 | `RenderPlan.Input.samplerType` | A GLSL type name as a string: `"sampler2D"`, `"samplerCube"`, `"samplerExternalOES"`; the engine compares it with `"samplerCube"` (`PlanBindings`) | Leak in the plan | How the texture is read: flat, cube, or a texture the platform owns | small |
| P2 | `RenderPlan.Program.versionLine` | Generated lines go after `#version` | Leak in the plan, by name and by comment. What the engine needs is "the line after which it may add lines" | The same value, 0 where there is no such line | trivial |
| P3 | `RenderPlan.Program` | The source lines are in a language the plan does not name | Leak by omission: ARCHITECTURE 2 lists "language/version" as owned by the program description | The language of each program, so an engine can say it does not compile one | trivial, and as cheap later: nothing would read the field today |
| P4 | `Preparation.prepare(..., Map<String, String> reserved)`, `OpenProject.prepare(reserved, ...)` | The engine's builtin uniforms are handed in as GLSL type keywords by name, made by `UniformTable.types()` | Leak: the GL engine's keywords cross the one signature every host calls | Names, and for the samplers among them how they are read | small |
| P5 | `PassGraph` | Calls `SourceAssembly` and `HostDirectives` itself; `\bmain\b` and `\bbackbuffer\b` over `PreprocessorScan` code; compares with `"sampler2D"`; messages say "defines no main" and "uniform sampler2D" | Leak: the graph, which decides roles, knows how GLSL is read | One place that reads a pass file in its language and returns what the graph needs | small |
| P6 | `OpenProject.definesMain`, used by `runs(path)` | Tokenizes with `GlslLexer` and looks for a function `main` | Leak into the file layer, the only one there | The language answers whether a text can run | small |
| P7 | `SourceAssembly`, `PreprocessorScan`, `HostDirectives` | Everything that reads GLSL for a build: comments, `#` directives, `#include` expansion, `#version` placement, `#pragma once`, conditionals, the carrier and attach rules, precision words, ASCII check | GLSL's own code; after P5 nothing neutral calls it | A reader of its own with the same result | none after P5 |
| P8 | `HostDirective` | Parses the text after the carrier | Neutral already, except `isHostPragma` and the spelling help, which name the pragma | Reuse as it is | none |
| P9 | `RenderPlan.Sampling`, `Target`, `Source`, `Size`; enums `Filter`, `Mipmap`, `Wrap`, `Format`, `Frame` | Named after what they mean; `GlSampling` maps them to GL constants | Behind the engine seam. The enums are declared in `HostDirective`, which is only a matter of where they live | Its own mapping | none |
| P10 | `RenderPlan.Sampling.of`, `PassGraph`, `Preparation` | The name `backbuffer`, and that every other engine sampler is a camera | The names of builtins are the host's API and part of the format (SPEC 3.2), so they are independent of the language. The camera guess belongs to the engine | Nothing for a language | none |
| P11 | `SourceMap`, `SourceLocation`, `ProjectDiagnostic` | File and line, no column | Neutral. A compiler that reports columns loses them | A column, added without changing a caller | none |
| P12 | `ProjectLimits`, `CapturedFiles`, `ProjectManifest`, `FileKind` | Nothing about a language | Neutral | Nothing | none |

## 3. The engine interface (`opengl/`)

| # | Where | What is assumed | Seam or leak | What another backend needs | Cost now |
| --- | --- | --- | --- | --- | --- |
| E1 | `ShaderEngine`: `setPlan(Preparation, quality)`, `request()`, `Listener.onDiagnostics(List<ProjectDiagnostic>)`, `Reporting`, thumbnails as PNG bytes | Nothing about GL: a build is offered as a prepared project, and errors come back as diagnostics with file and line | Neutral | The same calls | none |
| E2 | `ShaderEngine.onContextCreated(int version)`, `onSurfaceChanged`, `drawFrame`, `onContextLost` | The host owns an EGL context and a GL thread and tells the GLES major version: the shape of `GLSurfaceView.Renderer` | The hosts are `ShaderView` and the wallpaper view, both GL surface views. `ShaderEngine` is a class, not an interface | Another engine class and another view to host it (the research names `SurfaceView` and `AndroidExternalSurface`); whether a wallpaper surface works with either is not known | large |
| E3 | `EngineHost` | Context, permissions, camera lifecycle | Neutral | Nothing | none |
| E4 | `PlanSupport.refusals`, `PlanSource.of`, `PlanBindings.check` | What this runtime and this context can do: one pass, RGBA8, `#version 300 es` needs GLES 3, GLES2 texture limits | Behind the seam: answered as diagnostics when a candidate is built, as SPEC 9 describes. Nobody asks which language a context compiles, because there is one | The same mechanism, and a refusal of a program in a language it does not compile (P3) | none |
| E5 | `UniformTable.BUILTINS`, `UniformDef`, `GlslType` | One enum holds the GLSL keyword and the GL type constant of a builtin; `UniformDef.getType()` is public and read by the Add uniform list | Inside the engine, but language and API are one type. Only P4 crosses the seam | A type name per language beside the neutral type | small, not now: nothing outside the engine needs it once P4 is done |
| E6 | `DriverLog` | The driver's info log format `0:LINE: message`, `WARNING` prefix | Behind the seam (H8) | Its compiler's format | none |
| E7 | `PlanSource` | Finds the `#version` number, adds `#define SHADER_EDITOR 1` and the external-image extension, picks the vertex shader of the same version, reads `FTIME_PERIOD`; uses `project.PreprocessorScan` | The GL engine compiles GLSL ES, so this is its own knowledge | Its own prelude | none |
| E8 | `PlanBindings`, `GlProgram` | Samplers and uniforms are found by reflection after linking; an unannotated `backbuffer` is bound when active | Behind the seam; the plan's comment says the implicit backbuffer is known only after linking | WebGPU has explicit bindings: such a pass declares its inputs | none |
| E9 | `UniformWarnings`, `Reporting.textureFix()` | Messages quote GLSL: `#pragma se input "path"` | The host supplies the fix text; the rest is the GL engine's | Its own texts | none |

## 4. The editor (`syntax/`, `editor/`, `widget/`, `fragment/`)

Audited, not changed: another change set works there.

| # | Where | What is assumed | Seam or leak | What another language needs | Cost now |
| --- | --- | --- | --- | --- | --- |
| L1 | `syntax/GlslLexer`, `GlslWords`, `Style` | GLSL tokens; `Style` has GLSL categories (`TYPE_SAMPLER`, `PRECISION`, `DIRECTIVE`) | GLSL's own code | A lexer and a word list; `Tokens` and most styles are reusable | none |
| L2 | `editor/LineStyles`, `editor/Analysis`, `syntax/LexedLine` | Call `GlslLexer.lexLine` directly for every document, also for `shader-project.json` or a README | Leak inside the editor: nothing selects a syntax. LANGUAGE.md plans one `Syntax` chosen from the language table where a file is opened | That selection | small, not mine to do |
| L3 | `syntax/Outline`, `GlslCompletion`, `fragment/CodeViewSourceEditor` | Top-level declarations and completions as GLSL has them; builtin uniform names from `UniformTable` | Same as L2 | Same | with L2 |
| L4 | `editor/AutoIndent` | A line ending in `{` or an operator indents, `//` continues | Holds for every C-like language, WGSL and AGSL included | Nothing | none |
| L5 | `editor/AddUniform` | Inserts a `uniform` statement after the last one, or after `#endif` | GLSL text editing | Its own statement | with L2 |
| L6 | `widget/ShaderToySource`, the check in `CodeViewSourceEditor` | A text with `mainImage` and no `main` is wrapped for GLSL ES | GLSL only by nature | Nothing | none |
| L7 | Error lines | Come as `ProjectDiagnostic` with file and line | Neutral | Nothing | none |

## 5. The converter of old shaders (`project/legacy/`)

`LegacyShaderConverter`, `LegacySamplerSettings`, `PresetShader` and `DatabaseProjectFiles` read shaders from the database, which are GLSL ES and nothing else, and write GLSL with `#pragma se` lines. They are GLSL by nature and sit behind `project/legacy`. The file they write is named through `ShaderLanguage.PASS`. Nothing to make neutral.

## 6. Examples and New-file templates

| # | Where | What is assumed | Seam or leak | Cost now |
| --- | --- | --- | --- | --- |
| X1 | `docs/project-mode/examples/` | GLSL ES 1.00 and 3.00 projects | Content | none |
| X2 | `activity/managers/NewFile`, `R.raw.new_shader` | A new shader gets one GLSL ES 1.00 template whatever its name says | Leak, harmless while one language runs | A template per language, chosen through the table: trivial when there is a second one |
| X3 | `widget/NewSheet`, `DocumentProjectFiles` | Suggest `.frag` and `.glsl` from `ShaderLanguage.PASS` and `INCLUDE` | Through the table | none |

Navigation (`navigation/`) knows no language: it reads `FileKind.hint` for an icon and nothing else.

## 7. The hard ones

**H1. Host settings are `#pragma se` lines; WGSL and AGSL have no preprocessor.** There are three places such a language could carry them.

- *A line comment in the pass file* holding the same text, behind a marker chosen with that language. Every language in question has `//` comments. ISF has done the like for years: a JSON block in a leading comment of a GLSL file.
- *The manifest*, listing inputs per file.
- *The language's own attributes.* WGSL has `@group` and `@binding` only and cannot be extended; AGSL has `layout(color)` and nothing else.

The comment is the answer, and the manifest is not: the manifest is optional and "nothing in this format needs one" (SPEC 1); an opened loose document is a project without one; an input sits next to the declaration it describes and names nothing twice, where a manifest would repeat file and variable names and break on a rename; and Share, Save a copy and Convert move files, not manifest fragments.

What that means for v1: nothing changes on disk. `#pragma se` is the natural carrier in GLSL (compilers ignore pragmas they do not know, editors colour them) and stays the only spelling there; a second spelling for GLSL would be two ways to say one thing, which SPEC 3 already refuses for `se.input`. What must be true now is that the format is the directive text and the carrier belongs to the language. The code already has that split (`HostDirective` parses the text, `HostDirectives` finds the carrier); the SPEC does not say it (F1), and the pass graph reaches past it (P5).

**H2. `#include` is the app's own preprocessing.** GLSL ES has no `#include`; the app expands it before the compiler, by rules of its own: a literal relative path, no macros, every include resolves even inside `#if 0`, repeats expand repeatedly. So the meaning is neutral already and only the spelling is GLSL-like. A language without a preprocessor would write it on its comment carrier. Text inclusion fits WGSL and AGSL, whose top-level declarations can be concatenated. The one rule missing is that an include is in the language of the file that includes it (F5); it is empty while one language exists, and costs a sentence.

**H3. Uniform names and types the host provides.** The names (`time`, `resolution`, `touch`, `backbuffer`, ...) are the host's API and can be the same in any language. The types are stated as GLSL types (`UniformTable`, SPEC 7), and GLSL lets the user declare a loose uniform that the host finds after linking. WGSL has no loose uniforms (a struct with `@group` and `@binding`), Vulkan-flavoured GLSL needs a block, AGSL has loose uniforms but no samplers. So "the host never injects a declaration" is a GLSL rule (F6), and another language would get a generated or prescribed declaration. What leaks today is smaller: GLSL keywords cross `prepare` (P4).

**H4. Sampler types and texture parameters.** The parameters are neutral already: `filter`, `min`, `mag`, `mipmap`, `wrap` and `format` are enums that `GlSampling` maps to GL. "Sampling belongs to the input" (SPEC 4) also fits WGSL, where a sampler is its own object, and AGSL, where filter and wrap are set on the child shader by the host. The type is not neutral: the plan carries the GLSL keyword (P1). Three ways to read a texture cover what exists: flat, cube, and a texture the platform owns (GL's external image, WGSL's `texture_external`).

**H5. Coordinates.** GLSL's window and texture coordinates have Y up; AGSL's `fragCoord` has Y down, and it samples children in pixels. The format says once that `(0,0)` is the lower-left corner of pictures and pass outputs and that touch uses the same origin (F9). No file records an orientation, and the cubemap strip is defined in image terms (face order, rows top to bottom). So this is not a risk for files on disk. Whether another language keeps the project's convention through a generated wrapper or uses its own is that language's decision and is left open here.

**H6. Precision qualifiers.** They appear in the template and examples, in the attach grammar (`[lowp|mediump|highp]`), in the converter and in the editor's word list. All of it is code that reads or writes GLSL. Nothing neutral knows them once P5 is done.

**H7. GLSL ES versions and the 100 / 300 es split.** The version is found by `SourceAssembly` (where generated lines go, and that an include has none) and again by `PlanSource` (which vertex shader, which extension name, whether the context can compile it). The second is the GL engine's business. The first reaches the plan as `versionLine` (P2). The format's rules about it (SPEC 3: never translate 1.00 to 3.00; SPEC 11: `textureSize` needs `#version 300 es`) are rules about GLSL and stay in the GLSL sections.

**H8. Compiler error formats.** `DriverLog` reads the GL drivers' `0:LINE: message` and maps the line through `SourceMap` to a file and line; lines the engine generated map to an explicit generated origin. The engine hands back `ProjectDiagnostic`s. Another compiler's format (glslang prints `file:line: error`, WGSL compilers add a column, `RuntimeShader` throws with a text) is parsed by its engine and mapped with the same `SourceMap`. A translator between languages would have to map lines through the translation; the research found that documented nowhere. Nothing leaks here.

**H9. "Defines `main`" as the test for a file that can run.** Three places ask it: the pass graph (a regex for the word `main` in comment-free code), `OpenProject.runs` (a tokenizer scan for a function `main` with a body), and the editor's ShaderToy check. WGSL marks entry points with `@fragment` and any name; AGSL has `main` with another signature. The neutral question is "does this text have an entry point", answered by the language (F3, P5, P6).

**H10. The `ShaderLanguage` table.** `ShaderLanguage.of` and `listed` have no caller in production code; only the constants `PASS` and `INCLUDE` are used. So the table is a statement of intent. Two things follow. The classes that should ask it do not (P5, P6). And its last row, "anything else is GLSL", gives GLSL meaning to names that belong to other languages (F4).

## 8. Ranked: what to do now

Ordered by what a release would make expensive.

| Rank | Item | Why now | Cost | Status |
| --- | --- | --- | --- | --- |
| 1 | **F4** Reserve the names of other shading languages in the table, and say that an include is in the language of its includer (F5) | The only place where waiting changes the meaning of files that are valid today. After a release it costs `projectVersion` 2 | small | open |
| 2 | **F1, F2, F3, F5, F6, F10, F14** Say in SPEC what is the format (directive text, options, sources, sampling, targets, the graph) and what is GLSL's way of writing it (carrier, declaration, entry point, include spelling) | The document is what a later reader, and a later language, is held to. Words only | trivial | done: SPEC terms, 2.1, 3, 3.2, 8, 10 and 13 |
| 3 | **P1, P2, P4** The plan and `prepare` carry no GLSL names: how a texture is read instead of a type keyword, the lines that stay first instead of the version line, builtins without keywords | The plan is the contract between preparation and every engine, and all hosts call `prepare`. Each is a rename or a small type today and a migration once a second engine reads the plan | small | done: `RenderPlan.Input.texture` is a `TextureKind` (flat, cube, external) that `HostDirectives` maps from the GLSL type; `RenderPlan.Program.head` counts the lines that stay first; `prepare` takes `Builtins` (names, and how the samplers are read) from `UniformTable.builtins()` |
| 4 | **P5, P6** One place reads a file in its language, chosen through the `ShaderLanguage` table; the pass graph and `runs(path)` ask it | Roles are decided in the graph. While it reads GLSL itself, every later rule about roles is written against GLSL | small | open |

## 9. Ranked: what to leave, and what would change that

| Item | Why it is left | Trigger |
| --- | --- | --- |
| **E2** `ShaderEngine` as an interface, a second view host | Large, and unprovable with one engine. ARCHITECTURE 2 forbids interfaces without a real second implementation. What hosts exchange with the engine is neutral already (E1) | A decision to build a second backend. Its first step is a spike on whether a wallpaper surface can host it |
| **P3** The plan does not name a program's language | A field nothing reads would repeat what the table is today. Adding it later costs the same | A second language: its first engine check is "I do not compile this one" |
| **E5** `GlslType` as language and API in one | Inside the engine after P4 | A second language, or a second engine that shares the builtin table |
| **L2, L3, L5** The editor selects no syntax | Another change set owns the editor; LANGUAGE.md has the design (one `Syntax` from the language table) | The first file the editor should not colour as GLSL: the manifest, or a second language |
| **F7** `FTIME_PERIOD` as a macro | Old shaders rely on it; an `se` option can be added later without changing a file | A language without macros |
| **F9, H5** Coordinates | Nothing on disk depends on it | A language with Y down |
| **F11** GL capability rules in SPEC 4 and 5 | They decide nothing on disk | The next rework of SPEC or ENGINE.md |
| **P11** No column in diagnostics | GL drivers report none | A compiler that reports columns |
| **X2** One template for a new shader | One language runs | A second row in the table that runs |
| **P10** The camera guess in `Sampling.of` | Independent of the language | A new kind of engine sampler |
| **F8, F12, F13, F15, P7, P8, P9, P12, E1, E3, E4, E6 to E9, L1, L4, L6, L7, X1, X3** and the converter | Neutral already, or GLSL's and GL's own code behind a seam | none |

## 10. Not verified

- The names other languages' tools use (`.wgsl`, `.agsl` and the like) are taken from common use; the research found no written convention for them in the documents it read.
- Nothing here was tried against a second compiler or backend. "What another language needs" is read from the research report's sources, not from an experiment.
- The editor rows were read, not exercised.
