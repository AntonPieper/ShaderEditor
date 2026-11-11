# Acceptance cases

These are required observable outcomes, not claims that tests already exist. Implement focused tests in the PR owning each case (IMPLEMENTATION lists the owner of every ID). Keep actual execution evidence in HANDOFF.md. Use unique IDs in test names or evidence so later sessions can find coverage without reading every file.

## Format and source preparation — M1–M3, M6

| ID | Scenario | Expected result |
| --- | --- | --- |
| F01 | No manifest; partial manifest; malformed/duplicate/unknown fields; unsupported version | Fixed v1 defaults for absence; exact supported overrides; clear errors for invalid input |
| F02 | Nested relative paths, spaces/Unicode, composed versus decomposed spelling, aliases, escaping `..`, absolute/URI paths, a backslash in a path, duplicate normalized files | Exact logical resolution; NFC-equal names match; aliases identify one file; no escape sequences; unsafe/ambiguous trees rejected |
| F03 | Adjacent input plus comments, whitespace, precision qualifier, multiline direct declaration; every option of `se input` and `se target`, including `min`/`mag`, `mipmap`, two-value `wrap`, `initial`, `present` | One input attached to the declared variable without repeating its name; each option parsed to its documented meaning and default |
| F04 | `filter` together with `min` or `mag`; `mipmap` on a pass output or backbuffer; `wrap` on a cubemap; named target, placeholder, unsupported se.input/se::input/se_input spelling, unknown command/key, duplicate key/annotation, missing/unsupported declaration | Source-located error; no permissive fallback to old syntax |
| F05 | Pragmas inside comments, conditional blocks, includes, or separated from target by directives | Comments ignored; invalid placement rejected; no accidental capture of later uniforms |
| F06 | Nested/repeated includes, normal include guards, `#pragma once` | Correct assembled GLSL and original file/line mapping; no hidden include-once; located warning for `#pragma once` |
| F07 | Missing include in `#if 0`; guarded include cycle; macro/angle include; crossed conditional boundary | Defined static-resolution/placement errors, with the include chain |
| F08 | ESSL version plus extensions and the two engine-generated lines; version inside include | Valid entry ordering preserved; included version rejected; generated diagnostics not blamed on user lines |
| F09 | Error in a nested include; unfamiliar driver-log format | Correct source navigation where mapping is possible; raw fallback retained |
| F10 | Excessive include depth/expanded bytes; BOM/CRLF; UTF-8 path | Bounded helpful error; normalized text with correct source identity |

## Static graph planning — M4

| ID | Scenario | Expected result |
| --- | --- | --- |
| G01 | Empty/unused files and duplicate basenames in different directories | Only entry dependencies loaded; exact paths remain distinct |
| G02 | Diamond current-dependency graph; repeated references to same shader | Producer executes once; deterministic topological order |
| G03 | Current-frame self-edge and multi-node cycle | Rejected with dependency locations and readable cycle path |
| G04 | Self-previous and two-pass previous cycle | Discovery terminates; no false same-frame-cycle error |
| G05 | Producer referenced only through previous output | Included and scheduled to produce subsequent history |
| G06 | Previous requested by a consumer, producer has no backbuffer declaration | Producer gets history without relying on own-uniform discovery |
| G07 | Optimized-out declared input with missing source or current cycle | Static dependency validation still reports the problem |
| G08 | Scaled/fixed targets, quality changes, tiny/overflow/invalid dimensions | Exact rounding and bounds; fixed dimensions unchanged by quality |
| G09 | `initial` naming an image, a shader, a shader that reads a pass with its own initial shader, and two shaders that are each other's initial source | Initial sources are dependencies and are ordered for reset; the cycle is rejected with its path; `initial` on a pass without history readers warns |

## GPU execution — E12, E15–E17 (R01–R04, R13–R14); M15–M16 (R05–R12)

| ID | Scenario | Expected result |
| --- | --- | --- |
| R01 | Minimal/texture example; builtin coexistence; a uniform without a provider; a builtin with the wrong type | Correct render for valid input; a warning with its fix for the last two, and the shader still runs |
| R02 | Invalid candidate and late result from an older revision | Prior plan remains usable; stale completion cannot become active |
| R03 | Context recreation, cancelled build, repeated successful/failed reloads | No stale GL handles or unbounded resource growth; defined reset |
| R04 | Image orientation, alpha, two samplers for one static image with different settings, each mipmap mode, a cubemap strip, a strip with the wrong aspect, a non-power-of-two image with mipmaps on GLES2 | Defined channel/orientation behavior; independent settings; correct level selection; faces in +X −X +Y −Y +Z −Z order; specific errors for the last two |
| R05 | Incrementing producer; consumer reads current and previous simultaneously | At frame n, distinct expected current/previous values; no per-pass early swap |
| R06 | Previous-only producer and valid previous-cycle fixture | All required passes execute once; known state sequence is preserved |
| R07 | Shader discards pixels, first frame, resize, restart | Defined opaque-black clear/history initialization and project-wide reset |
| R08 | Two target sizes with one pointer/time/camera snapshot | Pass-local resolution/pixel coordinates; shared frame/time/input values |
| R09 | Pause/resume with and without surviving context | No extra simulation steps; retained history only when context survives |
| R10 | Same pass output consumed twice with different filters/wrap in ONE program; same source with different settings in two different passes | GLES3: independent sampling, including historical output, no second producer execution. GLES2: capability error naming both inputs for the one-program case; the two-pass case works |
| R11 | Unsupported float rendering/filtering, NPOT wrapping, texture units, size, or allocation budget | Specific capability/limit error; no silent format/quality/sampler fallback |
| R12 | Candidate fails to build while an old plan is active; target allocation fails at activation | Build failure leaves the old plan running with its history; allocation failure shows the error state, never a stale or half-built frame |
| R13 | History with no `initial`, with an image, and with a shader that itself reads an image; then restart and resize | First frame's previous read shows black, the stretched image, or the shader's output; the same again after every reset; the shader source runs once per reset |
| R14 | Running shader at quality below 1 with default `present`, with `present=linear`, and `present` on a non-running pass | Crisp upscaling by default, smooth with `linear`, no effect on other passes |

Use tiny deterministic pixel fixtures for R05/R06 (for example increment red by `1/255` in RGBA8), with an explicit tolerance. A screenshot of a plausible animation is insufficient evidence for history semantics. Run at least some real GLES/Android rendering checks; mock calls alone cannot establish sampler/framebuffer behavior.

## Storage and recovery — M7–M9

| ID | Scenario | Expected result |
| --- | --- | --- |
| P01 | Save/reopen own-provider multifile folder with image | Content, entry, and identity survive; no accidental DB-only save |
| P02 | SAF tree read/write versus read-only provider | Correct capability-aware actions and persisted grants |
| P03 | Single-document grant includes a sibling | Diagnostic with Open folder; no guessed parent access; no merge of document and tree grants |
| P04 | Local edit conflicts with changed/deleted external file | Neither version discarded; reload/keep-edits/save-copy behavior is explicit; autosave suspended until conflict resolved |
| P05 | Provider fails after some files save | Successful/failed files distinguished; drafts retained and retry possible |
| P06 | Process recreation with unsaved edits and a large asset-containing project | Draft recovery; small Intent handles; no whole-project Binder payload |
| P07 | Asset/config changes during preparation; revoked permission; offline provider | One captured value per file; detected races retried/reported; immutable candidate after capture; no claim of provider-wide transactions |
| P08 | Open/edit own-provider file in Android Files and another editor; rename with an existing handle | Same visible files; correct notifications, flags, supported ID/grant lifecycle; clean reload or real conflict |
| P09 | Provider requests with traversal/malformed IDs, escaping links, or app-private paths | Access confined to exposed user-files root; DB/drafts/wallpaper never exposed |
| P10 | Open very large mixed folder; build requires a few files; own-storage folder picked through the system picker | Lazy listing and dependency-only reads; no whole-folder clone; own-authority tree resolves to the directory store |

## Editor behavior — M10–M12 (U04 in M17)

| ID | Scenario | Expected result |
| --- | --- | --- |
| U01 | New shader and immediate edit; Change location to own subfolder or external provider | One Name/Location sheet with working default; same native picker; valid template and no manifest/setup wizard |
| U02 | Switch entry/include/pass files and undo in each | Independent text/history; selected entry/preview unchanged |
| U03 | Add local/external/library texture, including name conflict and nested editing file | Local file reused; external/library copy; canonical annotation with correct relative path and unused name |
| U04 | Add shader input in current/previous mode and inspect output | Correct dependency syntax; inspection shares graph state |
| U05 | Compile error in another file; save invalid source | Correct file navigation; save and preview states are independent |
| U06 | Rename a file the running shader depends on; external move then Refresh | Notice before renaming; no reference rewriting; located missing-file diagnostics afterwards; drafts kept |
| U07 | Delete referenced file, remove recent shortcut, read-only save | Clear distinct actions; no accidental external deletion/overwrite |
| U08 | Long paths, keyboard navigation/accessibility, localized status labels, auto/manual preview | Existing UX remains usable; state not conveyed solely by color |
| U09 | Open empty/mixed/read-only folder with no main/manifest; create/open a shader and Run | Folder always browsable; no automatic import/scan/wizard; selected shader can run; writes respect capabilities |
| U10 | Run this file, edit another file, reopen with/without manifest, refresh changed manifest, Set default shader | One rule in every case: remembered choice, else manifest, else `main.glsl`; Set default shader clears the remembered choice; preview title accurate; no implicit manifest write or switch on editing |
| U11 | Open standalone document; then add sibling include; open its folder | Standalone works immediately; Open folder offered only when needed; the folder opens as an ordinary project; drafts preserved |

## Portability and wallpaper — M13–M14 (multipass projects re-verified in M15)

| ID | Scenario | Expected result |
| --- | --- | --- |
| W01 | Share/import shader with local resources, Unicode names, compile errors, and explicitly added unused code | Selected closure and explicit extras preserved; no library dependence or compile gate |
| W02 | Unsafe ZIP path/link, duplicate entry, oversized payload, unsupported manifest, single top-level directory | Unsafe archives rejected before publication; a structurally safe unsupported manifest remains unchanged and opens with a render-config error; a single top-level directory becomes the root |
| W03 | Apply wallpaper, close app, revoke/remove external source, recreate process/context | Installed snapshot reopens independently; expected GPU reset |
| W04 | Current edits invalid while prior preview is valid; later successful edit | Invalid apply blocked; explicit successful update replaces installed snapshot atomically |
| W05 | Capture thumbnails/inspect pass with active feedback; switch legacy/native wallpaper | No extra simulation steps or identity collisions; existing runtime behavior preserved |
| W06 | Share/Save a copy from broad folder with unrelated private files; entry is a remembered Run this file choice | Only closure/extras copied; relative paths intact; portable entry metadata only in copy; source unchanged |
| W07 | Missing/malformed dependency; failed destination write; invalid/unsupported manifest | Incompleteness explained, no false self-contained/success claim; source/drafts retained; no silent manifest downgrade |

## Existing DB shaders — M5, E17 (L01–L02, L05), M18 (L03–L04), M20–M21 (L06–L07)

| ID | Scenario | Expected result |
| --- | --- | --- |
| L01 | Existing bundled samples and ordinary DB load/edit/save/preview | Same appearance and behavior when rendered through converter, `prepare`, and the one runtime; error lines point at the stored text |
| L02 | Each row of the SPEC 12.1 table: named textures, every `///` filter and wrap combination including the untouched default, cubemap atlas, backbuffer settings, backbuffer preset, camera | The documented project form for each, and the same picture; the engine contains no name lookup |
| L03 | Save as files for a DB shader using library textures | Independent files with explicit local paths that render the same; original remains usable |
| L04 | Convert a mipmap filter on `backbuffer`; a sampler naming a missing texture | The one inexact setting is converted to its base filter and reported; the missing texture stays an ordinary no-provider warning; nothing else is reported as changed |
| L05 | A file has a sampler named like a library texture and a trailing `///` comment | Read as written: a no-provider warning, the comment ignored. Both offer Convert, which rewrites that file into directives; no dialect is chosen at render time |
| L06 | Migrate a database with many shaders, shared and unused textures, empty and duplicate titles, a wallpaper selection; interrupt it midway; make one write fail | One library folder, each texture once, unique file names, same thumbnails, quality, and wallpaper; resumes after interruption; a failed shader stays usable from the database; database kept read-only |
| L07 | After removal: open old shader text from outside | Convert still works; no database shader or texture code remains |

## Release gates — M14, M19

Every ID above needs an evidence location and status: PASS, FAIL, BLOCKED, or NOT RUN. Existing coverage can be reused when it actually establishes the behavior. Record backend/device/API and commit when needed. Mandatory Android lifecycle and GPU cases cannot be marked PASS from source review or desktop GLSL compilation.

Build and lint must pass. No outstanding failing mandatory case is compatible with declaring native v1 complete. Optional RGBA16F hardware may be unavailable, but unsupported-device diagnostics must be exercised. Lack of an available Android execution environment is a validation blocker, not permission to relabel integration cases as passed.
