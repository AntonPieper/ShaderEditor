# Project format v1

Status: proposed implementation contract. MUST, MUST NOT, and SHOULD describe requirements. No claim of current app support is implied.

## Terms

| Term | Meaning |
| --- | --- |
| Project root | The folder the user opened. It bounds every path reference. |
| Running shader | The one text file whose output is shown. Also called the entry. |
| Pass file | A text file compiled as its own program: the running shader, or any text file named by an input. |
| Include file | A file pulled into a pass file by `#include`. |
| Kind | What a file is, read from its bytes: a picture, text, or other (section 2.1). |
| Role | What a file does in a build: running shader, pass, texture, include. It comes from how the file is referenced, never from its name, its folder or a guess at its content (section 2.1). |
| Language | What a text file is written in. It comes from the file's name through one table; GLSL where the table says nothing (section 2.1). |
| Input | A texture a shader reads: an image, a pass output, or an engine sampler. In GLSL it is a sampler uniform. |
| Host directive | A line that carries `se ...` for ShaderEditor, in GLSL a `#pragma se ...` line. ShaderEditor consumes it; the compiler never sees it. |

## 1. Project and entry

A native project is an ordinary folder used as a bounded logical file tree. Any folder granted by Android can be opened, including an empty, mixed-content, or read-only folder. Opening/browsing does not require a manifest or a runnable shader. The portable default entry is `main.frag`. Only the selected entry and its dependencies participate in execution. Directory names such as `passes`, `include`, and `assets` are conveniences, not semantic namespaces.

An optional UTF-8 `shader-project.json` at the root supports exactly:

```json
{
  "projectVersion": 1,
  "entry": "main.frag"
}
```

Both fields are optional and default to the values shown. A missing manifest permanently means v1 with portable default `main.frag`; it never means the latest format. This default does not prevent explicitly running another file. The manifest must be a JSON object. Duplicate keys, unknown keys, a version other than integer 1, or a non-string entry are errors. Entry resolves relative to the project root and may name any file, whatever it is called; what the file must be is decided when it runs (section 2.1).

A folder without a manifest is a complete project: nothing in this format needs one. The manifest exists only to make another file than `main.frag` the portable default.

The project root is the folder the user opened. Only a manifest directly in that root is read; a `shader-project.json` deeper in the tree is an ordinary file. A file can therefore sit under different roots at different times, and a reference that leaves the current root is an error. Code shared by several shaders belongs in a folder that contains all of them (DECISIONS, Reusing code across shaders).

One rule selects the running shader, and it applies identically on open, reopen, and refresh:

1. the file the user last ran in this folder (by tapping it or with Run this file), if it still exists;
2. otherwise the `entry` of a valid manifest;
3. otherwise `main.frag`, if present;
4. otherwise nothing runs.

The three sources are tried in this order and the first that names a file wins; there is exactly one default at any time. A folder that has both a manifest and a `main.frag` runs the manifest's entry, because the manifest is the explicit statement and `main.frag` only the convention. The only question the rule asks of a file is whether it exists. It never looks into a file, so the choice cannot change while a file is being edited: a remembered file that is no longer text, or has lost its `main`, is still the running shader, and that is a render diagnostic on it (section 2.1), not a reason to run something else. A remembered file that is gone falls through to the next line.

The Run this file choice is app metadata remembered per folder. It never changes portable files. Set default shader writes the manifest entry and clears the remembered choice, so the two agree afterwards. An invalid manifest is a render diagnostic for every running shader in that folder, because its version may be unsupported; a configured entry that does not exist is a render diagnostic too. Neither blocks browsing or editing.

An opened loose document is a one-file project: it is its own running shader regardless of filename, and it has no siblings. An `#include` or a file input in it is a diagnostic that offers Open folder, and the chosen folder then opens as an ordinary project. Select the same document inside that folder when the provider can identify it; otherwise leave the selection to the user. Do not infer a parent from URI text, and do not merge a document grant with a tree grant. Database shaders are covered in section 12.

Format evolution: `projectVersion` changes only when a project that is valid today would change meaning. An addition that every earlier reader rejects (a new `se` command, option, source type, or manifest key) stays within version 1; an older app reports it as unsupported and never misrenders it. This is what keeps "no manifest means v1" true permanently.

Project names, thumbnail caches, editor state, storage locators, quality preferences, permissions, and installed wallpaper state are app metadata outside the portable tree.

## 2. Paths and file contents

A path reference is the text between two double quotes, taken literally. There are no escape sequences, in `#include` and in `se input` alike, so `"` and `\` cannot appear in a path. The manifest is JSON, so its `entry` string is decoded by JSON's own rules first; the decoded text must then be a valid path. Spaces and Unicode are allowed. `/` separates segments. Reject empty paths, NUL/control characters, absolute paths, drive-qualified paths, and URI schemes. Normalize `.` and `..`, rejecting any escape above the project root. Resolve an input or include relative to the file containing that directive. Path identity is case-sensitive; require exact spelling. Compare segments in Unicode NFC, so a name stored decomposed (common in archives made on macOS) matches the same name typed composed. Do not derive paths from `content://` URI text: map logical directory entries to opaque provider locators.

Different spellings that normalize to the same path identify the same resource. A project snapshot must reject duplicate normalized paths. If a provider cannot represent a project's distinct paths, report the conflict rather than renaming silently. Filesystem links and archive links must not escape the root; initially reject links rather than inventing link semantics. Version-control administration directories are not valid dependency targets and are omitted from project export: a path with a segment `.git`, `.hg` or `.svn` is an error.

Shader/manifest text uses UTF-8. Accept an initial UTF-8 BOM and normalize CRLF/CR to LF while retaining original source locations. Parse host paths before any driver-facing ASCII sanitization. Do not strip Unicode characters out of filenames or mutate tokens to make invalid shader text appear valid. Preserve raw driver logs for diagnostic fallback.

An unreachable source file is not compiled; a broken experiment does not prevent the selected entry from running.

### 2.1 What a file is: kind, role and language

Three questions are asked of a file, and each has one answer from one place. A file's name answers only the third.

**Kind comes from the bytes.** A file is a *picture* when it starts with the signature of a supported image format, *text* when its bytes are valid UTF-8 within the size limit of a text file, and *other* in every remaining case. The signatures are one small table; adding a format is adding a row:

| Format | The file starts with |
| --- | --- |
| PNG | `89 50 4E 47 0D 0A 1A 0A` |
| JPEG | `FF D8 FF` |

The signature is looked at first, so a picture is never text. The name plays no part: `noise.png` holding GLSL is text, and `photo.txt` holding a JPEG is a picture. A build learns the kind of each file from the bytes it reads for that file anyway, the one read of section 10; telling a picture needs only its first bytes, and nothing is decoded to plan a build. Every file whose kind was looked at is one of the build's dependencies. Where a file is not read whole, a store reads its first bytes alone.

**Role comes from references.** Nothing is a shader, a texture or a library because of what it is called or where it lies:

- The running shader is the file the rule of section 1 selects.
- A file named by an input (`#pragma se input "..."`, or `initial="..."` of a target) is a texture when it is a picture and a pass when it is text. Anything else is an error on that line: `data.bin is neither a picture (PNG, JPEG) nor text`.
- An include is whatever `#include` names; it must be text.

A pass must have an entry point, which in GLSL is a function `main`: an input that names a text file whose source, with its includes, never mentions `main` is an error on the input line that names the file, `lib/noise.glsl defines no main, so it cannot be an input; include it instead`, because nothing could be drawn and the driver's message would point at no line of the user's. The same holds for the running shader, reported on the file. Text that mentions `main` is left to the compiler, which is the only judge of whether it defines one.

Options are checked against the kind once it is known: `frame=` on a picture, `mipmap=` on a pass, and a sampler type the source cannot be read as (section 3.1) are errors on the input line.

**Language comes from the name, through one table.** A text file's language is looked up by its name; GLSL is the language of every text file the table does not list. The table has one language that runs today:

| The name ends in | Language |
| --- | --- |
| `.frag`, `.glsl`, `.fs`, `.fsh` | GLSL |
| `.wgsl`, `.agsl`, `.sksl`, `.hlsl`, `.slang` | another shading language, which v1 does not run |
| anything else | GLSL |

The names other shading languages are known by are reserved, so that a file so named never means GLSL. As the running shader, as a pass or as an include, such a text file is an error that says so and how to fix it: `fx/blur.wgsl is named as a shader in another language than GLSL, which ShaderEditor does not run; rename it if it is GLSL`. Run this file is not offered on it. It is still text: it can be browsed, edited and shared, and a picture with such a name is a picture, because kind comes first. This is what lets a later version add a row for such a language without a new `projectVersion` (section 1): no project that is valid today changes its meaning.

This table is the only place where the ending of a file name has meaning, and it is consulted only for text. Endings are compared without regard to case; path lookup stays case-sensitive (section 2). The listed endings are also what the app offers itself for when another app opens a file, and what the folder list may use as a hint.

**Names by convention.** None of this is enforced: a fragment shader that runs or is a pass is named `*.frag`, and code meant to be included is named `*.glsl`. The default name, the examples, the converter and the New sheet follow it. It matches what the common tools do: `.frag` is the stage extension of the Khronos tools and of most editors, and `.glsl` is what shader libraries use for files without a stage.

**A hint is not the truth.** A list of files may show an icon from the type a provider reports or from the name, because reading the first bytes of every listed file is too slow on document providers. That hint decides nothing: when a file is opened or used, its kind is read from its bytes. A picture named like a shader shows a text icon in the list and opens as a picture.

**Run this file** is offered on every text file whose own text defines an entry point, `main` in GLSL, whatever its name.

## 3. GLSL and host directives

This section and section 8 describe GLSL, the one language of v1 (section 2.1). They keep two things apart. **The format** is what a host directive says: the command, the quoted path and the `key=value` options that follow `se`, what they mean (sections 3.2 to 7), and the rules of including (section 8). It does not depend on the language of the file. **GLSL's way of writing it** is the rest: that a directive is carried on a `#pragma` line, that an input attaches to a `uniform` sampler declaration, that an include is written `#include`, that a shader declares the engine's uniforms itself, and the rules about `#version`, conditionals, comments, line continuation and ASCII. A text file in another language would say the same things in that language's way (section 13).

Keep ordinary GLSL declarations, precision statements, functions, and version directives. Do not inject declarations for `time`, `resolution`, or other engine uniforms. Preserve the existing engine's supported language versions and builtin uniform API; never silently translate GLSL ES 1.00 to 3.00. A shader requiring an unavailable GLSL version fails with a capability diagnostic.

The engine generates only the source lines it generates today: `#define SHADER_EDITOR 1`, and the external-image extension that a camera sampler needs. Both follow `#version` and map to the generated-source origin of section 8. `#define FTIME_PERIOD` keeps its existing meaning for `ftime` and is read from each pass's assembled source.

Only two host directives exist in v1:

```glsl
#pragma se input "assets/noise.png" filter=nearest wrap=repeat
uniform sampler2D noise;

#pragma se target scale=0.5 format=rgba8
```

Every option has a default, so the short forms above are the common case. Sections 3.1 and 5 list all options.

The canonical spelling is `#pragma se input` or `#pragma se target`: `se` is a complete namespace token, followed by a whitespace-separated command. `se.input`, `se::input`, and `se_input` are not aliases. Diagnose these recognizable unsupported ShaderEditor spellings with the canonical replacement. Match namespace boundaries: unrelated pragmas such as `sequence` must not be captured.

Whitespace may precede `#`, appear between `#` and `pragma`, and separate tokens as in preprocessing directives. Each host directive occupies one physical line. The quoted path follows the literal rule of section 2. Spaces/tabs separate arguments. Permit trailing `//` comments after the final argument. A line that holds a host directive or an `#include` is consumed whole, so it may carry one trailing `//` comment and no other comment: a block comment that starts, ends or lies anywhere on such a line is an error that says so. Do not support macro expansion or line continuation in host directives: a `#pragma` line that ends in a backslash and would continue into a `se` directive is an error, never silently ordinary GLSL. Whitespace/comments between a completed annotation and its declaration are allowed.

Line continuation follows GLSL ES 3.00 in every file, whatever its `#version`: a backslash directly before a line end joins the next line to the current one before comments and directives are recognised. So a `//` comment or a `#define` that ends in a backslash continues on the next line, and a `#` on that next line starts no directive, no `#include` and no host directive. The editor colours text by the same rule, because both read the same tokenizer. A GLSL ES 1.00 compiler without continuation may read such a line as code; the app does not try to guess that.

Keys are case-sensitive. Reject unknown commands in the `se` namespace, unknown/repeated keys, malformed values, and trailing tokens. Preserve unrelated vendor/compiler pragmas for the GLSL compiler. Consume recognized host directives and replace them with mapped blank lines before compilation.

Use a comment-aware lexer. A pragma-looking string inside a comment is not a directive. The minimal declaration parser recognizes the grammar below; it is not a replacement GLSL compiler.

### 3.1 Input attachment

```text
input-directive := #pragma se input [quoted-path] [input-option ...]
input-option    := frame=current|previous
                 | filter=nearest|linear
                 | min=nearest|linear
                 | mag=nearest|linear
                 | mipmap=none|nearest|linear
                 | wrap=MODE | wrap=MODE,MODE
MODE            := clamp|repeat|mirror
declaration     := uniform [lowp|mediump|highp] sampler-type identifier ;
```

The sampling options are three independent choices, as in GL: the base filter, the mipmap mode, and the wrap mode. `filter` sets minification and magnification together; `min` and `mag` set them separately and cannot be combined with `filter`. `wrap` with one value sets both axes; with two values it sets the horizontal axis, then the vertical one.

For a pass, sampler-type is `sampler2D`. For a picture it is `sampler2D`, or `samplerCube` to read the image as a cubemap (section 4). For source-less engine samplers, use their existing supported GLSL type (`sampler2D` for backbuffer, the supported camera sampler type for camera inputs). Validate capabilities through existing engine providers.

An input annotation applies to exactly one immediately following direct uniform declaration in the same file. Allow comments, whitespace, and a multiline declaration, but not another declaration, another directive, or a file boundary in between. Do not support annotated comma-separated declarations, arrays, structs, layout qualifiers, or macro-generated declarations in v1. Give a targeted error explaining the supported shape. Unannotated GLSL may use ordinary legal constructs independently of this attachment grammar.

There may be only one input annotation per declaration. Host directives are allowed only in pass files and at conditional-preprocessor nesting depth zero. They are forbidden in include files, by design and not as a temporary limit: a library function that needs a texture takes the sampler as a parameter, and the shader that includes it declares the input (DECISIONS, Libraries and textures). They are also forbidden inside `#if`/`#ifdef`/`#ifndef` blocks. They cannot attach across such a boundary. The lexer tracks conditional structure without evaluating GLSL expressions.

The variable name appears only in the GLSL declaration. Named-target syntax, `se bind`, `se sampler`, placeholder targets, and generated uniform declarations are invalid native v1 syntax.

### 3.2 Sources

A quoted path that names a picture supplies an image texture. A quoted path that names text supplies that file's default pass output (section 2.1). A shader output defaults to the current frame. `frame=previous` selects its previous completed frame. The `frame` key is invalid for images and for source-less annotations.

With no quoted path, the attached uniform must be a recognized engine-provided sampler. Its source remains that engine provider; the annotation only customizes legal sampling settings. A bare input directive with neither path nor options is an error. Do not allow explicit file bindings to replace reserved builtin names.

Examples:

```glsl
#pragma se input "passes/state.frag" frame=previous
uniform sampler2D previousState;

#pragma se input filter=linear
uniform sampler2D backbuffer;
```

An unannotated active `backbuffer` retains the implicit previous output of its own pass. `backbuffer` and an input that names the pass's own file with `frame=previous` read the same history texture. Other engine uniforms continue to work under their existing names and types. An unannotated arbitrary sampler does not trigger file/library lookup.

Each declared input path is a required static dependency, even if the GLSL compiler optimizes the uniform away. Missing files and graph cycles remain errors. After linking, warn for an annotated input absent from active-uniform reflection; do not prune its declared dependency. Every active uniform should have a provider: the builtin table, or an input. A uniform without a provider reads zero (GL defines that; an engine on another API binds zeros) and is reported as a warning with its fix (Add texture for a sampler, the nearest builtin name for a likely typo). A builtin declared with the wrong type is a warning as well. The shader runs in both cases. The rule is stated in terms of providers so that it does not change when a later version lets a script provide values. Missing files, cycles, and malformed directives are errors, because then nothing can be built. V1 has no scalar-value directives; use GLSL constants or builtins.

## 4. Image and sampler semantics

PNG and JPEG are the v1 image formats, told by their signatures (section 2.1). The import UI may decode other formats and copy a PNG into the project. HDR images, video files, and asset sidecars are out of scope. Camera inputs keep their existing engine contract.

Decode to RGBA8 numeric channel values with straight alpha; do not implicitly linearize sRGB. JPEG alpha is 1. Honor supported image orientation metadata. Normalize upload orientation so `(0,0)` denotes the image's lower-left corner consistently with pass textures. Do not silently resize or reduce precision to fit GPU limits; report the limit and offer explicit user action. Imports may offer a deliberate resize before copying.

| Source | filter | mipmap | wrap |
| --- | --- | --- | --- |
| Image | linear | none | clamp |
| Pass output, backbuffer | nearest | not available | clamp |
| Other engine sampler | existing provider default | not available | existing provider default |

`clamp` means clamp-to-edge. Unsupported combinations (including GLES2 non-power-of-two images with `repeat`, `mirror`, or mipmaps, and external-texture constraints) fail clearly rather than changing sampling silently.

**Mipmaps.** `mipmap=nearest` and `mipmap=linear` select the nearest level or blend between two levels; together with the minification filter they cover the four GL mipmap modes. Mipmaps exist for image inputs only and are generated once at upload, when at least one input of the image asks for them. `mipmap` on a pass output, the backbuffer, or an engine sampler is an error: those change every frame, and regenerating levels per frame is not part of v1.

**Cubemaps.** An image input declared as `samplerCube` is a horizontal strip of six square faces, in the order +X, −X, +Y, −Y, +Z, −Z. The image width must be exactly six times its height. Square *i* from the left is face *i*; rows are stored top to bottom, which is the orientation GL expects for cube faces. `wrap` does not apply to cubemaps and is an error there. The import UI assembles the strip from six picked faces or repacks other layouts; the format has this one layout.

Sampling belongs to the input, not to the image or pass it reads. One source may have several consumers with different settings. On a GLES3 context use sampler objects; they are a context feature and work for every shader language version. On a GLES2 context apply each input's settings to the texture before the draw that uses it. Two inputs of one program that name the same source with different settings cannot be honored on GLES2: report a capability error that names both inputs. Do not copy textures to emulate sampler objects.

Library resources, including bundled noise, are inserted as project-local copies. There are no global references, implicit aliases, or background library updates. Export contains independent logical files. Bundled include files and bundled examples follow the same rule: what a user keeps is a copy, and an app update never changes a copy (EXAMPLES.md).

## 5. Targets

Each independently compiled shader file may contain at most one `se target` directive, at conditional depth zero. Included files cannot configure targets.

```text
#pragma se target [scale=number | size=WIDTHxHEIGHT]
                  [format=rgba8|rgba16f]
                  [initial=quoted-path]
                  [present=nearest|linear]
```

At least one property is required when the directive is present. `scale` and `size` are mutually exclusive. Defaults are `scale=1`, `format=rgba8`, no `initial`, `present=nearest`. Scale is a decimal greater than zero and no greater than one, written with at most six digits after the decimal point; no exponent, expressions or macros. It is compared and applied as that exact decimal. Size dimensions are positive decimal integers. Reject overflow and resource-limit violations.

Project render size is the engine's existing quality-scaled surface size (`round(surfaceDimension * quality)` at the inspected base), at least 1 per axis, so converted shaders render at the size they always had. Scaled pass size is `max(1, floor(projectRenderDimension * scale))`. Fixed sizes are independent of quality. The running shader's output is presented across the output surface. `present` on the running shader chooses how it is scaled when its size differs from the surface: `nearest` keeps pixels crisp and is the default, `linear` smooths. On any other pass file `present` has no effect. Texture orientation is consistent with the input rules. Existing wallpaper crop/offset behavior must be accounted for in the input-coordinate transform rather than independently guessed by every pass.

Every pass has one color output, no depth attachment, and no blending. Clear every draw destination to `(0,0,0,1)` before drawing, so discarded or unwritten pixels have defined contents.

**Initial content.** A pass's history starts as `(0,0,0,1)` unless `initial` names a source. `initial` takes a path exactly as an input does:

- An image is stretched over the target, sampled linearly.
- A text file is an ordinary pass file, with no restrictions: it may use inputs, includes, and its own `se target`. It is drawn once at start and after every reset (section 7), with `time` and `frame` at zero, and its output is stretched over the target. Whatever it reads from other passes shows their initial content, which is what "before the first frame" means.

Initial sources that are shaders form a graph of their own: a shader that is an initial source may read passes that have initial sources themselves. That graph must be acyclic, by the same rule as current-frame inputs (section 6). An initial source is a static dependency like an input. A shader reached only as an initial source is drawn at reset and not once per frame; if it is also a shader input, it is an ordinary per-frame pass as well. `initial` on a pass whose history nobody reads has no effect and is a warning; a reset-time read of the pass by another initial shader counts as a read. Whether a pass reads its own history through an unannotated `backbuffer` is only known after linking, so this warning is given then, not from source text.

RGBA8 is baseline. RGBA16F requires actual renderability and the requested sampling capability; support must not be inferred from GLES version alone. Check framebuffer completeness. Never silently fall back to RGBA8 or another size. Query device texture-size and texture-unit limits; do not rely on the current hardcoded texture count. Include internal draw needs in allocation planning.

## 6. Default pass identity and graph

File authoring creates one default pass per normalized shader path. Multiple references to that path share its execution and output. The program/source and pass instance remain separate internal records; a future graph frontend can create additional instances without a new renderer. Native v1 exposes no instance IDs or override syntax. Reuse algorithms with includes and small wrapper shader files when different configured instances are needed.

Reachability starts at the running shader and follows ALL explicit shader-input edges, both current and previous, and every `initial` source. Do not discover/execute unrelated files by folder scanning. Create graph nodes before recursively traversing dependencies so previous-frame cycles terminate during discovery.

Current-frame edges must form a DAG. Use only those edges for scheduling; use normalized path ordering to break ties deterministically. Previous edges do not impose same-frame order, but their producers still execute once per project frame. Self-current reads are errors. Self-previous and cross-pass previous cycles are valid. Report cycle paths with the input source locations that caused them.

An active implicit backbuffer requires own-pass history. An explicit previous edge requires history even if the producer has no backbuffer uniform. Do not wait for producer reflection to notice another pass's historical need.

## 7. Frame, history, and uniforms

One project frame has one input/time snapshot and one frame number. Sensor and camera sampling must not advance independently per pass. Reuse existing builtin meanings; do not replace the time API with a new clock design. Existing wall-clock versus persistent-runtime behavior, when present in the implementation base, remains distinguishable.

`resolution` is the current pass's target dimensions. Transform pixel-based touch/pointer positions from the displayed project area to that target; preserve the existing origin convention. Normalized coordinates remain consistent across passes. Builtin types and feature permissions remain the existing documented API. Collect builtin demand across the whole graph and own shared listeners/providers at graph-runtime scope rather than registering one listener set per pass.

Frame procedure:

1. Freeze references to all previous-frame outputs and capture shared inputs/time.
2. Draw reachable passes once in current-edge order, using fixed previous references and freshly produced current references.
3. Complete the entry output and present it.
4. Publish the frame and advance histories/frame number together.

Do not swap individual pass histories immediately after their draws. Never sample the texture attached as the current draw destination. A pass needs two outputs if any consumer needs its history; otherwise one is sufficient. There is no partial-frame recovery. Programs are linked and targets are complete before a plan becomes active, so a frame of an active plan either runs to the end or the context is lost, and context loss triggers the normal rebuild and reset. Do not poll GL errors per draw or keep a separate retained presentation copy.

Restart, successful activation of a new project revision, changed render-target dimensions, and GL context recreation reset all project histories and frame number. Restart also restarts ordinary animation time as the existing restart/reload contract requires; do not accidentally reset separately persisted wallpaper runtime contrary to its existing contract. No selective history preservation in v1. Pausing stops frame advancement. If the context survives, preserve history; existing time providers retain their documented pause behavior. Do not implement catch-up simulation steps on resume.

## 8. Includes and source maps

Support only literal `#include "relative/path.glsl"`. Angle-bracket lookup and macro-generated include operands are errors. Resolve relative to the including file, with no fallback search directories or global library. An included file must be text in the language of the file that includes it. Includes may be nested.

Include expansion is static source assembly before driver preprocessing: every literal include must resolve even inside `#if 0`. Ordinary include guards control the resulting GLSL, but do not make files optional. Reject include-stack cycles even if guards would terminate them. Repeated acyclic includes are expanded repeatedly; no implicit include-once behavior. `#pragma once` is not supported: warn where it appears and point to include guards, because the driver would ignore it and report confusing redefinitions. Document this intentionally smaller contract in editor help.

Track conditional nesting in each physical file and reject unbalanced conditional boundaries across include files. Includes do not supply or replace `#version`: reject version directives in included files. The entry retains ownership of version and extension requirements; do not hoist extensions silently. Existing engine-injected extension directives must retain valid GLSL ordering.

Map every prepared line back to original file and line, preserving an include stack where useful. Map engine-generated lines to an explicit generated-source origin, not an arbitrary user line. Retain raw compiler logs and show them when a driver log cannot be mapped reliably. Native host metadata in an included file is an error with that file's location.

## 9. Diagnostics and revision activation

Diagnostics carry project revision, source path/line when available, severity, message, and pass identity when relevant. Report structural errors before GPU compilation where possible; report linked-uniform type/capability errors afterward. Native unknown metadata is an error, never silently ignored.

Prepare a candidate revision without destroying the last valid executable plan. Compilation, resource upload, and validation must succeed before activation at a frame boundary. A candidate consists of linked programs, decoded and uploaded images, and target requirements checked against the context's capabilities. Render targets belong to the candidate: they are allocated and checked before activation, while the previous plan still draws, so a candidate whose targets cannot be made is rejected like any other failed candidate and the previous plan keeps running. Old and new targets therefore coexist until the swap. If the allocation fails while a previous plan holds targets, release the previous plan's targets and try once more before giving up; only then report it and show the error state, since the previous plan can no longer draw. Stale results cannot replace a newer requested revision. On failure retain the prior preview and label it as showing the last successful build. If no valid plan exists, show an explicit error/empty state. Persistence success and render success are independent.

## 10. Persistence and sharing

Follow UX.md for the native picker, saving in place, external edits, drafts, and recovery. All project file access goes through one small store interface keyed by logical path (ARCHITECTURE 2). A read has three outcomes that callers can tell apart: the content with its revision, absent, or failed with a reason (permission lost, I/O error, a link, two entries with one normalized name). A revision changes whenever the bytes do. It has two storage implementations: a directory on the app's own storage, and a SAF tree. ShaderEditor's DocumentsProvider exports the own-storage directory to other apps; ShaderEditor itself reads that directory directly and not through its own provider. Installed wallpaper snapshots and archive staging are directory stores as well. A fourth, temporary implementation is a view over the database (section 12). The store kind is not a language or user-visible project mode. Never expose app-private databases, drafts, caches, or installed wallpaper state through the user-files root.

Render immutable snapshots of the selected dependency closure, overlaid with current drafts. Do not clone or recursively read an entire folder on open. Capture each required file once for a build; all consumers of it use those bytes. Check available revisions/content on acquisition and before publishing when practical. Retry/report observed concurrent changes. Providers need not offer a transaction across files, so do not claim a globally atomic external snapshot. Once acquired, candidate bytes remain immutable: no lazy read of newer provider content midway through preparation/drawing.

Share and Save a copy gather the selected entry plus the transitive closure of shader inputs, literal includes, and local images, following both current and previous edges. Never implicitly copy unrelated files from the opened folder. Preserve root-relative paths so relative references remain valid. A dependency-free shader may be shared as a single shader document only when no manifest metadata is required to preserve its meaning. Otherwise use a ZIP with no enclosing synthetic directory; include a minimal v1 manifest when needed to make the selected entry portable. Adjust the manifest only in the copy, never as a side effect in the source folder. Allow explicit extra files; omit app state and VCS administration directories. Save a copy uses the same collection rules and switches to the copy only on successful completion.

Sharing does not require successful compilation. Dependency collection is a tolerant use of the same lexer/path rules, not a second guessed language. If malformed directives, missing files, or unreadable resources prevent complete collection, identify them and allow explicit extras or an acknowledged incomplete source package. Never describe that package as self-contained. Invalid/unsupported existing manifests are not silently downgraded; an incomplete source export retains that file as-is, with the limitation shown.

ZIP import rejects unsafe paths, duplicate normalized entries, links, and bounded-size/count violations before publishing. If every entry lies under one top-level directory, as in archives produced by code-hosting sites, that directory is the project root. Extract into a new unused folder at the chosen destination, using app-private staging for validation where necessary. A structurally safe archive with invalid/unsupported shader configuration may still be extracted and browsed; preserve its manifest and report the render error. Never overwrite unrelated destination files. Local publication can be atomic; arbitrary document providers cannot promise atomic multi-file writes. On failure retain drafts/staging as needed for retry and report partial destination writes without selecting them as a successful copy/import.

Wallpaper application captures the selected dependency closure from the current valid requested revision and its selected entry. Publish its installed app-private snapshot reference atomically. Never apply an older successful preview by accident. The installed snapshot must survive source permission loss and process recreation without depending on the provider.

## 11. Limits and feature scope

Enforce explicit bounds on expanded source bytes, include depth, include expansions, files per build, passes, and decoded pixels per image. A limit is an admission bound: it is checked before the read, expansion or allocation it guards, in one place per resource, and a limit of zero is a usable policy that yields the same diagnostic, not a crash. GPU limits come from the context's reported texture size and unit counts; do not estimate GPU memory. The defaults are include depth 16, 1 MiB of assembled source per pass, 20,000 include expansions per pass, 64 files per build, 16 passes, and 16,777,216 decoded pixels per image (4096 by 4096). A file counts toward the files of a build once, whether it turns out to be a picture, a pass or an include. They are sized for phones: a build stays within a few megabytes of text and one full-size texture per image, and a small project cannot make preparation run for more than a moment. Keep limits in one documented policy location. Report the exceeded limit and avoid silently truncating input. Limits may be implementation/device-dependent; they are resource admission policy, not alternate format semantics. Choose conservative values from Android memory constraints and record them in HANDOFF plus user-facing diagnostics.

V1 includes file projects, includes, local 2D and cubemap images, mipmaps, engine uniforms, basic multipass, history with initial content, targets, diagnostics, native editing/persistence, sharing, and wallpaper snapshots. It excludes generated declarations, source-name inference, asset sidecars, implicit global resources, arbitrary graph overrides, a graph editor, a script runtime, compute, SSBOs, multiple outputs, mipmaps for pass outputs, input-size uniforms (use `textureSize` with `#version 300 es`), and live cross-project package updates. Unsupported features fail explicitly.

## 12. Existing DB shaders

The engine has no second dialect. Everything it renders is a project as defined above. Existing database shaders reach it through a converter, and in the end they become files.

### 12.1 The converter

The converter is a pure function with no Android or GL dependency: given a DB shader's text and a way to look up library textures by name, it returns project files and a source map back to the original lines. The shader is written as `main.frag`, and a shader it generates for a backbuffer preset as `<preset>.frag`, following the names of section 2.1. It is the only code that knows the old conventions.

| Existing convention | Project form |
| --- | --- |
| `uniform sampler2D name;` matching a library texture | `#pragma se input "textures/name.png"` before the declaration; the image is the library texture |
| Trailing `///min:..;mag:..;s:..;t:..;` | `min`, `mag`, `mipmap`, and `wrap` options; `filter` when min and mag agree. An untouched texture has the old default and becomes `min=nearest mag=linear wrap=repeat`. |
| `uniform samplerCube name;` | The same directive; the stored atlas is repacked into the strip of section 4, pixel for pixel |
| Settings on `backbuffer` | A source-less `se input` with the same options. Its magnification filter also becomes `present` on `se target`, because the old renderer scaled the output with it. |
| Backbuffer preset `p:name` | `se target initial="..."` naming a generated shader that reproduces the old fit (scale to width, mirror-repeat vertically) with the image's dimensions written in |
| Camera samplers, builtin uniforms, `FTIME_PERIOD` (`0` still makes `ftime` NaN) | Unchanged. When a shader declares both camera samplers, the one declared first in the text gets the camera, as before. |
| `uniform sampler2D name;` matching a library texture whose name is a builtin uniform's (`time`, `mouse`, …) | The uniform is renamed `name_texture`, or `name_texture2`, … when that name is taken, in every use outside comments, and gets the directive of the first row. Compiler messages use the new name. |
| Characters other than printable ASCII, tab and line feed | Removed before conversion, as the old renderer removed them before it read the text |

Every row is exact. A sampler whose name matches no library texture stays without a provider and gets the ordinary warning of section 3.2.

"Exact" includes the effects of how the old renderer found declarations, wherever they change what a shader shows. It matched a pattern against the raw text, comments included, took the first match on each line, considered at most 32 samplers besides `backbuffer` (and read no `backbuffer` settings after the 32nd), and when a name matched more than once applied each match's settings in order, so the last one won. A commented-out declaration therefore still chose the settings, and bound the texture, of a real declaration of the same name that the pattern itself did not match (for example one with a precision qualifier). The converter reproduces these outcomes on the real declaration. The library texture names it writes become file names; a name that cannot be a file name gets a safe one, with a warning.

A few old behaviors have no exact form. Each was an accident of the old renderer, not a feature; the converter still produces a shader that compiles, and reports a warning that names what changed:

| Old behavior | After conversion |
| --- | --- |
| A mipmap minification filter on `backbuffer`. The levels were generated once and never refreshed, so it sampled stale data. | The base filter |
| `discard`. The old renderer never cleared, so a discarded pixel showed what that buffer held two frames earlier. | Opaque black, as section 5 defines for every pass |
| Different minification and magnification filters on `backbuffer`, at a quality above 1, where the output is scaled down. | `present` uses one filter in both directions |
| `backbuffer` with `repeat` wrap and linear magnification: edge pixels of the output blended with the opposite edge. Mirrored repeat is unaffected. | Edges are clamped |
| A sampler declared inside a conditional block. | The declaration moves in front of the outermost block; if the name is declared more than once, or the declaration spans several lines, it stays as written, without its texture |
| A library texture named like a camera sampler, on a sampler that is not a camera. | Not bound |
| A sampler declaration produced by a macro (`#define DECL uniform sampler2D noise;`). The old pattern found the name in the macro body. | The source is left as written and the texture is not bound |
| A sampler whose name the old pattern last matched with another sampler type, for example `uniform sampler2D noise;` and then `// uniform samplerCube noise;`. The old renderer bound a texture of the other type, so the sampler read whatever its texture unit held from earlier drawing. | Not bound: it reads black |
| A camera sampler that the old pattern found more than once, comments included. The old renderer gave the camera to the first binding and replaced it with an empty one. | The camera image |

Three differences carry no warning, because one would appear on nearly every shader of that kind or cannot be known when converting: a cubemap's wrap setting is dropped (it only ever affected face seams on GLES2); a backbuffer preset is resampled once per pixel instead of being pre-scaled, which can differ by a fraction of a pixel; and a preset image so wide that its tile, scaled to the target width and rounded, is zero pixels high starts as opaque black, where the old renderer used no preset (whether that happens depends on the render width).

Text in a database shader that is project syntax is read as project syntax: an `#include`, a `#pragma se` line or a `#pragma once` that was a driver error or an ignored pragma before now gets the project format's own error or warning, which names it. On a GLES2 context, `#version 300 es` is a capability error reported before compilation instead of a driver error.

### 12.2 Three phases

1. **Convert in memory.** A DB shader is stored and edited as it is today. To render it, convert it and run the result through `prepare` like any project; a `ProjectFiles` view over the database supplies the converted text and the textures. Diagnostics map back to the stored text through the converter's source map. From this point every existing shader exercises the converter and the one pipeline.
2. **Migrate.** One time, write every DB shader and every library texture into a single library folder in ShaderEditor's own storage (UX 8): shaders as `<title>.frag`, textures once in `textures/`. Carry thumbnails, quality, and the wallpaper selection into app metadata. Write and verify each file before marking its shader as migrated, so an interrupted migration resumes and a failed one leaves the shader usable from the database. Keep the database as a read-only backup for at least one release.
3. **Remove.** Delete the database shader and texture code and the database view. The converter stays, for old shader text that arrives from outside.

**Save as files** applies the converter to one shader on request at any time before migration, through the ordinary Name/Location flow, and leaves the original in place.

### 12.3 Old shader text from outside

A file is always a project file; never choose a dialect per file at render time. Shader text written for the old conventions is helped by its diagnostics instead: an unprovided sampler whose name matches a library texture, or a trailing `///` sampler comment, offers **Convert**, which applies the converter to that file. Without that action the text is read as written, and a `///` comment is only a comment.

## 13. Notes for later

Nothing here is part of v1. These notes say where two likely additions attach, so that what v1 builds does not stand in their way.

**A vertex shader.** A pass today is one fragment shader drawn over a full-screen rectangle with a vertex shader the engine supplies. A user's vertex shader would belong to a pass, and the pass file would name it, never the other way round: by default the sibling with the same stem (`X.vert` beside `X.frag`), or the file a line in the fragment shader names. The consumer names the producer, in one direction, as an input names its source. Nothing is found by scanning a folder, and a `.vert` file that no pass names has no role. What this needs from v1 is already there: role comes from references (section 2.1), a pass is identified by its fragment file's path (section 6), and an ending has meaning only through the language table, so a stage is not read out of a name anywhere else. The stem rule would be the one new place a name is looked at, and it only says where to look by default.

**A second language.** A text file's language comes from the one table of section 2.1, so another language is a row there. A pass is compiled in the language of its file, and an include is in the language of the file that includes it (section 8). What a host directive says is the same in every language (section 3); how it is written is the language's. A language without a preprocessor, which WGSL and AGSL are, would carry the same `se` text in a line comment of the pass file, not in the manifest: a folder needs no manifest, an opened loose document has none, and a directive belongs next to the declaration it describes. GLSL keeps `#pragma se` as its only spelling. Such a language also brings its own entry point where GLSL has `main`, its own declaration for an input to attach to, and, where it has no loose uniforms, declarations of the engine's uniforms that the host generates. A build would have to say which languages a device can run. Kind, role, the entry rule, what inputs, sampling and targets mean, and the pass graph do not ask about language. NEUTRALITY.md lists where the implementation assumes GLSL ES or OpenGL ES, and what would have to move.
