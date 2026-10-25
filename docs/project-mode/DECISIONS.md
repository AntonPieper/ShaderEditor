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

One fixed adapter preserves existing DB behavior and feeds the same renderer. New native files never fall back to old global texture names/comments. Save as files is explicit and can report an unrepresentable setting; preserving old shaders does not require inventing equivalent native syntax for every historical feature.

## Deliberate limits and remaining implementation choices

Literal include assembly and adjacent annotations require a lexer and source maps, not a complete GLSL preprocessor. Reject host metadata in conditional blocks/includes and cyclic includes even with guards. These constraints make dependency collection deterministic for rendering and sharing. Explain them at the relevant error and in help.

The graph follows both current and previous inputs for reachability, but only current inputs constrain execution order. Freeze previous outputs for the entire frame. This avoids order-dependent feedback and gives previous-only producers a clear meaning.

Sampling is an input property. GLES2 does not have independent sampler objects, so simultaneous different states for one texture require correct variants/copies. This cost is real; hiding it behind repeated binds would violate the authoring model. Keep the mechanism small and budget its allocations.

The implementation sessions choose concrete class names, reasonable admission limits, and the smallest correct backend strategy. They must preserve observable semantics and record actual test evidence. Do not implement deferred features to make the architecture look complete.
