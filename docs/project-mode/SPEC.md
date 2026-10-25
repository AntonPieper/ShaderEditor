# Project format v1

Status: proposed implementation contract. MUST, MUST NOT, and SHOULD describe requirements. No claim of current app support is implied.

## 1. Project and entry

A native project is an ordinary folder used as a bounded logical file tree. Any folder granted by Android can be opened, including an empty, mixed-content, or read-only folder. Opening/browsing does not require a manifest or a runnable shader. The portable default entry is `main.glsl`. Only the selected entry and its dependencies participate in execution. Directory names such as `passes`, `include`, and `assets` are conveniences, not semantic namespaces.

An optional UTF-8 `shader-project.json` at the root supports exactly:

```json
{
  "projectVersion": 1,
  "entry": "main.glsl"
}
```

Both fields are optional and default to the values shown. A missing manifest permanently means v1 with portable default `main.glsl`; it never means the latest format. This default does not prevent explicitly running another file. The manifest must be a JSON object. Duplicate keys, unknown keys, a version other than integer 1, or a non-string entry are errors. Entry resolves relative to the project root and must identify a `.glsl` file.

For folder opening, a valid manifest selects its entry. Without a manifest, restore a remembered explicitly run file if it still exists, otherwise select `main.glsl` if present, otherwise leave the preview unselected. Run this file selects a session entry and remembers it locally without changing portable files. An explicit session selection overrides a valid manifest entry until the session ends; refresh honors it. A manifest must still be valid even with a session override. Reopening honors a manifest over remembered selection. Missing/invalid configured entries produce render diagnostics without blocking folder browsing. Set default shader is an explicit action that writes the manifest.

An opened loose document is its own selected entry, regardless of filename. Reuse a known containing tree grant where possible; a single-document grant does not implicitly grant siblings. Request a containing folder only when dependencies require it, and verify that the selected document is actually within that granted tree. This permission detail is not a separate user-facing project type. DB records use the fixed legacy boundary in section 12.

Project names, thumbnail caches, editor state, storage locators, quality preferences, permissions, and installed wallpaper state are app metadata outside the portable tree.

## 2. Paths and file contents

References are quoted JSON strings containing logical relative paths. Spaces and Unicode are allowed. `/` separates segments. Reject empty paths, backslashes, NUL/control characters, absolute paths, drive-qualified paths, and URI schemes. Normalize `.` and `..`, rejecting any escape above the project root. Resolve a binding or include relative to the file containing that directive. Path identity is case-sensitive; require exact spelling. Do not derive paths from `content://` URI text: map logical directory entries to opaque provider locators.

Different spellings that normalize to the same path identify the same resource. A project snapshot must reject duplicate normalized paths. If a provider cannot represent a project's distinct paths, report the conflict rather than renaming silently. Filesystem links and archive links must not escape the root; initially reject links rather than inventing link semantics. Version-control administration directories are not valid dependency targets and are omitted from project export.

Shader/manifest text uses UTF-8. Accept an initial UTF-8 BOM and normalize CRLF/CR to LF while retaining original source locations. Parse host paths before any driver-facing ASCII sanitization. Do not strip Unicode characters out of filenames or mutate tokens to make invalid GLSL appear valid. Preserve raw driver logs for diagnostic fallback.

Recognize `.glsl`, `.png`, `.jpg`, and `.jpeg` extensions case-insensitively while keeping path lookup case-sensitive. Other files may be stored/shared but cannot be bound as native v1 inputs. An unreachable source file is not compiled; a broken experiment does not prevent the selected entry from running.

## 3. GLSL and host directives

Keep ordinary GLSL declarations, precision statements, functions, and version directives. Do not inject declarations for `time`, `resolution`, or other engine uniforms. Preserve the existing engine's supported language versions and builtin uniform API; never silently translate GLSL ES 1.00 to 3.00. A shader requiring an unavailable GLSL version fails with a capability diagnostic.

Only two host directives exist in v1:

```glsl
#pragma se input "assets/noise.png" filter=nearest wrap=repeat
uniform sampler2D noise;

#pragma se target scale=0.5 format=rgba8
```

The canonical spelling is `#pragma se input` or `#pragma se target`: `se` is a complete namespace token, followed by a whitespace-separated command. `se.input`, `se::input`, and `se_input` are not aliases. Diagnose these recognizable unsupported ShaderEditor spellings with the canonical replacement. Match namespace boundaries: unrelated pragmas such as `sequence` must not be captured.

Whitespace may precede `#`, appear between `#` and `pragma`, and separate tokens as in preprocessing directives. Each host directive occupies one physical line. JSON escaping is supported inside its quoted path. Spaces/tabs separate arguments. Permit trailing `//` comments after the final argument. Do not support macro expansion, line continuation, or embedded block comments inside host directive arguments. Whitespace/comments between a completed annotation and its declaration are allowed.

Keys are case-sensitive. Reject unknown commands in the `se` namespace, unknown/repeated keys, malformed values, and trailing tokens. Preserve unrelated vendor/compiler pragmas for the GLSL compiler. Consume recognized host directives and replace them with mapped blank lines before compilation.

Use a comment-aware lexer. A pragma-looking string inside a comment is not a directive. The minimal declaration parser recognizes the grammar below; it is not a replacement GLSL compiler.

### 3.1 Input attachment

```text
input-directive := #pragma se input [quoted-path] [input-option ...]
input-option    := frame=current|previous
                 | filter=nearest|linear
                 | wrap=clamp|repeat|mirror
declaration     := uniform [lowp|mediump|highp] sampler-type identifier ;
```

For file sources, sampler-type is `sampler2D`. For source-less engine samplers, use their existing supported GLSL type (`sampler2D` for backbuffer, the supported camera sampler type for camera inputs). Validate capabilities through existing engine providers.

An input annotation applies to exactly one immediately following direct uniform declaration in the same file. Allow comments, whitespace, and a multiline declaration, but not another declaration, another directive, or a file boundary in between. Do not support annotated comma-separated declarations, arrays, structs, layout qualifiers, or macro-generated declarations in v1. Give a targeted error explaining the supported shape. Unannotated GLSL may use ordinary legal constructs independently of this attachment grammar.

There may be only one input annotation per declaration. Host directives are allowed only in independently compiled shader entry files and at conditional-preprocessor nesting depth zero. They are forbidden in included files and inside `#if`/`#ifdef`/`#ifndef` blocks. They cannot attach across such a boundary. The lexer tracks conditional structure without evaluating GLSL expressions.

The variable name appears only in the GLSL declaration. Named-target syntax, `se bind`, `se sampler`, placeholder targets, and generated uniform declarations are invalid native v1 syntax.

### 3.2 Sources

A quoted image path supplies an image texture. A quoted `.glsl` path supplies that file's default pass output. A shader output defaults to the current frame. `frame=previous` selects its previous completed frame. The `frame` key is invalid for images and for source-less annotations.

With no quoted path, the attached uniform must be a recognized engine-provided sampler. Its source remains that engine provider; the annotation only customizes legal sampling settings. A bare input directive with neither path nor options is an error. Do not allow explicit file bindings to replace reserved builtin names.

Examples:

```glsl
#pragma se input "passes/state.glsl" frame=previous
uniform sampler2D previousState;

#pragma se input filter=linear
uniform sampler2D backbuffer;
```

An unannotated active `backbuffer` retains the implicit previous output of its own pass. Other engine uniforms continue to work under their existing names and types. An unannotated arbitrary sampler does not trigger file/library lookup.

Each declared input path is a required static dependency, even if the GLSL compiler optimizes the uniform away. Missing files and graph cycles remain errors. After linking, warn for an annotated input absent from active-uniform reflection; do not prune its declared dependency. Active native uniforms must have a type-compatible builtin provider or input binding. Report unprovided active uniforms rather than silently assigning zero or texture unit zero. Native v1 does not add scalar-value annotations; use GLSL constants or existing builtin providers.

## 4. Image and sampler semantics

PNG and JPEG are native v1 image formats. The import UI may decode other formats and copy a PNG into the project. HDR, cubemap files, video files, mipmapped sampling, and asset sidecars are out of native v1 scope. Existing engine camera inputs and legacy texture features remain supported through their respective existing contracts.

Decode to RGBA8 numeric channel values with straight alpha; do not implicitly linearize sRGB. JPEG alpha is 1. Honor supported image orientation metadata. Normalize upload orientation so `(0,0)` denotes the image's lower-left corner consistently with pass textures. Do not silently resize or reduce precision to fit GPU limits; report the limit and offer explicit user action. Imports may offer a deliberate resize before copying.

| Source | Default filter | Default wrap |
| --- | --- | --- |
| Image | linear | clamp |
| Pass output/backbuffer | nearest | clamp |
| Other engine sampler | existing provider default | existing provider default |

`filter` sets both minification and magnification. `wrap` sets both axes. `clamp` means clamp-to-edge. No mipmaps are generated for native v1 inputs/targets. Unsupported combinations (including GLES2 NPOT wrapping and external-texture constraints) fail clearly rather than changing sampling silently.

Sampling belongs to the input binding, not the underlying image/pass resource. One source may have multiple consumers using different settings. GLES3 may use sampler objects. GLES2 must handle differing states within the SAME draw correctly, for example with texture variants/copies; changing texture-object state between two unit bindings is not sufficient. Static image variants can be uploaded once; pass-output variants must be refreshed after their producer draws. Copies do not execute the producing pass again. Bound memory/capability checks must include variants. Choose the smallest correct backend implementation; do not build a generalized texture cache framework.

Library resources, including bundled noise, are inserted as project-local copies. There are no native global references, implicit aliases, or background library updates. Internal byte deduplication may be invisible storage optimization; export still contains independent logical files.

## 5. Targets

Each independently compiled shader file may contain at most one `se target` directive, at conditional depth zero. Included files cannot configure targets.

```text
#pragma se target [scale=number | size=WIDTHxHEIGHT] [format=rgba8|rgba16f]
```

At least one property is required when the directive is present. `scale` and `size` are mutually exclusive. Defaults are `scale=1`, `format=rgba8`. Scale is a finite decimal greater than zero and no greater than one; no expressions or macros. Size dimensions are positive decimal integers. Reject overflow and resource-limit violations.

Define project render size as `max(1, floor(surfaceDimension * quality))` per axis. Scaled pass size is `max(1, floor(projectRenderDimension * scale))`. Fixed sizes are independent of quality. The final entry texture is presented across the output surface. Presentation uses linear filtering; texture orientation is consistent with the input rules. Existing wallpaper crop/offset behavior must be accounted for in the input-coordinate transform rather than independently guessed by every pass.

Every pass has one color output, no depth attachment, and no blending. Initialize all historical textures to `(0,0,0,1)`; clear every draw destination to the same value before drawing. Discarded/unwritten pixels therefore have defined contents. Native v1 has no preset-image initialization; initialize from an explicitly bound seed image in GLSL when needed.

RGBA8 is baseline. RGBA16F requires actual renderability and the requested sampling capability; support must not be inferred from GLES version alone. Check framebuffer completeness. Never silently fall back to RGBA8 or another size. Query device texture-size and texture-unit limits; do not rely on the current hardcoded texture count. Include internal draw needs and sampler variants in allocation planning.

## 6. Default pass identity and graph

File authoring creates one default pass per normalized shader path. Multiple references to that path share its execution and output. The program/source and pass instance remain separate internal records; a future graph frontend can create additional instances without a new renderer. Native v1 exposes no instance IDs or override syntax. Reuse algorithms with includes and small wrapper shader files when different configured instances are needed.

Reachability starts at the selected entry and follows ALL explicit shader-input edges, both current and previous. Do not discover/execute unrelated files by folder scanning. Create graph nodes before recursively traversing dependencies so previous-frame cycles terminate during discovery.

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

Do not swap individual pass histories immediately after their draws. Never sample the texture attached as the current draw destination. A pass needs two outputs if any consumer needs its history; otherwise one is sufficient, excluding backend sampler variants. On a failed frame, do not advance histories or frame number. If the context is still usable, preserve/re-present the last completed entry output; allocate the necessary retained presentation texture when it cannot be preserved otherwise. Context loss invalidates GPU output and triggers normal rebuild/reset.

Restart, successful activation of a new project revision, changed render-target dimensions, and GL context recreation reset all project histories and frame number. Restart also restarts ordinary animation time as the existing restart/reload contract requires; do not accidentally reset separately persisted wallpaper runtime contrary to its existing contract. No selective history preservation in v1. Pausing stops frame advancement. If the context survives, preserve history; existing time providers retain their documented pause behavior. Do not implement catch-up simulation steps on resume.

## 8. Includes and source maps

Support only literal `#include "relative/path.glsl"`. Angle-bracket lookup and macro-generated include operands are errors. Resolve relative to the including file, with no fallback search directories or global library. Included files must be GLSL text. Includes may be nested.

Include expansion is static source assembly before driver preprocessing: every literal include must resolve even inside `#if 0`. Ordinary include guards control the resulting GLSL, but do not make files optional. Reject include-stack cycles even if guards would terminate them. Repeated acyclic includes are expanded repeatedly; no implicit include-once behavior. Document this intentionally smaller contract in editor help.

Track conditional nesting in each physical file and reject unbalanced conditional boundaries across include files. Includes do not supply or replace `#version`: reject version directives in included files. The entry retains ownership of version and extension requirements; do not hoist extensions silently. Existing engine-injected extension directives must retain valid GLSL ordering.

Map every prepared line back to original file and line, preserving an include stack where useful. Map engine-generated lines to an explicit generated-source origin, not an arbitrary user line. Retain raw compiler logs and show them when a driver log cannot be mapped reliably. Native host metadata in an included file is an error with that file's location.

## 9. Diagnostics and revision activation

Diagnostics carry project revision, source path/line when available, severity, message, and pass identity when relevant. Report structural errors before GPU compilation where possible; report linked-uniform type/capability errors afterward. Native unknown metadata is an error, never silently ignored.

Prepare a candidate revision without destroying the last valid executable plan. Compilation, resource upload, and validation must succeed before activation at a frame boundary. Stale results cannot replace a newer requested revision. On failure retain the prior preview and label it as showing the last successful build. If no valid plan exists, show an explicit error/empty state. Persistence success and render success are independent.

## 10. Persistence and sharing

Follow UX.md for the native picker, saving in place, external edits, drafts, and recovery. Expose user authoring files through ShaderEditor's own DocumentsProvider. Own files and other providers use the same logical-path resolution and document read/write operations; DB records remain the separate legacy boundary. Provider authority is not a language or user-visible project mode. Never expose app-private databases, drafts, caches, or installed wallpaper state through the user-files root.

Render immutable snapshots of the selected dependency closure, overlaid with current drafts. Do not clone or recursively read an entire folder on open. Capture each required file once for a build; all consumers of it use those bytes. Check available revisions/content on acquisition and before publishing when practical. Retry/report observed concurrent changes. Providers need not offer a transaction across files, so do not claim a globally atomic external snapshot. Once acquired, candidate bytes remain immutable: no lazy read of newer provider content midway through preparation/drawing.

Share and Save a copy gather the selected entry plus the transitive closure of shader inputs, literal includes, and local images, following both current and previous edges. Never implicitly copy unrelated files from the opened folder. Preserve root-relative paths so relative references remain valid. A dependency-free shader may be shared as a single GLSL document only when no manifest metadata is required to preserve its meaning. Otherwise use a ZIP with no enclosing synthetic directory; include a minimal v1 manifest when needed to make the selected entry portable. Adjust the manifest only in the copy, never as a side effect in the source folder. Allow explicit extra files; omit app state and VCS administration directories. Save a copy uses the same collection rules and switches to the copy only on successful completion.

Sharing does not require successful GLSL compilation. Dependency collection is a tolerant use of the same lexer/path rules, not a second guessed language. If malformed directives, missing files, or unreadable resources prevent complete collection, identify them and allow explicit extras or an acknowledged incomplete source package. Never describe that package as self-contained. Invalid/unsupported existing manifests are not silently downgraded; an incomplete source export retains that file as-is, with the limitation shown.

ZIP import rejects unsafe paths, duplicate normalized entries, links, and bounded-size/count violations before publishing. Extract into a new unused folder at the chosen destination, using app-private staging for validation where necessary. A structurally safe archive with invalid/unsupported shader configuration may still be extracted and browsed; preserve its manifest and report the render error. Never overwrite unrelated destination files. Local publication can be atomic; arbitrary document providers cannot promise atomic multi-file writes. On failure retain drafts/staging as needed for retry and report partial destination writes without selecting them as a successful copy/import.

Wallpaper application captures the selected dependency closure from the current valid requested revision and its selected entry. Publish its installed app-private snapshot reference atomically. Never apply an older successful preview by accident. The installed snapshot must survive source permission loss and process recreation without depending on the provider.

## 11. Limits and feature scope

Enforce explicit bounds on expanded source bytes, include depth, files, passes, decoded pixels, GPU memory estimates, and preparation work. Keep limits in one documented policy location. Report the exceeded limit and avoid silently truncating input. Limits may be implementation/device-dependent; they are resource admission policy, not alternate format semantics. Choose conservative values from Android memory constraints and record them in HANDOFF plus user-facing diagnostics.

V1 includes file projects, includes, local 2D images, engine uniforms, basic multipass, history, targets, diagnostics, native editing/persistence, sharing, and wallpaper snapshots. It excludes generated declarations, source-name inference, asset sidecars, implicit global resources, arbitrary graph overrides, a graph editor, a script runtime, compute, SSBOs, multiple outputs, native cubemap files, and live cross-project package updates. Unsupported features fail explicitly.

## 12. Legacy boundary

Preserve existing DB shader behavior, bundled samples, old texture-name lookup, comment-encoded sampling, preset backbuffers, cameras/cubemaps, defaults, and app flows through one fixed legacy adapter. Native source preparation never falls back to legacy name lookup. Renderer code consumes normalized plan/resource descriptions rather than checking origin or format flags.

The internal execution descriptions may represent existing legacy behaviors beyond native v1's authoring vocabulary (for example, an existing mipmap setting or seed image). This preserves behavior without extending native syntax or introducing a second backend. Keep those translations and legacy defaults in the adapter.

The Save as files action explicitly converts a legacy record into an independent native folder using the ordinary Name/Location workflow. Preserve originals and copy resources. Convert only semantics representable by native v1. A mixed min/mag policy, mipmap dependency, cubemap, or preset transformation that cannot be preserved must produce a specific conversion limitation; never claim exact conversion by silently substituting defaults. Existing shaders can continue running through the legacy adapter. Missing old resources are reported, not invented.

Known DB records and explicit legacy-import actions use the legacy adapter. Do not guess legacy/native mode from an arbitrary sampler name or from absence of pragmas. New shaders use native projects after the rollout gate. New project features do not extend legacy syntax.
