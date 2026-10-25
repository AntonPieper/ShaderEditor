# Acceptance cases

These are required observable outcomes, not claims that tests already exist. Implement focused tests in the session owning each case. Keep actual execution evidence in HANDOFF.md. Use unique IDs in test names or evidence so later sessions can find coverage without reading every file.

## Format and source preparation — S1

| ID | Scenario | Expected result |
| --- | --- | --- |
| F01 | No manifest; partial manifest; malformed/duplicate/unknown fields; unsupported version | Fixed v1 defaults for absence; exact supported overrides; clear errors for invalid input |
| F02 | Nested relative paths, spaces/Unicode, aliases, escaping `..`, absolute/URI paths, duplicate normalized files | Exact logical resolution; aliases identify one file; unsafe/ambiguous trees rejected |
| F03 | Adjacent input plus comments, whitespace, precision qualifier, multiline direct declaration | One input attached to the declared variable without repeating its name |
| F04 | Named target, placeholder, unsupported se.input/se::input/se_input spelling, unknown command/key, duplicate key/annotation, missing/unsupported declaration | Source-located error; no permissive fallback to old syntax |
| F05 | Pragmas inside comments, conditional blocks, includes, or separated from target by directives | Comments ignored; invalid placement rejected; no accidental capture of later uniforms |
| F06 | Nested/repeated includes and normal include guards | Correct assembled GLSL and original file/line mapping; no hidden include-once |
| F07 | Missing include in `#if 0`; guarded include cycle; macro/angle include; crossed conditional boundary | Defined static-resolution/placement errors, with the include chain |
| F08 | ESSL version plus extensions/generated lines; version inside include | Valid entry ordering preserved; included version rejected; generated diagnostics not blamed on user lines |
| F09 | Error in a nested include; unfamiliar driver-log format | Correct source navigation where mapping is possible; raw fallback retained |
| F10 | Excessive include depth/expanded bytes; BOM/CRLF; UTF-8 path | Bounded helpful error; normalized text with correct source identity |

## Static graph planning — S2

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

## GPU execution — S3/S4

| ID | Scenario | Expected result |
| --- | --- | --- |
| R01 | Minimal/texture example; builtin coexistence; unbound/type-wrong active uniform | Correct render for valid input; targeted errors otherwise |
| R02 | Invalid candidate and late result from an older revision | Prior plan remains usable; stale completion cannot become active |
| R03 | Context recreation, cancelled build, repeated successful/failed reloads | No stale GL handles or unbounded resource growth; defined reset |
| R04 | Image orientation, alpha, and two samplers for one static image with different settings | Defined channel/orientation behavior; independent settings |
| R05 | Incrementing producer; consumer reads current and previous simultaneously | At frame n, distinct expected current/previous values; no per-pass early swap |
| R06 | Previous-only producer and valid previous-cycle fixture | All required passes execute once; known state sequence is preserved |
| R07 | Shader discards pixels, first frame, resize, restart | Defined opaque-black clear/history initialization and project-wide reset |
| R08 | Two target sizes with one pointer/time/camera snapshot | Pass-local resolution/pixel coordinates; shared frame/time/input values |
| R09 | Pause/resume with and without surviving context | No extra simulation steps; retained history only when context survives |
| R10 | Same PASS output consumed twice with different filters/wrap in ONE draw, on GLES2 and GLES3 | Independent sampling, including historical output; no second producer execution |
| R11 | Unsupported float rendering/filtering, NPOT wrapping, texture units, size, or allocation budget | Specific capability/limit error; no silent format/quality/sampler fallback |
| R12 | Failure after some passes drew, and failed candidate allocation while old plan is active | No history/frame publication; last completed presentation retained where context permits |

Use tiny deterministic pixel fixtures for R05/R06 (for example increment red by `1/255` in RGBA8), with an explicit tolerance. A screenshot of a plausible animation is insufficient evidence for history semantics. Run at least some real GLES/Android rendering checks; mock calls alone cannot establish sampler/framebuffer behavior.

## Storage and recovery — S5

| ID | Scenario | Expected result |
| --- | --- | --- |
| P01 | Save/reopen own-provider multifile folder with image | Content, entry, and identity survive; no accidental DB-only save |
| P02 | SAF tree read/write versus read-only provider | Correct capability-aware actions and persisted grants |
| P03 | Single-document grant tries to include a sibling | Useful access action; no guessed parent access |
| P04 | Local edit conflicts with changed/deleted external file | Neither version discarded; reload/keep-edits/save-copy behavior is explicit; autosave suspended until conflict resolved |
| P05 | Provider fails after some files save | Successful/failed files distinguished; drafts retained and retry possible |
| P06 | Process recreation with unsaved edits and a large asset-containing project | Draft recovery; small Intent handles; no whole-project Binder payload |
| P07 | Asset/config changes during preparation; revoked permission; offline provider | One captured value per file; detected races retried/reported; immutable candidate after capture; no claim of provider-wide transactions |
| P08 | Open/edit own-provider file in Android Files and another editor; rename with an existing handle | Same visible files; correct notifications, flags, supported ID/grant lifecycle; clean reload or real conflict |
| P09 | Provider requests with traversal/malformed IDs, escaping links, or app-private paths | Access confined to exposed user-files root; DB/drafts/wallpaper never exposed |
| P10 | Open very large mixed folder; build requires a few files; own-provider document opened through different routes | Lazy listing and dependency-only reads; no whole-folder clone; no duplicate session for a known identical document |

## Editor behavior — S6

| ID | Scenario | Expected result |
| --- | --- | --- |
| U01 | New shader and immediate edit; Change location to own subfolder or external provider | One Name/Location sheet with working default; same native picker; valid template and no manifest/setup wizard |
| U02 | Switch entry/include/pass files and undo in each | Independent text/history; selected entry/preview unchanged |
| U03 | Add local/external/library texture, including name conflict and nested editing file | Local file reused; external/library copy; canonical annotation with correct relative path and unused name |
| U04 | Add shader input in current/previous mode and inspect output | Correct dependency syntax; inspection shares graph state |
| U05 | Compile error in another file; save invalid source | Correct file navigation; save and preview states are independent |
| U06 | Rename with references in active and unused files; external move then Refresh | Only recognized references updated; external partial failure recoverable |
| U07 | Delete referenced file, remove recent shortcut, read-only save | Clear distinct actions; no accidental external deletion/overwrite |
| U08 | Long paths, keyboard navigation/accessibility, localized status labels, auto/manual preview | Existing UX remains usable; state not conveyed solely by color |
| U09 | Open empty/mixed/read-only folder with no main/manifest; create/open a shader and Run | Folder always browsable; no automatic import/scan/wizard; selected shader can run; writes respect capabilities |
| U10 | Run this file, edit another file, reopen with/without manifest, refresh changed manifest | SPEC 1 entry precedence; preview title accurate; no implicit manifest write or target switch on editing |
| U11 | Open standalone document; then add sibling include; choose wrong/correct folder | Standalone works immediately; access requested only when needed; containment verified and drafts preserved |

## Portability and wallpaper — S7

| ID | Scenario | Expected result |
| --- | --- | --- |
| W01 | Share/import shader with local resources, Unicode names, compile errors, and explicitly added unused code | Selected closure and explicit extras preserved; no library dependence or compile gate |
| W02 | Unsafe ZIP path/link, duplicate entry, oversized payload, unsupported manifest | Unsafe archives rejected before publication; a structurally safe unsupported manifest remains unchanged and opens with a render-config error |
| W03 | Apply wallpaper, close app, revoke/remove external source, recreate process/context | Installed snapshot reopens independently; expected GPU reset |
| W04 | Current edits invalid while prior preview is valid; later successful edit | Invalid apply blocked; explicit successful update replaces installed snapshot atomically |
| W05 | Capture thumbnails/inspect pass with active feedback; switch legacy/native wallpaper | No extra simulation steps or identity collisions; existing runtime behavior preserved |
| W06 | Share/Save a copy from broad folder with unrelated private files; entry is a session override | Only closure/extras copied; relative paths intact; portable entry metadata only in copy; source unchanged |
| W07 | Missing/malformed dependency; failed destination write; invalid/unsupported manifest | Incompleteness explained, no false self-contained/success claim; source/drafts retained; no silent manifest downgrade |

## Legacy and conversion — S3/S8

| ID | Scenario | Expected result |
| --- | --- | --- |
| L01 | Existing bundled samples and ordinary DB load/edit/save/preview | Existing appearance and behavior preserved through shared backend |
| L02 | Old named textures, sampler comments, presets, cameras, cubemaps | Existing settings/providers preserved; no native name lookup added |
| L03 | Save as files for representable legacy shader using library textures | Independent project with explicit local paths; original remains usable |
| L04 | Convert incompatible min/mag/mipmap/cubemap/preset behavior or missing resource | Specific limitation; no silent downgrade or false successful conversion |
| L05 | Native file has a sampler named like a DB texture; explicit legacy import | Native remains an unbound-input error; only explicit legacy path uses old lookup |

## Release gate — S9

Every ID above needs an evidence location and status: PASS, FAIL, BLOCKED, or NOT RUN. Existing coverage can be reused when it actually establishes the behavior. Record backend/device/API and commit when needed. Mandatory Android lifecycle and GPU cases cannot be marked PASS from source review or desktop GLSL compilation.

Build and lint must pass. No outstanding failing mandatory case is compatible with declaring native v1 complete. Optional RGBA16F hardware may be unavailable, but unsupported-device diagnostics must be exercised. Lack of an available Android execution environment is a validation blocker, not permission to relabel integration cases as passed.
