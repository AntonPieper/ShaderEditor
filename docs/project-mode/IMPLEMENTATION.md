# Ordered implementation sessions

Use GPT-6.1 Sol with High reasoning when available. Complete one session per conversation and update HANDOFF.md. Do not execute these as concurrent agents: later sessions depend on the exact contracts and integration work of earlier ones.

The list is a dependency sequence, not a time estimate. If a session exceeds its context budget, checkpoint the exact partial state and use the continuation prompt for the SAME session. Do not mark a phase complete because scaffolding exists. Do not start a later session to hide incomplete dependencies.

## Shared working rules

1. Read applicable AGENTS.md, README, HANDOFF, and the relevant sections below. Inspect the actual current checkout before relying on historical file names.
2. Preserve unrelated changes. Add implementation to the existing architecture and dependencies. Avoid broad cleanup and new frameworks.
3. Implement the concrete slice, including caller integration and error handling. Do not stop after a plan, TODO stubs, or a new model unused by callers.
4. Add tests for semantics and regressions, not getters or structures that merely duplicate the implementation. Keep CPU preparation tests runnable without a device where possible.
5. Run focused tests, then the required build/lint checks once. Repeat only after a relevant change or failure. If infrastructure blocks a check, record the exact blocker and do all unaffected work.
6. Update HANDOFF with files, decisions, actual commands/results, outstanding cases, and the next exact step. Do not rewrite the approved contract for convenience.
7. Report concise completion and limitations. Stop at this session's boundary. No push, publication, deployment, or unrelated changes are part of these prompts.

Recommended Android checks after app code changes: `./gradlew :app:assembleDebug :app:lintDebug`, plus the focused JVM/instrumentation tasks actually available. S1 may add the smallest normal test dependency/setup if none exists. Discover SDK/JDK requirements from the actual checkout. Do not alter compile/target SDK, plugin versions, or signing configuration just to make this feature easier.

## S1 — Native source contract and preparation

Read: SPEC 1–3, 8–9, 11; ARCHITECTURE 2–4; acceptance F01–F10.

Deliver:

- Extend the existing project concepts only as needed for immutable text snapshots, logical paths, source locations, and unresolved input/target descriptions. Keep binary resources distinct from text.
- Manifest parsing with defaults, duplicate/unknown-key rejection, path normalization and containment.
- A comment-aware lexer for the exact adjacent `se input` grammar, direct uniform attachment, `se target`, and conditional-depth restrictions.
- Static recursive include assembly with source mapping, repeated includes, cycle/depth checks, generated-line provenance, and valid version/directive ordering.
- Preparation diagnostics independent of the editor. No resource-name inference and no full macro evaluator.
- Meaningful JVM fixtures covering accepted/rejected syntax and error locations. Update editor lexical highlighting minimally if required to avoid treating valid host directives as broken syntax; richer insertion UI belongs to S6.

Exit: F01–F10 pass in focused tests; app still builds/lints. New code is a callable preparation component with clear output, not a renderer rewrite. No GPU/runtime change required yet.

## S2 — Resolved inputs and graph planning

Depends on S1. Read: SPEC 3.2–7, 11; ARCHITECTURE 2, 4; acceptance G01–G08.

Deliver:

- Separate program, input, and pass-instance descriptions, using existing types where appropriate.
- Discover only shader paths reachable from entry through both temporal modes. Allocate graph identity before following previous cycles.
- Resolve image/pass inputs, sampling defaults, target dimensions, and typed resource requirements. Do not load DB globals in native preparation.
- Validate the current-edge DAG with deterministic order and source-located cycle diagnostics.
- Compute explicit historical requirements and leave a clear linked-interface step for implicit backbuffer/builtin demand in S3/S4.
- A CPU render plan description consumed by the upcoming GL builder; no GL handles or Android storage operations in this description.
- Plan tests with independently expected order, reachability, sizes, errors, and history requirements.

Exit: G01–G08 pass. Paths with duplicate basenames work, previous-only producers remain reachable, and unreachable broken files are ignored. Do not implement speculative graph manifests/instance overrides.

## S3 — Shared single-pass runtime and legacy adapter

Depends on S2. Read: SPEC 3–4, 9, 12; ARCHITECTURE 3–5; acceptance R01–R04, L01–L02.

Deliver:

- A GL candidate builder consuming resolved program/pass/resource descriptions, active-uniform reflection, and meaningful native missing/type-mismatch diagnostics.
- A fixed legacy adapter translating existing DB texture lookup, comment settings, source preparation, backbuffer presets, and builtin needs into the same descriptions. Preserve existing behavior and sample flows; do not expose new syntax in legacy mode.
- Single-pass native rendering with local images and engine uniforms. A minimal development/test route to load a native snapshot is sufficient until S6.
- Candidate/active separation: failed builds and stale completions retain the prior program/resources. GL-thread ownership and context-loss cleanup are explicit.
- Route existing preview/fullscreen/wallpaper rendering through the shared boundary without leaving a permanent second drawing engine. Full file storage/transport changes belong to S5; do not disguise native snapshots as only entry strings.
- Move file/DB image loading and decoding out of GL draw/build work as needed. Retain type-specific existing camera/cubemap behavior through normalized inputs.

Exit: current single-pass legacy samples remain correct; native minimal/texture examples render; R01–R04 and L01–L02 have evidence. No multipass claim yet. Record unavailable device tests honestly.

## S4 — Multipass, frame transactions, capabilities

Depends on S3. Read: SPEC 4–7; ARCHITECTURE 5–6; acceptance R05–R12 and G06–G08.

Deliver:

- Per-pass targets/history and execution of the resolved order. Previous outputs remain fixed until a project-wide commit.
- Shared per-frame time/sensor/camera capture, unioned provider demand, per-pass resolution and touch transforms.
- Correct current/previous simultaneous reads, previous-only producers, implicit backbuffer, reset/pause behavior, and retained completed presentation on failure where the context remains usable.
- RGBA8 baseline, validated RGBA16F, framebuffer/size/unit checks, target rounding, and bounded allocation including old/candidate coexistence.
- Correct different sampler settings for the same source within one draw, including a GLES2 path. Do not mistake per-unit binding for independent GLES2 sampling state.
- Developer fixture playback or render tests with known pixel/state expectations. Validate history values, not just successful shader compilation.

Exit: multipass examples and R05–R12 work on exercised backends. Unsupported hardware features fail clearly. Remaining unexercised hardware is recorded as a validation gap, not a passing check. Remove obsolete single-target assumptions from the common renderer.

## S5 — Native persistence, drafts, and session transport

Depends on S4. Read: SPEC 1–2, 9–10; ARCHITECTURE 7; UX 1–3, 5; acceptance P01–P10.

Deliver:

- ShaderEditor DocumentsProvider exposing only user authoring files, with native Files/third-party access, accurate capabilities, containment, identity/rename behavior, and notifications.
- One native document-access path for own-provider folders, other SAF trees, and individual document grants. A recent/session index stores app metadata, not a second copy of folder contents.
- Lazy any-folder opening and dependency-scoped snapshot capture. Do not recursively import a folder to browse it.
- Stable local project/session identities, immutable revision snapshots, persistent drafts, external revision/conflict detection, and partial-save recovery.
- Correct source/asset snapshot invalidation without relying on universal filesystem watchers.
- Small snapshot/session identifiers in navigation; recover referenced state after process recreation. No whole project/asset payload in Intent extras.
- Save through the native document-access path, preserving legacy DB saves separately. Opening or saving a native/loose project must not collapse it into a DB source string.
- Tests for provider failures/conflicts and recovery. Minimal invocation UI needed for validation is allowed; final authoring workflow is S6.

Exit: P01–P10 have automated or reproducible instrumentation evidence. Draft persistence and saved/running revisions remain independent. No sync service or generalized VFS framework.

## S6 — Editor and resource workflows

Depends on S5. Read: UX 1–6, 8–9; SPEC 1, 3–5, 8–9; acceptance U01–U11.

Deliver:

- New with Name/Location and native Change picker; own-provider default and same-folder New file. Open folder in place without a mode/setup question, including empty/mixed/read-only folders.
- Lightweight current-folder file list, per-file edits/undo/selection, manifest/remembered/default entry selection, Run this file local override, and existing preview-mode integration.
- Native Open file and incoming intents; request a containing folder only when dependencies need access. No URI parent guessing.
- Add texture and Add shader input actions generating the canonical adjacent annotations and declarations with correct relative paths. Copy library resources; no runtime global links.
- File-aware errors and last-successful-preview state; pass inspection from the existing graph without rerunning simulation.
- Rename with bounded recognized-reference updates and partial-failure recovery, referenced-delete feedback, save/read-only/conflict states, and restart. General cross-provider moves remain native Files operations.
- Existing app styling/accessibility/localization conventions. Keep legacy library and old Add Uniform behavior usable; offer Save as files for project-only features through the ordinary destination flow (the converter is completed in S8).

Exit: U01–U11 pass using concrete user workflows, and files are persisted/reopened. Native creation may remain behind the single temporary development gate until S9. No graph UI or visual redesign.

## S7 — Wallpaper snapshots and portable projects

Depends on S6. Read: SPEC 9–10; UX 5, 7–8; acceptance W01–W07.

Deliver:

- Share/Save a copy use dependency closure plus explicitly selected extras, preserving relative paths and writing selected-entry metadata only into the copy. Never include unrelated folder files by default.
- Source sharing without a compile gate, incomplete-reference feedback, and bounded ZIP import to a new chosen destination. Save a copy switches only after success.
- Durable applied wallpaper snapshots, current-revision validation, atomic installed-reference publication, independent GL resources, process-restart recovery, and explicit updates.
- Preserve DB-backed wallpaper choices and existing runtime semantics. Native project IDs must not collide with or accidentally overwrite legacy shader-ID preferences.
- Thumbnails from completed outputs without extra simulation steps. Keep preview quality and surface target differences explicit.
- Diagnostics/actions for missing access, invalid updates, unsupported versions, and archive failures.

Exit: W01–W07 pass, including source-folder removal after applying a wallpaper, broken-source sharing, and export/import round trip. No external publication.

## S8 — Faithful legacy conversion and regression coverage

Depends on S7. Read: SPEC 12; UX 8; acceptance L01–L05.

Deliver:

- Save as files for representable legacy records using the ordinary Name/Location picker, resource copying, canonical annotations, and preservation of originals.
- A deterministic conversion report for unsupported sampling/cubemap/preset semantics. Never silently downgrade behavior or leave an incomplete copy selected as successful.
- Distinguish explicit legacy imports from native loose documents. No name-based format guessing.
- Regression evidence for bundled samples, custom textures, old comment settings, backbuffer presets, camera, supported legacy cubemaps, and wallpaper flows.
- Document fixed legacy adapter scope and remove any accidental legacy lookups from native preparation or origin tests in rendering.

Exit: L01–L05 pass on exercised paths, limitations are visible in UI, and unchanged legacy content remains usable. No automatic bulk migration or DB deletion.

## S9 — Integration closure and rollout

Depends on S8. Read: all open items in HANDOFF and ACCEPTANCE; inspect SPEC/UX only where needed.

Deliver:

- One bounded audit of cross-session seams: snapshot freshness, process recreation, context loss, graph frame commit, sampler variants, own-provider access/containment, permissions, partial saves, library/save origin, and wallpaper publication.
- Fix material integration failures within scope; no speculative architecture rewrite or new features.
- Run build/lint and the meaningful automated suite; exercise available Android/emulator/device matrix. Distinguish GLES2, GLES3, optional float targets, preview, wallpaper, and unavailable physical-device coverage.
- Confirm all acceptance IDs have an actual status/evidence entry. Do not mark tests passed solely because code exists or desktop GLSL compiled.
- Enable native creation as the default only when the required release gate is verified. If required Android validation is unavailable, leave the rollout gate in place and report the exact remaining gate; complete all implementable fixes.
- Update in-repo user/help documentation, remove temporary harnesses or clearly isolate development-only fixtures, and leave an accurate maintainer handoff.

Exit: feature is implemented and validated to the recorded coverage; rollout status is explicit. Report remaining risks without claiming unsupported hardware was tested.
