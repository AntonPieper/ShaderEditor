# Editor language tooling

Status: accepted on 2026-11-11. This is the design for the editor's tokenizer, highlighting and completion, and for the lexical layer it shares with project mode. The work is track L of the plan.

The owner asked: "Should the syntax highlighting, lexer and parser (which is currently inline in the lexer somewhat) be rewritten and overhauled from first principles to be architected better, fix the bugs, integrate better and support future filetypes better?"

## Answer

Partly, and not where the question points.

- **Tokenizer: yes, replace it.** It is 2,040 lines for what about 400 can do, it knows the wrong GLSL dialect, and it cannot colour the lines project mode is built around (`#include "..."`, `#pragma se input "..."`). The replacement is small because most of the old code is a keyword table and a token type per keyword.
- **Parser: no.** There is no parser today, only about twelve lines of guessing inside the tokenizer. Nothing the app needs, now or in project mode, requires a GLSL parser. A shallow scan of top-level declarations covers every real need, and only when the first feature asks for it.
- **The bugs users reported are not in the tokenizer.** Lost colours (upstream issue 192), missing completions and the slow editor (issues 31, 125, 176) come from how colours are applied to the text and from the text widget. A rewrite of the tokenizer alone would fix none of them. They need five small fixes that can go upstream now, before any rewrite.
- **Integrate better: yes, by sharing one lexical layer with project mode.** Today the editor and the project scanner decide independently what a comment and a directive are, and they disagree on inputs I ran. The shared part is small: comments, directive lines, positions.
- **Future file types: one interface with two methods.** More than that is speculation; the only second file type that exists is a JSON file with two keys.

The rest of this document supports that, proposes the target design, and orders the work as small pull requests.

## 1. What exists and what is wrong with it

### The design in a paragraph

`highlighter/Lexer` is a hand-written GLSL tokenizer over a `String`. It returns mutable `Token` objects that carry a `TokenType` (about 230 constants, one per keyword and operator of desktop GLSL 4.60), a category (`NORMAL`, `PREPROC`, `TRIVIA`) and six position fields. Keywords and directive names live in two tries. `Highlight.from` maps the 230 types to 20 colour classes in a 250-line switch. The editor widget (`widget/ShaderEditor`) re-tokenizes the whole text on a single background thread after every edit. One second after the last keystroke (the "update delay" preference) it compares the new token list with the list it highlighted last time (`Lexer.diff`, longest common prefix and suffix of token type and length) and sets one `ForegroundColorSpan` per token in the changed range. Completions come from the same tries: the editor finds the token under the cursor and offers keywords that start with its text.

### "The parser inline in the lexer"

It is not a parser. It is three pieces of state in `Lexer.nextToken`:

1. An identifier after `.` becomes `FIELD_SELECTION`; an identifier after `struct` becomes `TYPE_NAME`.
2. When two identifiers follow each other, the first one is changed to `TYPE_NAME` **after it was produced**. To make that work, `nextToken()` returns each token one call late: a local variable named `previous` shadows the field of the same name, so `return previous` returns the token of the previous call. The constructor calls `nextToken()` once to prime this. I read that line as a bug at first, and "fixing" it would break highlighting. A type name separated from its variable by a comment is still mutated after it was handed out.
3. "Are we inside a preprocessor line" is carried from token to token and reset when the line count changed while skipping whitespace.

### Defects and fragilities

"Ran" means I executed the compiled classes of this checkout with the input shown, in a throwaway JVM program that is deleted. "Read" means I reasoned from the source.

| # | Defect | Input or scenario | Result | Evidence |
| --- | --- | --- | --- | --- |
| 1 | Inserted text stays uncoloured when it looks like its neighbours | `vec3(1.0, 1.0)` becomes `vec3(1.0, 1.0, 1.0)`; or a new line `y = 2.0;` under `x = 1.0;` | `Lexer.diff` returns a changed range that ends before it starts (start 5, end 3 for the first input): its common prefix and common suffix overlap, so the editor's loop over the changed tokens is empty. The new `1.0` stays white instead of green | Ran the diff. Effect on spans read |
| 2 | Colours lost after deleting and retyping (upstream 192) | `vec4`, delete `ec4`, type `ec4` again within the update delay | Token lists before and after are identical, so nothing is re-applied; the span had shrunk to `v` and does not grow back | Ran the diff. Span behaviour read |
| 3 | No completions when the cursor touches the next token | `vec3(no▮)`, `x = fl▮;` | The token under the cursor is `)` or `;`, prefix is empty, no completions. Works only with whitespace or end of text after the cursor | Ran |
| 4 | Wrong dialect | `true false attribute varying gl_FragColor texture2D textureCube samplerExternalOES` | None is a keyword or builtin; each gets whatever the identifier guess of defect 10 gives it. `HIGHLIGHT_BOOL_LITERAL` can never be produced. `samplerbuffer` (not GLSL) is a keyword, `samplerBuffer` is not; `uimage1DArray` is missing because `iimage1DArray` was inserted twice. Completion for `gl_Frag` offers `gl_FragCoord, gl_FragDepth` only, for `tex` no `texture2D`, for `res` only `restrict` | Ran |
| 5 | Paths in directives are not strings | `#pragma se input "a.png"`, `#include "a//b.glsl"` | `"` is an invalid token (red), `a` an identifier, `.png` a field selection; `//b.glsl"` is a comment | Ran |
| 6 | Directive detection depends on comments | `/* a` newline ` b */ #define X 1`; `#define A /* c */ b c`; `#/**/pragma se input` | `#` is invalid (red) and `define` a type name; after a comment inside a directive the rest of the line is ordinary code; `pragma` is not a directive | Ran |
| 7 | Line continuation changes meaning with a space | `#define A 1 \` newline `+ b c` against the same without the space before `\` | With the space, the continued line is ordinary code and `b` becomes a type name; without it, the line belongs to the directive and the token `1` covers the backslash and newline | Ran |
| 8 | A token can end outside the text | Text ending in an unpaired surrogate | Token range 2 to 4 in a text of length 3. `setSpan` rejects ranges beyond the text, so this would crash the UI thread. Commit `9bcaebc` already fixed one crash from a token past the end | Range ran. Crash read |
| 9 | Half of `Token` is dead and wrong | Emoji in code against emoji in a comment; CRLF; 40,001 lines | `start`/`end` count neither chars nor code points (they differ between the two emoji cases); `line` counts CRLF twice; `line` and `column` are `short` and read −25535 at 40,001. Nothing outside `Token` reads these four fields | Ran |
| 10 | The type guess is inconsistent | `Ray r = Ray(o, d); Ray[2] rs;` and `x = y` newline `z = 1;` | First `Ray` is a type (amber), the other two plain. `y` becomes a type whenever a statement lacks its semicolon, which is the normal state while typing | Ran |
| 11 | The background tokenizer cannot restart | Editor view detached, then any text change | `onDetachedFromWindow` shuts the executor down for good; the next `update` throws `RejectedExecutionException`. Whether the app ever re-attaches the view I did not establish. Highlighting also waits on the background task with a blocking `get()` on the UI thread | Ran `TokenListUpdater` on the JVM |
| 12 | A regex over the whole text runs on every keystroke, on the UI thread | Any edit; `convertShaderToySource` in `afterTextChanged` | 16.6 ms on a desktop JVM for a 5,290-line text, about seven times the cost of tokenizing it. Quadratic in line length: 65 ms for one 5,000-character line, 1.3 s for one of 20,000 | Ran the same pattern |
| 13 | The keyword table costs memory | Class load | 1,718 trie nodes, 1,440 of them with a 127-slot child array: at least 0.7 MB retained for about 350 words | Ran the count |

Two more facts belong here although they are not defects of this code:

- **There are no tests** for the tokenizer, the diff, or the completion logic. `app/src/test` has none for `highlighter` or `widget`.
- **Four parts of the app each understand GLSL text in their own way.** The highlighter's `Lexer`; the project scanner (`PreprocessorScan`, plus a regex tokenizer in `HostDirectives` and an argument splitter in `HostDirective`); the regexes of `LegacyShaderConverter`, `PassGraph` and the old `ShaderSourcePreparer`; and four regexes in `ShaderEditor` itself (where to insert a uniform, the last `#endif`, `void main(`, `void mainImage(`). On this checkout the first two disagree: for a `//` comment continued by a backslash onto a `#pragma se input` line, the highlighter sees a comment and the scanner a directive; for `#/**/pragma` and for a directive after a comment that closes on its line, it is the other way round (ran).

### What has been fixed repeatedly

`git log` for `highlighter/` shows three functional changes since the lexer arrived in November 2023 (keyword lookup for the suggestion bar in `ac24b7c`, completions in `4993782`, one comparison operator in `acd9d75`); the rest is reformatting. All repeated fixes are in the widget, at the hand-off between tokenizer and UI:

| Area | Commits |
| --- | --- |
| Null text | `5d4e195`, `dba635c`, `6146790`: "check getText() for null", three times |
| Token outside the text | `9bcaebc` |
| Threads | `402143b` (completions ran on the background thread), `be36de7` (executor never shut down), `a5f4059` (NPE after cancel) |
| Completion state | `3a72eb2`, `acd9d75` (stale results, wrong insertion offset, end of buffer) |

The tokenizer was left alone because nothing tests it, not because it is right. The hand-off was patched five times because mutable tokens, a revision counter and a blocking wait are hard to get right by inspection.

### Upstream issues

| Issue | Cause |
| --- | --- |
| 31, Unresponsive editor (2016) | The widget. The maintainer measured it in the thread: for 1,124 lines on a Nexus 4, clearing spans took 242 ms and setting about 1,330 spans 265 ms; all regexes together 5 ms. Still the design: one span per coloured token over the whole document, in an `EditText` laid out at full height inside a `ScrollView` |
| 45, Keywords in comments get highlighted (2016) | Lexing, in the regex highlighter of the time. The current lexer returns a comment as one token (ran), so this should be closed after a look on a device |
| 75, Syntax highlighting disappeared (2018) | Cannot be determined. The code it was reported against was replaced in 2023. The same symptom exists today through defects 1 and 2 |
| 125, Editor gets slower (2022) | Not the lexer: reported with highlighting disabled, before the lexer existed. Candidates today are defect 12, the span count, and `LineNumberEditText.onDraw`, which draws the number of every line of the document on every frame. Which one dominates cannot be determined without a device |
| 176, Slow text deleting (2024) | Most likely the widget: deleting a range that holds thousands of spans. Not determined without a device |
| 192, Highlighting synchronization (2024) | How spans are applied (defect 2). Not the lexer |

## 2. What the app needs from language tooling

"Exact positions" below means character offsets into the text. No consumer needs columns: compiler logs and project diagnostics carry a file and a line.

| Consumer | Tokens | Comments kept | Positions | Preprocessor structure | Declarations | Incremental | Real? |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Highlighting | yes | yes, they are coloured | offsets | which lines are directives; strings in them | only for user type names | span updates, not lexing | Real, exists |
| Completion (keywords, builtins; plan item E14 adds builtin uniforms) | the word at the cursor | to stay silent inside them | offsets | to offer directive names | no | no | Real, exists |
| Completion of the file's own names | yes | yes | offsets | macro names | top level | no | Wanted by nobody yet; cheap once the outline exists |
| Completion in host directives (`se` commands, option keys, file paths) | yes | yes | offsets | yes | no | no | Foreseen in DECISIONS ("easy to extend completion after `se `"); needs the file list of plan item M10 |
| Error markers | no | no | line starts | no | no | no | Real. Plan item M6 adds the file |
| Include and directive processing (`project/`) | no | where they start and end | physical lines | directive name, argument, conditional depth | one `uniform` after an input directive | no | Real, exists, being fixed |
| Converter for old shaders | no | yes: the `///` comments carry the settings | lines | conditional blocks, macro bodies | top-level sampler uniforms | no | Real, exists, being fixed |
| Add texture, Add uniform, Add include (M12) | no | yes | line to insert at | conditional depth | declared names, to suggest an unused one | no | Real, planned. Done with two regexes today |
| Warnings for uniforms without a provider (E13) | no | no | no | no | no | no | Needs GL reflection, not text |
| A file list with several file types | per type | per type | offsets | no | no | no | `.glsl` is real. JSON for a two-key manifest is marginal. Scripts are a sentence in the spec about "a later version" |
| Scope-aware completion, signature help, squiggles before compiling, formatting, rename | full syntax tree | yes | offsets | macro expansion | all scopes | yes | Speculative. Nobody asked, and the driver's compiler reports the authoritative errors a second after typing stops |

**On incremental updates.** Tokenizing the whole text is cheap; applying spans is not.

| Work, 5,290 lines, 122,720 chars (bundled samples repeated) | Desktop JVM, median |
| --- | --- |
| Full tokenization (28,711 tokens) | 2.5 ms |
| `Lexer.diff` of two full lists | 0.1 ms |
| `PreprocessorScan.scan` of the same text | 0.6 ms |
| ShaderToy regex, run per keystroke today | 16.6 ms |

I did not measure on a phone. Even at twenty times slower, a full tokenization is 50 ms on a background thread for a document far larger than a typical shader; a 300-line shader is under 5 ms. Incremental lexing would add state and bugs for no visible gain. The same document needs 9,441 coloured spans when it is loaded, and by the maintainer's 2016 figures a span costs about 0.2 ms to set on an old phone. So the editor must be incremental in **which spans it touches**, and for large files in how many it holds at all.

## 3. Options

| Option | Build | Review upstream | Keep | Makes possible |
| --- | --- | --- | --- | --- |
| A. Leave it, fix bugs in place | Defects 1 to 3, 8, 11, 12 are a few lines each. Defects 4 to 7 mean editing the 1,048-line lexer and adding token types | Easy for the first group; the second touches code no test covers | 2,040 lines, two scanners, the delayed-return trick | Fixes what users reported. Does not give project mode coloured directive lines or one definition of a comment |
| **B. New tokenizer, shared; shallow outline later** | About 400 lines in two PRs, replacing 2,000. Outline about 200 lines when needed | Small files, pure functions, tests in the same PR | One tokenizer, one word list of two arrays | Everything in section 2 that is real |
| C. Hand-written recursive-descent parser | 2,000 to 3,000 lines for GLSL ES 3.00 with error recovery; eight or more PRs | Hard: grammar code is long and uniform | A grammar to track for every language revision | The speculative row of section 2, unreliably: GLSL is preprocessed, and macros and `#if` halves break any parser that runs on unpreprocessed text |
| D. Generated parser (ANTLR or similar) | A grammar file, a build plugin, a runtime library, generated classes in the APK | The maintainer reviews a grammar and trusts a generator | A new dependency and build step in an app that has six libraries | As C, and not tolerant of half-typed code, which is what an editor sees most of the time |
| E. tree-sitter | A native library per ABI, JNI bindings, an NDK build. The app builds no native code today. `tree-sitter-glsl` extends the C grammar; one package page reports about 150,000 lines of generated C. The Android binding I found says in its README that it is no longer maintained | A native toolchain and a foreign grammar | Native crashes, ABI splits, F-Droid build recipe | Incremental, error-tolerant syntax trees. Its strength, incremental parsing, solves a problem the measurements say this app does not have |
| F. TextMate grammar | A regex engine with Oniguruma semantics (a Java port exists) and a grammar interpreter, or a whole editor widget that bundles them | A JSON grammar of regexes, which is what the app left in 2023 | A second regex dialect | Highlighting only. Nothing for completion, includes or the converter. Wins when many languages are needed, which is not the case |

How much of GLSL has to be understood for which benefit:

- **A tokenizer is enough** for colours, comment awareness, finding directive lines and their arguments, the completion prefix and context, "does this file mention `backbuffer`", and "does it define `mainImage`".
- **A scan of top-level declarations** (track brace depth; at depth zero recognise `struct Name`, `type name(`, `type name;` and `#define NAME`) is enough for completing the file's own names, colouring user types consistently, choosing an unused name and an insertion line for Add texture, attaching an input directive to its `uniform`, and the converter's sampler declarations. It needs no expressions and no statements.
- **Only a full parser** gives scope-aware completion, checking member access and swizzles, signature help, errors before compiling, formatting and rename. None is requested.

One option outside this list deserves a sentence because it is the honest answer to issues 31 and 125: replacing `EditText` with a code-editor widget that draws only visible lines. That is a decision about the widget, not about language tooling; it is decision 4 below.

## 4. Recommendation

Option B, with the user-visible fixes of option A done first in the existing code.

### The types

All in one new package without Android imports (I call it `syntax` here), so `project/` can use it and it runs in JVM tests.

| Type | Responsibility |
| --- | --- |
| `Tokens` | The immutable result of tokenizing one text: for each token its start offset, end offset, style and an "inside a directive" flag, in parallel `int` arrays; the start offset of every line; `indexAt(offset)` by binary search. No token objects, nothing mutable after it is returned |
| `GlslLexer` | `static Tokens lex(CharSequence)`. The only code in the app that knows what a comment, a line continuation, a number, a directive line and a quoted path are. Total: it accepts any input, never throws, and every token lies inside the text |
| `GlslWords` | The word list: a few string arrays (control flow, types, qualifiers, builtins) and `matching(prefix)`. Replaces the two tries, `TokenType`, and the switch in `Highlight` |
| `Style` | About twenty colour classes, as `Highlight` has today but without resource ids. The widget maps a style to a colour |
| `Syntax` | The seam for file types (below) |
| `Outline` (later) | `static Outline of(Tokens, CharSequence)`: top-level declarations with name, kind and range |

Design points that answer the defects:

- **Positions have one owner.** A token's range is in UTF-16 offsets of exactly the text that was lexed, which is what `Editable` and `String` use. Lines come from the line table; `\n`, `\r\n` and `\r` each end one line, the same rule `SourceText` applies. Columns are computed on demand. There is no code-point index and no `short`.
- **Comment state has one owner.** A comment is a token, also when it is unterminated. Whoever needs "the text without comments" blanks those ranges; nobody scans for `/*` again.
- **Directive lines are a mode of the lexer**, entered at a `#` that is first on its line apart from whitespace and comments, and left at the end of the logical line. In that mode a `"` starts a string token that runs to the closing quote. That is what makes `#include "lib/noise.glsl"` and `#pragma se input "textures/paper.png"` one coloured string, and it is the rule `PreprocessorScan` already uses to protect `//` inside a path.
- **The type guess becomes a visible step.** The three rules of today (after `.`, after `struct`, identifier followed by identifier) are applied while the arrays are still private to the lexer. No delayed return, no mutation after publishing. When the outline exists, declared struct names are coloured everywhere they appear.
- **Punctuation needs no types.** All operators have one colour, and today that colour equals the text colour. One style, one token per character; the 45 operator types and the 200-line switch over them go. A consumer that needs `{` reads the character.
- **A token never spans a line continuation**, except a comment. `flo\` newline `at` is two identifiers. The old lexer joins them, as GLSL ES 3.00 does; I recommend dropping that because it complicates every character read and nobody writes it. A continuation between tokens keeps the directive open, which is the case that matters (multi-line `#define`).

A sketch of the seam, to show its size:

```java
interface Syntax {
	Tokens lex(CharSequence text);                                   // any thread
	List<String> complete(Tokens tokens, CharSequence text, int cursor);
}
```

### How the editor updates

1. On every edit the widget records the **dirty range**: the smallest range of the current text that covers everything typed or deleted since colours were last applied. This is arithmetic in `onTextChanged`.
2. It tokenizes the whole text on the background thread and posts the immutable `Tokens` with its revision. A result for an older revision is dropped. Nothing on the UI thread waits for the background thread.
3. When colours are applied, the range to restyle is the union of the dirty range and the range in which old and new tokens differ (common prefix and suffix, without the overlap of defect 1), widened to whole tokens. Spans in that range are cleared and set again for coloured tokens only.

The dirty range repairs what editing did to spans (issue 192). The token comparison catches effects at a distance, such as typing `/*`. Step 3 is a pure function of two `Tokens` and a range, so it is tested on the JVM without Android.

Whether colours are applied after the pause, as today, or as soon as tokens arrive, is decision 3. Whether spans exist only for the visible lines is decision 4.

### How a second file type plugs in

The editor holds one `Syntax`, chosen where a file is opened from the language of the file. The language comes from the file's name through the one table of SPEC 2.1, which today gives GLSL for every text file. That is one lookup, not a registry. A JSON syntax for the manifest would be about fifty lines if someone wants it; I would not write it now. A script language gets a lexer and a word list of its own and reuses `Tokens`, `Style` and all of the editor. Nothing else should be built for file types that do not exist.

**As built.** The editor came to colour line by line (EDITOR.md), so the seam is the line lexer and what hangs off it, not the sketch above. `syntax/Syntax` is an interface of four methods, and the editor knows a language only through it:

| Method | What the editor does with it |
| --- | --- |
| `lexLine(line, breakFollows, state, out)` | `LineStyles` colours the visible lines with it and continues in the background; the state a line passes on is one byte, so a language has 256 states |
| `outline(max, maxChars)` | `Analysis` reads the whole text with the builder it returns, off the main thread; `null` means the language collects nothing and the text is not tokenized for it |
| `complete(line, column, lineStart, declared, current, extraWords, offer)` | the strip above the keyboard; the words reach the host through `offer`, so there is no result type per language |
| `indentation()` | `AutoIndent`: the line comment a new line continues, the characters that add a level at the end of a line, and those that open and close one anywhere |

`LineStyles` holds the file's one syntax; `Analysis`, completion and the indentation of `EditCommands` take it from there. The tokens of a line (`LineTokens`, `LexedLine`), the styles (`Style`) and the declarations (`Outline`) are shared by every language. `GlslSyntax` is GLSL's implementation; it hands each call to the classes that existed before (`GlslLexer`, `Outline.builder`, `GlslCompletion`). The characters of a word, for double-tap selection and the completion prefix, are the same for every language today (letters, digits, `_`) and are not part of the seam. A test keeps the generic classes free of GLSL names. A database shader is always GLSL; a file of a folder gets the syntax of its language.

The seam costs no allocation per line: a line was tokenized into the same reused `LineTokens` before, through one static call, and now through one interface call. Measured on a desktop JVM before and after, for 50,855 lines (bundled samples repeated): colouring all of them in the background 18 ms both times, colouring 5,000 visible lines 2 ms, one keypress in the middle with 50 lines shown 0.08 ms, the analysis 25 ms.

### How the project-mode scanner relates to it: one shared layer

With the fix that is in review now (fork PR 39 and the `prepare-fix-*` PRs stacked on it), the build decides host directives from one scan of each file, in `PreprocessorScan`, and carries a record per line through assembly instead of scanning assembled text again. That is the right shape, and it leaves exactly one place in `project/` that decides what a comment and a directive are: about 70 lines in `PreprocessorScan` (`stripComments` and `parse`).

I recommend that those 70 lines read `GlslLexer`'s tokens instead of scanning characters themselves. `PreprocessorScan` keeps its API (lines, directives, conditional depth) and everything above it stays as it is: `HostDirective` keeps its argument grammar, `SourceAssembly` its line records, the strict rules of the spec (one physical line per host directive, no block comments on it) stay in `project/`, where they are policy and not lexing.

Why one layer and not two that stay separate:

- The defects the review found in `project/` had one cause: text was understood more than once. Two tokenizers in one app are the same cause one level up, and they already disagree on three inputs.
- The editor is about to show project files. What it colours as a directive, a path or a comment should be what the build treats as one. With one lexer that holds by construction.
- The cost is one refactoring PR of about 60 lines each way, guarded by the 200-odd existing project tests.

Why the sharing stops there: assembly works in lines and must reject what it does not support; the editor works in offsets and must tolerate anything. Forcing both onto one data model above the lexer would couple them for no gain. Further consumers move to tokens only when that deletes more code than it adds; candidates are the regex tokenizer in `HostDirectives.declaration`, the `backbuffer` regex in `PassGraph`, and the converter's sampler regex, where "is this declaration inside a comment or a macro body" (review findings 2 and 24) becomes a lookup.

This is the last step of the core sequence and the one with the least user-visible value. It can be declined without affecting the others (decision 2).

### Builtin uniforms (plan item E14)

`UniformTable.BUILTINS` is a list of 39 `UniformDef` entries with name, GLSL type and description; the Add Uniform list already reads it. For completion, the widget passes the table's names to the completion step as extra words. That is the planned small change and it needs nothing from the rewrite: it works against `Lexer.completeKeyword` today and against `GlslWords.matching` afterwards. Two cautions:

- Fix defect 3 first. A builtin uniform is mostly typed inside an expression (`gl_FragCoord.xy / res▮;`), which is exactly where completion is silent today.
- Do not colour builtin uniform names by name. `time`, `offset`, `light`, `frame`, `linear`, `second` and `date` are ordinary variable names in shaders that do not declare the uniform. Colour them only when the file declares that `uniform`, which is a job for the outline.

## 5. Delivery

I call this track L, next to E, M and X of the existing plan. Every PR follows that plan's rules: one idea, at most about 350 added production lines, refactoring apart from behaviour change, ★ marks a PR that changes behaviour. Line counts are estimates.

**How behaviour is pinned first.** L1 adds tests and changes no production code. A helper turns a source text into a string of the same length with one letter per character for its colour class, so a test reads:

```java
assertStyles(
		"float x = 1.0; // hi",
		"ppppp.....nnn..ccccc");
```

About forty such cases cover ordinary code and every input of section 1, with the wrong ones marked as wrong. Two properties run over every bundled sample: tokens are ordered, disjoint and inside the text; and, while both lexers exist, old and new produce the same string. `Lexer.diff` and the completion lookup get direct tests. From then on the behaviour change of any PR in this track is the diff of those expectation strings, which a reviewer can read. This is text for a pure function, not golden-image infrastructure.

| PR | Change | Users see | Tested how | When |
| --- | --- | --- | --- | --- |
| L1 | Tests that pin tokenizer, diff and completion lookup as they are | Nothing | They are the tests | Now |
| L2 ★ | The diff's prefix and suffix may not overlap (defect 1) | Inserted text that resembles its neighbours is coloured | JVM: the two inputs of defect 1 | Now |
| L3 ★ | Restyle the edited range, from a dirty range kept by the widget (defect 2) | Issue 192 is gone | JVM for the range arithmetic; emulator for both reproductions in the issue | Now |
| L4 ★ | The completion prefix is the word that ends at the cursor (defect 3); none inside comments | Completions appear inside parentheses and before `;` | JVM | Now, before E14 |
| L5 | Skip the ShaderToy regex unless the text contains `mainImage` (defect 12) | Nothing but speed | JVM: same conversions as before | Now |
| L6 | `syntax` package: `Tokens`, `Style`, `GlslWords` with today's word list, `GlslLexer`. Unused. Two PRs if it exceeds the limit: the word list, then the lexer | Nothing | L1's cases run against both lexers; each intended difference is listed in the test; a fixed-seed random-input test for "never throws, tokens inside the text" | Any time after L1 |
| L7 ★ | The editor uses `GlslLexer`. Removes `Lexer`, `Token`, `TokenType`, `TrieNode`, `CharIterator` and the switch in `Highlight`: about 2,000 lines | Paths in directives are one coloured string; directives after comments and continued macros are coloured correctly (defects 5 to 7); no crash on a trailing surrogate (8) | The expectation diff of L1; emulator, bundled samples by eye | After L6. Before M10 |
| L8 ★ | The word list is GLSL ES 1.00 and 3.00 for fragment shaders (defect 4) | `gl_FragColor`, `texture2D`, `true`, `attribute`, `varying` and the rest are coloured and completed; names that do not exist on this platform are no longer offered | Expectation diff | After L7 |
| L9 | Test: `PreprocessorScan` and `GlslLexer` agree on comments and directive lines for the bundled examples and the tricky inputs | Nothing | It is the test | After L6 and after the scanner fix has merged |
| L10 | `PreprocessorScan` reads comments and directives from `GlslLexer` | Nothing | The existing project tests, unchanged | After L9 |
| L11 | `Outline`, and declared type names coloured everywhere | User types are coloured consistently (defect 10) | JVM | After L8 |
| L12 ★ | Completion offers the file's own functions, globals, macros and types | As stated | JVM | After L11 |
| L13 | Measure typing, scrolling and deleting with a 5,000-line shader on the emulator and a real device, before and after L5 | Nothing | A written measurement | After L5 |
| L14 | Superseded by EDITOR.md: colouring only visible lines is a property of the new editor view, not built on the old widget | — | — | — |
| L15 | Superseded by EDITOR.md: colours follow typing in the new editor view | — | — | — |

Notes:

- **What can go now.** L1 to L5 touch only `highlighter/` and `widget/ShaderEditor.java`. Both are byte-identical to upstream `master` on this branch, and no open PR touches them. Each fixes something a user can see in the released app.
- **What must wait.** L9 and L10 wait for the scanner fix (fork PR 39 and the `prepare-fix-*` PRs stacked on it), because they read and then change `PreprocessorScan`. Nothing else in this track depends on `project/`.
- **E14** goes after L4. If it lands before L7, L7 carries it over; the seam is "a list of extra words".
- **M10** (file list, per-file text) should find L7 merged, so that choosing a `Syntax` per file lands on the new seam and is part of M10, about 30 lines.
- **Completion inside host directives** (commands, option keys, file paths) belongs to M12, where the file list and Add texture arrive. It needs the directive tokens of L7 and the option names from `HostDirective`.
- **Removed along the way.** `ShaderEditor`'s regexes for `main` and `mainImage` and for the uniform insertion point become outline lookups in L11 or M12. `ShaderSourcePreparer` and its regexes are already scheduled for deletion in E17.
- Every ★ PR is checked on the emulator by its author, as the standing rules require.

Net effect of L1 to L10: about 700 production lines added, about 2,100 removed.

## 6. Decisions taken

| Question | Decision |
| --- | --- |
| Scope | Fix the reported bugs in the existing code first, then replace the tokenizer. No parser, no native or generated parser |
| One lexical layer or two | One: `PreprocessorScan` reads the new tokenizer's output (L9, L10) |
| Where the early fixes go | L1 to L5 are proposed directly to upstream `master` as ordinary bug fixes, and land on the integration branch as well |
| Large files | Measure on a device after L5; then colour only the visible lines and draw only visible line numbers |
| When colours update | The update delay exists because highlighting is slow. Once that cause is fixed and measured, colours update as tokens arrive and the delay no longer applies to highlighting. Checked on a real device with several keyboards before it ships |
| Outline | Built right after the new tokenizer (L11, L12 follow L8), not deferred to M12 |

## 7. What was checked when this was written

Checked in this session:

- Read in full: every file in `highlighter/`; `widget/ShaderEditor.java`, `LineNumberEditText.java`, `LineNumbers.java`; `adapter/CompletionsAdapter.java`; `fragment/EditorFragment.java`; `project/PreprocessorScan`, `SourceText`, `SourceMap`, `SourceLocation`, `SourceAssembly`, `HostDirectives` as they are on this checkout; `opengl/UniformTable.java`; the review `project-chain.md`; the plan `IMPLEMENTATION.md`; SPEC 1 to 3, 8, 9; UX 3, 4, 6; ENGINE 2.2 and 4; EXAMPLES 3. Searched, not read in full: `HostDirective`, `LegacyShaderConverter`, `ShaderSourcePreparer`, `PassGraph` (their regexes and scanner use).
- Ran, against the classes compiled from this checkout: every row of the defect table marked "ran"; the scanner disagreement; the timings; the trie count; `TokenListUpdater` after shutdown. The programs lived outside the repository and are deleted.
- Upstream issues 24, 31, 45, 54, 75, 82, 125, 176, 192 and 237, with comments, and the list of open issues.
- `git log` for `highlighter/` and `ShaderEditor.java`, and the diffs of the fix commits named above. `git diff upstream/master..HEAD` for the editor files is empty.
- The description and file list of fork PR 39, to know the shape of the scanner fix, and the file lists of all open fork PRs: none touches the editor or the highlighter.
- The debug APK: 12.4 MB; the only native libraries in it come from CameraX.

Not checked:

- **Nothing ran on a phone or emulator.** Every statement about spans, crashes in `setSpan`, keyboards and drawing cost is reasoned from the code and from the maintainer's 2016 measurement. Timings are from a desktop JVM; the phone estimates are a stated multiplier, not a measurement.
- Whether the editor view is ever detached and re-attached (defect 11).
- The GLSL ES specifications themselves. That 3.00 processes line continuation before tokens and comments, and that 1.00 under the ES 2.0 API need not support it, comes from a web search (an ANGLE commit message), not from the spec text.
- tree-sitter and TextMate: I built neither. The statements about `tree-sitter-glsl` and the Android binding come from one web search ([tree-sitter-glsl](https://www.github.com/tree-sitter-grammars/tree-sitter-glsl), [android-tree-sitter](https://awesome.ecosyste.ms/projects/github.com%2FAndroidIDEOfficial%2Fandroid-tree-sitter), [ANGLE line continuation](https://android.googlesource.com/platform/external/angle/+/26e355b83%5E%21/)). Library sizes for ANTLR and for a TextMate interpreter, and the existence of a Java port of the Oniguruma regex engine, are from memory; no sizes are stated for that reason.
- The scanner fix beyond PR 39's description. L9 and L10 assume `PreprocessorScan` remains the one per-file scan; if the merged fix differs, L10 is re-planned against it.
- The line estimates for the new code and for options C and D. I wrote no implementation.
- The release APK size, and the old lexer's behaviour for inputs other than those listed.
