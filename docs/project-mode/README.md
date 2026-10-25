# ShaderEditor project mode: implementation handoff

This package specifies the revised project mode discussed in issue #191. It is an implementation contract, not a description of shipped functionality. The uploaded original memo and earlier discussion explain the motivation; this package replaces their conflicting syntax and rules.

## Start here

Use **GPT-6.1 Sol**, with **High reasoning** if the client offers that setting. Select the model in the Codex client; mentioning a model inside a prompt does not change the running model.

Run sessions **S1 through S9 in order**, in the same checkout or on branches that include the preceding session's work. These are successive implementation sessions, not parallel agents. Start a fresh conversation for each session. Reuse the implementation prompt in [PROMPTS.md](PROMPTS.md), changing only the session ID.

Each session reads the repository's applicable `AGENTS.md`, this file, and [HANDOFF.md](HANDOFF.md), then only the contract sections and files relevant to that session. Do not resend the entire design conversation.

| File | Purpose |
| --- | --- |
| [SPEC.md](SPEC.md) | Normative syntax, resolution, rendering, and compatibility rules |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Responsibilities, integration points, and constraints |
| [DECISIONS.md](DECISIONS.md) | Syntax alternatives, architecture tradeoffs, and deliberate simplifications |
| [UX.md](UX.md) | Concrete authoring, file, error, and wallpaper workflows |
| [IMPLEMENTATION.md](IMPLEMENTATION.md) | Ordered sessions with deliverables and exit criteria |
| [ACCEPTANCE.md](ACCEPTANCE.md) | Named, observable acceptance cases |
| [HANDOFF.md](HANDOFF.md) | Current implementation state and evidence; update after every session |
| [PROMPTS.md](PROMPTS.md) | Copy-and-paste implementation, continuation, and review prompts |
| [examples/README.md](examples/README.md) | Small executable source fixtures and expected results |

## Decisions already made

The user model is: **a project is a folder; an entry shader produces the picture; includes share code; inputs read images or shader outputs; backbuffer reads that pass's previous frame.**

```glsl
#pragma se input "assets/noise.png" filter=nearest wrap=repeat
uniform sampler2D noise;
```

- Ordinary GLSL declarations remain explicit. An input annotation attaches to the immediately following direct declaration.
- There is one annotation spelling: `se input`. No named-target variant, `se bind`, `se sampler`, generated uniforms, or `$`/`@`/`_` placeholder expressions.
- Files have exact logical paths. No pass IDs, asset IDs, basename inference, global-resource fallback, or mandatory directory roles.
- Open any folder granted by Android. ShaderEditor exposes its own user files through a DocumentsProvider; creation defaults there and Change uses the same native folder picker.
- Save edits in place. No managed/linked/imported modes, mandatory manifest, or automatic whole-folder copy.
- Share and Save a copy collect the selected shader and its dependencies; unrelated folder contents require explicit inclusion.
- Resources selected from the library are copied into the project; already-local files are reused.
- `se target` configures the default pass associated with a shader entry file.
- Programs, input bindings, and pass instances are separate internal concepts. File authoring creates one default instance per shader path; this is not a restriction on the runtime data model.
- Native v1 includes basic multipass and previous-frame reads. Earlier sessions deliver development slices, not complete v1 support.
- Existing shaders keep their behavior through one fixed legacy adapter. New project features do not extend legacy syntax.
- No scripting runtime, graph editor, asset sidecars, general override system, or new app framework in this work.

## Contract and change control

The current user's instructions override this package. Within the package, SPEC defines language/runtime semantics and UX defines product behavior; ARCHITECTURE and IMPLEMENTATION explain implementation rather than superseding either. ACCEPTANCE must agree with both. HANDOFF reports evidence; it cannot redefine behavior.

Routine implementation choices are delegated to the implementing session. Do not repeatedly ask for approval for reversible repository work. If a real contradiction prevents implementation, document the smallest correction, its rationale, and its effect on acceptance cases. Do not silently introduce a second syntax or compatibility mode.

Repository reality takes precedence over stale file names and build commands. The local checkout inspected for this handoff was `cf4ac238caf918fb8396ef7793ede14dec7f16af`; earlier review also inspected upstream `e00a6e80a5b37a299d8849ad04a20ded6eb57da9`. These are reference points, not required implementation bases. Inspect the actual starting revision and preserve unrelated user changes.

The scope of this delivery is documentation and fixtures. App implementation and Android runtime validation have not been performed by the handoff author.
