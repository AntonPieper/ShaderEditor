# Implementation handoff

Update this file after every implementation or continuation session. Keep the current state concise; record evidence without copying entire command logs. Do not mark a session complete until its integration and exit criteria are satisfied.

## Current state

- Current phase: **documentation handoff; S1 has not started**.
- Next session: **S1 — Native source contract and preparation**.
- Reference checkout at document creation: `cf4ac238caf918fb8396ef7793ede14dec7f16af`.
- Changes delivered: this documentation package and source fixtures only. No production app code changed.
- Existing foundation: canonical project/session/source wrapper classes and renderer helper decomposition are present in the inspected checkout.
- Known integration gaps: text-only project files, renderer entry-string extraction, legacy DB save routing, session serialization in navigation, no full native project parser/storage/multipass execution.
- Starting worktree before package creation: clean. Later sessions must inspect the current worktree rather than relying on this historical observation.
- Model workflow: select GPT-6.1 Sol in the client; use High reasoning when available. One ordered session per conversation; no automatic parallel agents.

## Session ledger

| Session | Status | Evidence / remaining work |
| --- | --- | --- |
| S1 | NOT STARTED | Parser, include assembly, source maps, format fixtures |
| S2 | NOT STARTED | Resolution and static graph planning |
| S3 | NOT STARTED | Shared single-pass backend, legacy adapter, atomic candidates |
| S4 | NOT STARTED | Multipass/history, input snapshots, sampler variants, capabilities |
| S5 | NOT STARTED | Own DocumentsProvider, unified native access, drafts, conflicts, recoverable transport |
| S6 | NOT STARTED | Native editing and resource insertion workflows |
| S7 | NOT STARTED | Portable projects and installed wallpaper snapshots |
| S8 | NOT STARTED | Faithful conversion and legacy regression checks |
| S9 | NOT STARTED | Integration closure and release gate |

## Validation baseline

No app build, Android run, or production implementation test was performed as part of this documentation delivery. All acceptance IDs are **NOT RUN** until an implementation session supplies evidence. Syntax checks of example GLSL, if recorded below, do not establish host-parser, graph, storage, or Android behavior.

Documentation delivery checks are recorded in the delivery validation subsection after they run.

## Fixed decisions worth preserving

- Native folders use one document workflow. Own user files are a DocumentsProvider location; no managed/linked/imported UI modes or automatic whole-folder clones. Share/copy collects selected dependencies only.
- Canonical namespace is whitespace-separated `#pragma se input` / `#pragma se target`; punctuation alternatives are deliberately not aliases. See DECISIONS.md.
- Adjacent `se input` owns the source and optional sampling settings; exactly one direct uniform declaration follows. No named-target syntax or placeholder expressions.
- Host annotations are outside GLSL conditionals and forbidden in include files. Literal includes are statically expanded even in disabled GLSL branches; cyclic includes are rejected.
- Program, input binding, and pass instance are separate internal concepts. File-based authoring creates default instances only; no graph manifest/overrides in v1.
- Both temporal edge kinds determine reachability; only current edges determine topological order.
- Do not silently disable GPU capability checks, GLES2 sampling correctness, conflict detection, or source mapping to complete a session.
- Existing builtin APIs remain authoritative at the actual implementation base. Do not introduce/remove a time provider because older/newer reference commits differ.
- A frozen legacy adapter preserves old semantics; native conversion can decline unrepresentable settings rather than inventing another compatibility mode.

## Current blockers

None established for implementation. SDK, emulator/device, and build availability must be checked when needed. Do not infer readiness from a prior session's environment.

## Update template for each session

Replace stale current-state entries and append a short record:

```text
Session / date / starting revision:
Status: COMPLETE | IN PROGRESS | BLOCKED
Implemented behavior:
Files and important entry points:
Contract decisions or corrections (with rationale):
Checks: exact command or manual steps, result, device/backend when relevant
Acceptance IDs covered / failing / blocked:
Known limitations and any rollout gate:
Uncommitted work and unrelated changes preserved:
Next exact step / next session:
```

If interrupted, describe what compiles, what is partially wired, and the next edit. Do not leave only a vague TODO. Keep required checks marked BLOCKED/NOT RUN when they were not executed.

## Documentation delivery validation — 2026-10-25

- Package: 20 files, including 10 Markdown documents, seven complete shader programs, one include helper, one manifest, and one PNG. DECISIONS.md records the syntax comparison and architecture alternatives.
- Latest feedback incorporated: own DocumentsProvider/native folder picker; any granted folder opens without initialization; default New location with Change; one native save path; explicit running-file selection; dependency-only sharing/copying; whitespace-separated pragma namespace.
- `python /workspace/work/check_project_mode_docs.py`: PASS for local Markdown links, fenced blocks, trailing whitespace, fixture manifest entries, canonical example syntax, and all 63 unique acceptance IDs/range references. This is a delivery-time scratch checker, not an app test suite.
- `python /workspace/work/check_project_mode_examples.py`: seven shader programs compiled and linked; all annotated fixture resource paths resolved. The scratch harness expands includes and removes host pragmas before compiling GLSL ES 1.00 source through Mesa 25.0.7 llvmpipe, reporting OpenGL ES 3.2. It does not implement or validate the native host grammar, graph execution, actual sampling, Android providers, or wallpaper lifecycle.
- App build/lint and Android/device acceptance cases: NOT RUN, because this delivery changes documentation/fixtures only. No implementation session is complete.
- Next exact action: select GPT-6.1 Sol and run the S1 reusable prompt in PROMPTS.md against this checkout. Inspect current changes before implementing; this package was uncommitted at delivery.
