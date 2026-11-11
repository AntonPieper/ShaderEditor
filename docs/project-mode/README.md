# ShaderEditor project mode: implementation handoff

This package specifies the revised project mode discussed in issue #191. It is an implementation contract, not a description of shipped functionality. The uploaded original memo and earlier discussion explain the motivation; this package replaces their conflicting syntax and rules.

## Start here

This folder is the design. The implementation plan, its ledger, and the prompts for coding agents are local agent configuration in `docs/agents/project-mode/` and are not part of upstream pull requests.

| File | Purpose |
| --- | --- |
| [SPEC.md](SPEC.md) | Normative syntax, resolution, rendering, and conversion rules |
| [UX.md](UX.md) | Concrete authoring, file, error, wallpaper, and migration workflows |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Responsibilities, seams, integration points, and constraints |
| [ENGINE.md](ENGINE.md) | Diagnosis of the rendering engine and its target design |
| [DECISIONS.md](DECISIONS.md) | Why the contract has its shape, including the decisions of 2026-11-11 |
| [ACCEPTANCE.md](ACCEPTANCE.md) | Named, observable acceptance cases |
| [examples/README.md](examples/README.md) | Small executable source fixtures and expected results |

The work is a list of small pull requests in two tracks: **E** cleans up the rendering engine, **M** adds project mode. References to "IMPLEMENTATION" and to PR IDs such as E15 or M4 in this folder mean `docs/agents/project-mode/IMPLEMENTATION.md`.

## Decisions already made

The user model is: **a project is a folder; an entry shader produces the picture; includes share code; inputs read images or shader outputs; backbuffer reads that pass's previous frame.**

```glsl
#pragma se input "assets/noise.png" filter=nearest wrap=repeat
uniform sampler2D noise;
```

- Ordinary GLSL declarations remain explicit. An input directive attaches to the immediately following direct declaration.
- There is one directive spelling for inputs: `se input`. No named-target form, `se bind`, `se sampler`, generated uniforms, or `$`/`@`/`_` placeholder expressions.
- Files have exact logical paths. No pass IDs, asset IDs, basename inference, global-resource fallback, or mandatory directory roles.
- Open any folder granted by Android. ShaderEditor exposes its own user files through a DocumentsProvider; creation defaults there and Change uses the same native folder picker. Code shared by several shaders lives in a folder that contains them all; that folder is the project.
- Save edits in place. No managed/linked/imported modes, mandatory manifest, or automatic whole-folder copy.
- Share and Save a copy collect the selected shader and its dependencies; unrelated folder contents require explicit inclusion.
- Resources selected from the library are copied into the project; already-local files are reused.
- `se target` configures a pass file's output: size, format, initial content, and how the running shader is scaled to the screen (nearest by default).
- Programs, input bindings, and pass instances are separate internal concepts. File authoring creates one default instance per shader path; this is not a restriction on the runtime data model.
- Native v1 includes basic multipass and previous-frame reads, and the format specifies them from the start. Implementation reaches a complete single-pass workflow first (Gate A after M14) and adds multipass in M15.
- One rule selects the running shader: the remembered Run this file choice, else the manifest entry, else `main.glsl`.
- Three seams carry the implementation: `ProjectFiles`, `prepare`, and `PlanRuntime` (ARCHITECTURE 2).
- The engine has one dialect. Existing DB shaders are converted to project files in memory from the start, migrated to one library folder later, and the database code is then removed. A small pure converter is the only code that knows the old conventions; nothing carries a legacy flag.
- Every old setting has an exact project form: separate min/mag filters, mipmaps, per-axis wrap, cubemaps (a six-face strip), and initial backbuffer content (`se target initial`, from an image or any shader).
- A new shader is a file in the open folder, by default the library folder, so shaders share textures and included code.
- A uniform that nothing provides is a warning and the shader still runs. This is not configurable.
- The rendering engine is cleaned up first, in its own track: one table declares every builtin uniform, a program reflects its uniforms once at link, and the driver swaps plans without recreating the GL context (ENGINE.md).
- Host directives are never allowed in include files; a library takes samplers as function parameters.
- No scripting runtime, graph editor, asset sidecars, general override system, or new app framework in this work.

## Contract and change control

The current user's instructions override this package. Within the package, SPEC defines language/runtime semantics and UX defines product behavior; ARCHITECTURE and IMPLEMENTATION explain implementation rather than superseding either. ACCEPTANCE must agree with both. HANDOFF reports evidence; it cannot redefine behavior.

Routine implementation choices are delegated to the implementing session. Do not repeatedly ask for approval for reversible repository work. If a real contradiction prevents implementation, document the smallest correction, its rationale, and its effect on acceptance cases. Do not silently introduce a second syntax or compatibility mode.

Repository reality takes precedence over stale file names and build commands. The local checkout inspected for this handoff was `cf4ac238caf918fb8396ef7793ede14dec7f16af`; earlier review also inspected upstream `e00a6e80a5b37a299d8849ad04a20ded6eb57da9`. These are reference points, not required implementation bases. Inspect the actual starting revision and preserve unrelated user changes.

The scope of this delivery is documentation and fixtures. App implementation and Android runtime validation have not been performed by the handoff author.
