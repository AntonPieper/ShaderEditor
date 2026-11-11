# Design decisions and alternatives

This records why the contract takes its present shape. SPEC.md and UX.md define the behavior. Earlier proposal spellings are design alternatives, not shipped syntax requiring aliases.

## Start with the user's tasks

| User task | Minimum useful model |
| --- | --- |
| Write a shader immediately | New creates a valid `main.glsl` in a named folder at the displayed default location |
| Work in an existing repository/folder | Open the granted folder in place, then run a file; no initialization/import step |
| Edit in another Android app | ShaderEditor user files appear in the native picker through DocumentsProvider |
| Use an image or another shader's output | An adjacent annotation names its path; GLSL names the variable once |
| Reuse calculations | Literal includes; wrapper shader files when distinct configured passes are needed |
| Keep previous state | Backbuffer for this pass; `frame=previous` for another pass |
| Send the result | Package the selected shader's dependencies, independent of whether it compiles |
| Apply wallpaper reliably | Explicitly install the current valid revision; source access can later disappear |

A folder is a permission/path boundary, not a container the application must import, classify, or own. A project need not be initialized before it can be browsed. The running entry is explicit and distinct from the file currently being edited.

## Pragma namespace spelling

Choose **`#pragma se input`** and **`#pragma se target`**.

```glsl
#pragma se input "assets/noise.png"
uniform sampler2D noise;
```

The resource stem appears in the path and in the declaration, with no repeated binding target. They need not match: `"assets/blue-noise.png"` may feed a variable called `jitter`. Filter/wrap remain optional arguments on the same annotation.

| Form | Assessment |
| --- | --- |
| `#pragma se input` | Chosen. A simple namespace token and command token; no punctuation rule or implied expression syntax. Easy to extend completion after `se `. |
| `#pragma se.input` | Reasonable runner-up. Visually compact and familiar as dotted qualification, but introduces a punctuation convention without reducing repetition or semantics. |
| `#pragma se::input` | Readable to C++ users, but adds two punctuation characters and resembles a C++ namespace although the source is GLSL. No functional benefit here. |
| `#pragma se_input` | Easy to tokenize as one identifier, but flattens app namespace and command into a naming prefix. Equally implementable; less clear as a small family of directives. |

This is a language-design choice, not a claim that dots or colons are inherently invalid in a custom pragma. Host metadata is consumed before driver compilation. No candidate spelling magically makes resource bindings portable to tools that lack ShaderEditor's preparation step. Supporting several equivalent spellings would add documentation, completion, testing, and maintenance cost without another user capability.

Do not use `$`, `@`, `_`, or `this` placeholders. Adjacency already identifies the declaration, so a placeholder adds a token and a rule while conveying no additional information. The rule is deliberately local: one annotation, then one direct uniform declaration, with only comments/whitespace between them. A misplaced annotation produces a located error instead of searching for a later suitable variable.

Rejected shortcuts include deriving file paths from variable names, generating GLSL declarations from annotations, and putting host metadata in arbitrary comments. They save a line at the expense of hidden lookup rules, worse GLSL tooling, or accidental interpretation of prose. Ordinary GLSL remains explicit; insertion UI generates the short form.

## Storage architectures considered

| Architecture | Consequence |
| --- | --- |
| Import everything into an app-owned workspace | Simple internal paths, but creates duplicate files, export/sync decisions, and surprising external-edit behavior |
| Managed projects plus linked projects plus loose-document modes | Models Android details directly, but makes users learn storage categories and multiplies branches |
| Broad filesystem access with raw paths | Does not fit arbitrary Android document providers or current scoped-storage constraints |
| One native document workflow with own DocumentsProvider | Chosen. Own files are another picker location; grants/capabilities stay implementation details |

Termux provides the useful UX precedent: app-owned user files are visible through Android's provider system. It does not remove Android's directory restrictions or make every provider support rename, transactions, or filesystem watchers. The implementation handles those actual boundaries behind the same actions. No universal VFS or sync service is needed.

Keep the in-app file list scoped to the opened folder. Let the system picker navigate storage locations. Share/copy the dependency closure by default because the opened directory may contain unrelated files. Store only drafts, caches, recents, and installed wallpaper snapshots privately outside the exposed user-files root.

## Rendering architectures considered

| Architecture | Consequence |
| --- | --- |
| Extend the old texture-name/comment resolver indefinitely | Lowest initial disruption, but new features accumulate inference rules and compatibility branches |
| Manifest declares all assets, bindings, and graph nodes | Explicit, but duplicates source relationships and makes a simple shader require coordination across files |
| Rich shader DSL with generated declarations | Concise at first, but requires a new language/tooling surface and a growing translator |
| Native file annotations plus one resolved execution model | Chosen. Dependencies stay next to declarations; legacy translation stays at one fixed boundary |

Separate program source, input binding, and pass instance internally. File syntax creates one default pass per path. This supports a simple present model without making a future multiple-instance frontend require a different renderer. It does not justify implementing graph editing, override precedence, instance IDs in files, or a plugin system now.

The October contract kept DB shaders behind a permanent adapter. That was reversed on 2026-11-11: every old setting received an exact project form, so a converter replaces the adapter and the database is eventually removed (see the end of this file).

## Deliberate limits and remaining implementation choices

Literal include assembly and adjacent annotations require a lexer and source maps, not a complete GLSL preprocessor. Reject host metadata in conditional blocks/includes and cyclic includes even with guards. These constraints make dependency collection deterministic for rendering and sharing. Explain them at the relevant error and in help.

The graph follows both current and previous inputs for reachability, but only current inputs constrain execution order. Freeze previous outputs for the entire frame. This avoids order-dependent feedback and gives previous-only producers a clear meaning.

Sampling is an input property. GLES3 sampler objects implement that directly. GLES2 has no independent sampler state, so one program cannot sample one texture with two different settings there; that case is an error on GLES2 and is not emulated with copies (see the review below).

The implementation sessions choose concrete class names, reasonable admission limits, and the smallest correct backend strategy. They must preserve observable semantics and record actual test evidence. Do not implement deferred features to make the architecture look complete.

## Reusing code across shaders

The first request in issue #191 was a personal library of functions shared by many shaders. The format has no include search path and no global library, and it needs none: the project root is whichever folder the user opens, and "Run this file" picks the shader.

```text
Shaders/                 <- open this folder
  lib/sdf.glsl
  clouds/main.glsl       #include "../lib/sdf.glsl"
  tunnel.glsl            #include "lib/sdf.glsl"
```

Share and Set wallpaper collect only what the running shader depends on, so one large folder costs nothing. A shader folder opened on its own cannot reach a sibling `lib/`; the missing-file diagnostic says so. Library code bundled with the app, if it is ever added, follows the texture rule: inserting it copies it into the project.

Include search roots with vendoring on export were considered in June (ADR 0003) and rejected: they make a project depend on app state, and they need manifest keys, a search order, and a collision rule to solve what a parent folder already solves.

## Review of 2026-11-11

A first-principles review before S1 kept the core of the October contract and changed the points below. The October text is in git history.

**Kept, and why.** Paths as the only identity removed pass IDs, asset IDs, basename collisions, and the override pragma that the original memo needed to resolve them. Sampling on the input removed asset sidecars. Copying library resources removed `global::` and made every project self-contained. The cost is one generated line per input, which is the right trade.

| Change | Reason |
| --- | --- |
| GLES2 texture variants removed; the one-program conflict is an error | The mechanism needed per-frame copies, refresh ordering, and memory budgeting to serve one case: two inputs of one program, same source, different settings, on a device without GLES3. The app already asks for a GLES3 context first, where sampler objects do this for free. The spec's own rule for unsupported combinations is to fail clearly. |
| Failed-frame recovery and the retained presentation copy removed | A draw in a linked, complete plan does not fail short of context loss, which has its own path. The clause could not be tested (R12 had no way to induce it) and implied per-draw error polling. |
| Old and candidate render targets no longer coexist | Activation resets all history, so the old targets are worthless the moment the candidate wins. Keeping both doubled peak GPU memory for full-screen float histories on phones. Programs and images still build as a candidate, so a broken edit keeps the old preview. |
| One running-shader rule | The October rule changed what "Run this file" did depending on whether an optional manifest existed, and reverted it silently at session end. Three sources of truth are now one ordered list. |
| A loose document is a one-file project | Proving that a document lies inside a granted tree needs provider-optional calls that do not exist on every supported API level, and it fails for documents from view/share intents. Opening the folder is one tap and always works. |
| Rename does not rewrite references | Rewriting needs every referencing file, which means reading the whole folder; the folder may be a repository. Located missing-file diagnostics already show what to fix. |
| Paths are literal, without JSON escapes | Escapes were specified for `se input` and not for `#include`, so one path could need two spellings. No escapes means one rule and no JSON parser in the lexer. |
| NFC comparison of path segments | Archives made on macOS store decomposed names. Without this, a visibly identical path fails to resolve. |
| Format evolution rule | "No manifest means v1 permanently" and "unknown directives are errors" together left no stated way to add anything. Additions that old readers reject keep the version. |
| Engine-generated lines stated | "Do not inject declarations" left `SHADER_EDITOR`, the camera extension, and `FTIME_PERIOD` undefined for native files. |
| Three conversion outcomes (later replaced by exact conversion) | Legacy default sampling is nearest-min, linear-mag, repeat. `filter` sets both, so under the October text nearly every textured shader would have reported a conversion limitation. |
| Add texture fix for library-named samplers | Every ShaderEditor shader shared so far is written for the legacy dialect. Opening one as a file must lead to a working shader in one step, without guessing modes. |
| `ProjectFiles`, `prepare`, `PlanRuntime` fixed as seams | Nine sessions with fresh contexts need the same three shapes. One `prepare` result also guarantees that rendering, sharing, and wallpaper snapshots agree on what a shader depends on. A directory store makes storage logic testable on the JVM and lets a wallpaper snapshot be an ordinary project. |
| Multipass moved after storage, editor, and wallpaper | In the October order nothing was usable until S6, and S3 rerouted every existing shader through a new renderer first. The format still includes multipass; only the order changed. |
| Reference capture before the renderer reroute | The checkout has no tests. Without a baseline, "existing appearance preserved" (L01) cannot be shown. |

The open questions this review raised were decided with the maintainer on the same day; see Decisions of 2026-11-11 at the end.

## Engine and plan review of 2026-11-11

The first review changed the contract. This one changed how the work is cut, after reading the engine itself. ENGINE.md has the evidence.

| Decision | Reason |
| --- | --- |
| Clean the engine before building on it, as its own track | Multipass and per-input sampling would otherwise be added to code where one uniform is declared in seven places and one fact is tracked in five layers. Each engine PR also pays off for existing DB shaders, so it can be proposed upstream on its own merits. |
| One uniform table; programs reflect their uniforms at link | These two facts are the source of every duplicate today. With them, the `has*` flags, location caches, `ProgramBindings`, the sampler regex, and the Add Uniform list's own copy of names and types all become derived. |
| Remove the GL state cache | It saves nothing measurable at a few draws per frame, and it is the reason the camera texture needs a dirty flag. |
| `EngineHost` instead of casting `Context` | Rendering code requests permissions from the GL thread today, and the camera silently does nothing in the wallpaper service because the service is not a `LifecycleOwner`. |
| Swap plans on the GL thread | Reload currently destroys the EGL context. Keeping the context is what makes "the last working shader keeps running" possible at all, and it is required by SPEC 9. |
| Pull requests instead of nine sessions | A session such as the old S3 would have been a several-thousand-line change. The March renderer refactor (31 files, about 3,400 added lines) shows the size that results. The rules in IMPLEMENTATION.md cap each PR and separate refactoring from behavior. |
| The old S3 is split into E15, E16, and E17 | The runtime, its remaining input kinds, and the switch of existing shaders are three reviewable steps. |

Not done on purpose: no package renames, no Kotlin or Compose migration, no dependency injection, no generic render-graph library. Each would inflate diffs without removing a problem listed in ENGINE.md.

## Libraries and textures

Host directives are forbidden in include files, permanently. A library that needs a texture should not choose the image; it takes the sampler as a parameter, which GLSL ES allows:

```glsl
// lib/noise.glsl
float blueNoise(sampler2D tex, vec2 uv) { return texture2D(tex, uv).r; }

// main.glsl
#pragma se input "lib/blue-noise.png" filter=nearest wrap=repeat
uniform sampler2D blue;
#include "lib/noise.glsl"
... blueNoise(blue, uv) ...
```

The library stays pure code, each shader picks its image and sampling, and the library can still ship a suggested image next to itself. This also keeps the rule that every input of a pass is visible in the pass file.

## Decisions of 2026-11-11

Made with the maintainer, one by one, after both reviews. The guiding principle he set: no regression that can be cleanly avoided, and no long-term cost from old decisions.

| Question | Decision | Why, and what was rejected |
| --- | --- | --- |
| How the running shader is scaled to the screen | `present=nearest|linear` on `se target`, default nearest | Nearest is what every existing shader shows, and a default can never change later. The option ships now so smooth upscaling and exact conversion are both possible. Rejected: linear as the fixed rule. |
| Mipmaps | Supported for image inputs | The existing app has them. Rejected: deferring, because it would regress shaders that use them. |
| Spelling of sampling options | Orthogonal keys: `filter` or `min`/`mag`, `mipmap`, `wrap` with one or two values | Three independent GL choices get three keys; the common case stays one short key; all twelve old filter combinations and every wrap pair convert exactly. Rejected: `filter` plus `mipmap` only (the old default would still be inexact) and GL-style combined values (long and easy to get backwards). |
| Size of an input in GLSL ES 1.00 | Not provided | `textureSize` exists with `#version 300 es`. Rejected: a companion uniform found by name, which is the inference the format removed everywhere else. |
| Cubemaps | Supported: an image declared `samplerCube` is a six-face horizontal strip in GL order | The declared type decides, so there is no new syntax. One layout with a one-sentence rule; conversion repacks stored atlases. Rejected: keeping the old 2-by-3 atlas (odd face order forever), the cross layout (ambiguous orientation), and accepting several layouts. |
| Initial backbuffer content | `se target initial="..."` takes any source: an image, or an ordinary shader drawn once per reset | Exact conversion of presets without the old fit rule entering the format, and procedural initial state for new projects. The shader is unrestricted so there is no special kind of shader to learn. Rejected: a restricted seed shader, a `fit` key, and stretched images only. |
| Host directives in include files | Forbidden by design | See Libraries and textures. Rejected: allowing them, which needs a merge rule for repeated includes and hides inputs from the pass file. |
| Enabling project creation | At Gate A, before multipass | Includes and local textures reach users sooner and feedback arrives before the multipass work. |
| Existing DB shaders | Convert in memory, migrate to files, remove the database code | No second path in the engine and no legacy flag. Possible because every setting now converts exactly. Rejected: a runtime adapter, permanent or temporary, because the old conventions would then live in two places. Migration and removal need the upstream maintainer's agreement; without it the database view stays and nothing else changes. |
| Shape of migrated shaders | One library folder with a shared `textures/` folder; a new shader is a file | Each texture is stored once, the shader list looks as before, and shaders can share code at once. Rejected: one folder per shader, which duplicates textures and isolates code. |
| A uniform without a provider, or a builtin with the wrong type | Warning; the shader runs; not configurable | It reads zero by GL's definition. Ported, unfinished, and migrated shaders keep running, and the rule is unchanged when scripts become providers. A setting would make one shared shader behave differently on two devices. A per-project strictness key can be added later if asked for. |
| Proof that rendering is unchanged | CI for build, lint, and JVM tests; a named by-eye checklist for rendering PRs | Simplicity first. Rejected: a golden-image harness, because pixels differ between GPUs and nothing would run it. |
| `GpuBitmapTransformer` | Rebased on the engine classes after the engine lands (E18) | Removes a second copy of compile and draw code once the shared pieces exist. |
| Agent files | `IMPLEMENTATION.md`, `HANDOFF.md`, `PROMPTS.md` moved to `docs/agents/project-mode/` | AGENTS.md keeps agent configuration out of upstream PRs; the design folder can now be offered upstream. |
