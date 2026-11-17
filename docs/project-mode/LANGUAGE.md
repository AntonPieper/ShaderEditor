# Editor language tooling

Status: accepted on 2026-11-11; one answer was reversed on 2026-11-15: the editor gets a parser, see section 8, which wins over what the sections before it say about parsing and about the outline. This is the design for the editor's tokenizer, highlighting and completion, and for the lexical layer it shares with project mode. The work is track L of the plan.

The owner asked: "Should the syntax highlighting, lexer and parser (which is currently inline in the lexer somewhat) be rewritten and overhauled from first principles to be architected better, fix the bugs, integrate better and support future filetypes better?"

## Answer

Partly, and not where the question points.

- **Tokenizer: yes, replace it.** It is 2,040 lines for what about 400 can do, it knows the wrong GLSL dialect, and it cannot colour the lines project mode is built around (`#include "..."`, `#pragma se input "..."`). The replacement is small because most of the old code is a keyword table and a token type per keyword.
- **Parser: no.** (Reversed on 2026-11-15, section 8: the features this bullet calls unrequested were then asked for.) There is no parser today, only about twelve lines of guessing inside the tokenizer. Nothing the app needs, now or in project mode, requires a GLSL parser. A shallow scan of top-level declarations covers every real need, and only when the first feature asks for it.
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

**As built.** The editor came to colour line by line (EDITOR.md), so the seam is the line lexer and what hangs off it, not the sketch above. `syntax/Syntax` is an interface of four methods, and the editor knows a language only through it (the second row as section 8 changed it):

| Method | What the editor does with it |
| --- | --- |
| `lexLine(line, breakFollows, state, out)` | `LineStyles` colours the visible lines with it and continues in the background; the state a line passes on is one byte, so a language has 256 states |
| `knowledge(maxSymbols, maxChars)` | `Analysis` reads the whole text with the builder it returns, off the main thread, and keeps the `Knowledge` it builds (section 8); `null` means the language knows nothing of a text, which is then not tokenized for it |
| `complete(line, column, lineStart, known, current, extraWords, offer)` | the strip above the keyboard; the words reach the host through `offer`, so there is no result type per language |
| `indentation()` | `AutoIndent`: the line comment a new line continues, the characters that add a level at the end of a line, and those that open and close one anywhere |

`LineStyles` holds the file's one syntax; `Analysis`, completion and the indentation of `EditCommands` take it from there. The tokens of a line (`LineTokens`, `LexedLine`), the styles (`Style`) and what a language knows of a text (`Knowledge`, section 8) are shared by every language. `GlslSyntax` is GLSL's implementation; it hands each call to GLSL's own classes (`GlslLexer`, `GlslKnowledge.builder`, `GlslCompletion`). The characters of a word, for double-tap selection and the completion prefix, are the same for every language today (letters, digits, `_`) and are not part of the seam. A test keeps the generic classes free of GLSL names. A database shader is always GLSL; a file of a folder gets the syntax of its language.

**JSON, the second language.** `JsonSyntax` is 150 lines with its checks of numbers and escapes: strings with their escapes, numbers by JSON's grammar, `true`, `false` and `null`, punctuation, and the invalid style for what cannot be JSON, an unterminated string or a bad escape among it. A JSON string holds no line break, so every line starts in the same state and nothing is carried. It completes nothing, collects nothing, and indents after `{` and `[` until they close. Markdown and the shading languages v1 does not run get `PlainSyntax`: no colours, no completions, the indentation of the line before. All three are rows of the one table (SPEC 2.1), the same rows that tell the build what may run; there is no second table from names to languages.

**The manifest.** `shader-project.json` in a folder's root is JSON with completions: in a string where a key stands, the two keys SPEC 1 knows; in the string after `"entry":`, the files in the root that may be shaders by their names (text by the name hint, a language that runs). `OpenProject.syntax(path)` decides this, because only the project knows which file is the root's manifest; it lists the root once, off the main thread, and the names arrive in the syntax when listed. Any other `.json`, a `shader-project.json` deeper in the tree or opened alone, is plain JSON. The manifest's errors are the build's: the parser reports an unknown key, a wrong version or a bad entry with its line, the build carries it like a compile error, and the editor marks the line and shows the message under it when the manifest is the open file, after the same delay. When nothing runs, the build still reads the manifest and reports what is wrong with it, since an invalid manifest is then often the reason. The manifest's language does not run, so Run this file is not offered on it.

**What a script language would add.** A lexer whose state carries more than GLSL's comment and directive bits: a template string that goes on over lines (and the expressions inside it), and whether a `/` after the previous token starts a regular expression or divides, which needs the kind of the last token as GLSL's type guess already does. A word list for its keywords and builtins, and an outline builder if its declarations should complete. Both fit the 256 states of a line and the four methods. What the seam does not give, and no implementation can add: types, scopes and go-to-definition. The editor asks a language for the words that may follow a prefix, never what a name refers to.

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

## 8. Code knowledge: a parser after all (2026-11-15)

The owner asked for what section 2 called speculative: documentation for built-ins and for one's own functions, help with a call's arguments, go to definition, usages and rename, struct fields after a `.`, an outline. A study with a measured prototype (branch `spike/code-knowledge` on the fork, not merged) answered what the editor can know about code, how fast, and through which seam. Decided on its numbers: **the editor gets a hand-written, error-tolerant GLSL parser, behind one seam, in two halves.** This section wins over the "Parser: no" of the Answer, over option C's verdict in section 3 and over the "Scope" row of section 6.

### 8.1 Why the answer changed

- **The features are requested now.** A tokenizer and a scan of top-level names cannot choose between the forms of `mix` by its arguments, cannot say which declaration a name means inside a function, and so cannot rename.
- **It is cheap.** Measured on a desktop JVM (a phone is about ten times slower), a full parse of a whole file with the type of every expression: 0.7 ms at 1,000 lines, 3.8 ms at 5,000, 38 ms at 50,000. The analysis it replaces, which lists top-level names, takes 0.4, 2 and 21 to 23 ms: two thirds of either is making the tokens. A keypress costs the main thread 3.5 to 6 µs at any size and stays there, because parsing runs where the analysis runs: off the main thread, after typing pauses.
- **Section 3 feared macros and `#if` halves.** Measured: all 56 bundled shaders and examples parse without a problem and 99.8 % of their expressions get a type; of a file written in the roughest ShaderToy style, 297 of 300. What stays unknown is a macro that expands to code that is no expression, and what is unknown shows as nothing, never as an error.
- **It is more tolerant than the scan it replaces.** The outline counted braces and waited: below a missing `}` it found 18 of 34 functions in one test and 75 of 151 in another. The parser's rules (8.3) keep all of them. 2,127 half-typed and 20,000 randomly damaged texts: no failure.
- **No dependency.** Hand-written, about 2,500 lines for both halves. A generated parser's runtime alone (ANTLR 4.13.2: 326 KB) is three times the size of all of it, and is not made for half-typed code.

What stays as decided: no incremental parsing (a whole file of 5,000 lines is milliseconds, one function is microseconds), no macro expansion (it would move every position), no error marks of the editor's own (its knowledge is more generous than the compiler's and blind behind some macros: it helps, it does not judge).

### 8.2 The seam

`Syntax` keeps four methods. `outline(…)` became `knowledge(maxSymbols, maxChars)`: it returns a builder that `Analysis` feeds the text line by line, as before, and that builds a `syntax/Knowledge`: what the language knows about that version of the text. A knowledge never changes; the editor holds the one of the last analysis and knows for which revision it was made. `complete(…)` takes it where it took the outline.

| `Knowledge` | Answers | Used for |
| --- | --- | --- |
| `symbols()` | The declarations of the text in its order, each a `Symbol` | The outline, Go to name, the names completion offers, the struct names that are coloured |
| `at(offset, word)` | What `word`, which stands at `offset`, refers to: a function with every form it is declared in, else one symbol, or nothing | Look up, go to definition, the call in the strip |
| `links()` | The files the text names: an include (its text is part of this one) or another file | Files knowing each other (8.5), following a path |
| `folds()` | The regions that fold, as pairs of lines | Folding and the partner of a bracket, when they are built |

For the strip `Syntax` has a fifth method, `call(lines, line, column)`: the call the caret is an argument of, as the name called and the number of the argument, from the tokens before the caret alone, on the main thread, per keystroke. GLSL scans back to the open parenthesis that follows a name, over at most 20 lines and 400 tokens, so its work does not grow with the file (0.2 µs on a desktop JVM); a language without calls answers null. What is called is then asked of the knowledge and the scope, and for a built-in of the docs.

Beside it stands `Knowledge.Scope`: what is declared outside of a text and known in it. The editor is given one with the document it shows and asks it at the moment it needs to: `names()` for completion, `find(word)` for what a word refers to in another file. Plain text and a shader of the database have the empty one.

A `Symbol` is a name, a kind (function, variable, uniform, constant, macro, type, parameter, field, include, file), its **detail** (how the language writes it: `vec2 flow(vec2 p)`, `uniform vec2 resolution`, the line of a macro), its **documentation** (the comment that belongs to it, 8.4), the place of its name and of the whole declaration, its **parts** (the parameters of a function or macro, the fields of a struct) and, once it is handed to another file, the file it comes from.

Three points of its shape:

- **`at` takes the word and the offset.** The editor always has the word, from the line's tokens. The offset says from where the word is seen; until names have scopes (half 2) it is not needed, and it lets a knowledge of an older text still answer while the user types.
- **Usages are not part of it yet.** `occurrences(symbol)` needs scopes to be right (a local hides a global), so it arrives with half 2 and not as a method that is wrong until then.
- **Nothing of it is GLSL.** JSON and plain text build no knowledge and the editor holds `Knowledge.NONE`; a language that only knows its brackets would answer `folds()` and nothing else.

### 8.3 The parser, in two halves

**Half 1, declarations** (this change set). It reads top-level declarations: functions with every overload and their parameters, global variables, uniforms and constants, structs with their fields, `#define` names with their text and parameters (not expanded), the comment that belongs to each, and exact ranges. Function bodies are not read, only their braces are matched. It replaces the outline's scan; there is one model of a file's structure.

**Half 2, statements and expressions** (later): scopes, the type of every expression, overloads chosen by argument types, usages, rename, members after a `.`.

Both read the text one top-level declaration at a time from the line lexer's tokens, so memory does not grow with the file: what is kept is the declarations, bounded as the outline was (10,000 symbols, 524,288 characters of their names, texts and comments).

Half 1 as built (`GlslDeclarations`, `GlslKnowledge`), against the scan it replaced, on a desktop JVM with the bundled shaders repeated: 0.46 ms for 1,000 lines (the scan: 0.40), 2.5 ms for 5,000 (2.0), 27 ms for 50,000 (22), of which the line lexer alone is 15. It keeps 39 KB, 192 KB and 1.9 MB (the scan: 5, 84 and 828 KB), because a symbol now carries how it is written, its comment and its parts.

The rules that make it tolerant, each a thing GLSL cannot mean:

1. **A function head inside a body ends that body.** GLSL has no nested functions, so `float f(vec2 p) {` inside a body means the body lost its `}`. Everything below a missing brace stays known.
2. **A declaration that lost its `;` ends where a new line starts another**: at a declaration's keyword, or at a type or name that follows a name, a number or a closing bracket, which no declaration continues after.
3. **A name needs a type before it to be declared.** `x = y` on one line and `z = 1;` on the next declare nothing.
4. **After `#else` the nesting is what it was at `#if`**, so a function head written once per branch with one body counts its brace once. Code between `#if 0` and its `#else` or `#endif` is not read; every other branch is, so the outline has the names of all of them.

#### How the reader is built

Three classes, each with one job:

| Class | What it is | Lives for |
| --- | --- | --- |
| `GlslStatement` | The tokens of one statement: what each is, its text once with single spaces, where it stood. Answers where the words it starts with, a pair of brackets or a declarator end, and which of its words is the name it declares | One statement |
| `GlslSymbols` | A function from a statement's tokens to what they declare: function with parameters, variables, struct with fields, block. Reads nothing else and keeps nothing | A call |
| `GlslDeclarations` | The reader: one pass over the lines, fills the statement, asks `GlslSymbols` when it ends, keeps the symbols within the budget | One text |

**The name a statement declares** is the last of the words it starts with, if it is a name and a type or another name stands before it; what stands before those two qualifies them, whether GLSL knows the word or not. So `uniform PREC float speed;`, `COMPAT_VARYING vec4 TEX0;`, `static const float x = 1.0;` and `inline float2 f()` declare `speed`, `TEX0`, `x` and `f`: macros for a precision or a qualifier and words of other shading languages cost no name. The same rule reads a parameter and a field. The scan this parser replaced took "the last name before `;`, `,`, `=`, `[` or `(` that a word precedes"; over a macro-heavy corpus (libretro's GLSL shaders, 604 files, 29,287 names) the parser now finds every name the scan found and 25 more.

The reader is one state machine and stays one class: its parts read the same token stream and must agree on its order. Every token is a comment, part of a directive, or code, tried in that order:

| Part | Its state | Ends |
| --- | --- | --- |
| Comment | The comment lines right above the line being read; whether the line before left a comment open, which the lexer tells | A line of code or an empty line |
| Directive | What the directive expects next, as one enum (`COMMAND`, `DEFINE`, `MACRO`, `PARAMETERS`, `TEXT`, `IF`, `IF_ZERO`, `INCLUDE`, `PRAGMA`, `HOST`, ...); the macro being defined. Per open `#if`: where the code reader was, and the level `#if 0` disabled code at | With its line, unless the lexer tells that it goes on; `#endif` |
| Code | The mode, the statement, how many of its brackets are open, the comment above its first token; in braces their depth and the function whose body is read. Beside it the words read last, from one that began a line or a statement on, with the end of the code before them, their line and the comment above them | The end of the declaration |

What each event keeps and clears:

| Event | Effect |
| --- | --- |
| A line ends | The comment above is cleared unless the line was nothing but comment. A directive that does not go on ends: its macro is declared, `#if 0` starts to disable code |
| `{` outside of an initializer | `TOP` to `BODY` for a function (declared at once, its range grows with the body) or for nothing, to `FIELDS` for a struct or block (the statement goes on). Open brackets are forgotten: no `{` stands in them |
| `}` that closes the fields | `FIELDS` to `TOP`; the statement goes on to its `;` |
| `}` that closes the body | `BODY` to `TOP`, the function ends there |
| Recovery, see below | `FIELDS` or `BODY` to `TOP`; in `TOP` the statement before the words is declared. The words become the statement, with their comment |
| `#else`, `#elif` | The mode, the depth and the function are what they were at `#if`; the statement and the words read last are dropped if it began in a body. Open brackets are not restored |
| The text ends | A directive, a body and a statement end where the last code did |

**Recovery is one rule with one way out.** What is being read (a body, the fields of a struct, a declaration that did not end) is left only for what can start a declaration outside of it **and** cannot go on with it; and never for a line that merely looks like a declaration, since a parameter list, a struct and a body all go on with `type name` lines of their own. Two things qualify:

- a function head, `type name (` with any qualifiers before it, that began a line or a statement. Among fields any such head counts, since a field has no `(`. In a body and in a declaration that did not end, the type must be a built-in or a struct declared above, since an unknown word before `name(` may be a macro there;
- `uniform`, `varying` or `attribute` at the start of a line or a statement.

`recover()` then ends what was being read at the end of the code before those words and goes on with them as the statement. Besides that rule, two things are no recovery but belong to it: a `{` inside `(` or `[` outside of an initializer means they lost their closing ones (`float f(vec3 p {` is a function), and a declaration that lost its `;` ends where a new line starts another (rule 2). The acceptance table is `GlslRecoveryTest`: 39 rows over an expression, parameters, fields, a body, conditional branches and statements, each context once written over several lines as it may be, where nothing must be left, and once with its delimiter missing, with a comment before the candidate, and at the end of the text; every row is also read cut at every character.

Known gaps of recovery:

- A function head among fields or in a body must begin its line or statement; behind a stray character on its line it is not seen.
- A declaration that a conditional cuts in two goes on from its last branch: a parameter list with one parameter per branch declares the function with the parameters of the last one. A declaration that a branch began and did not end ends with the branch, and what it says so far is declared, so each branch declares its own.
- In a body, an `#else` that the first branch closed a brace before cannot open that scope again: what was declared in it is not seen in the second branch.
- A statement keeps 2,048 tokens and 16,384 characters. A token it does not keep inside brackets declares nothing, so a table of numbers may be as long as it likes; one outside of brackets, among fields or in the declaration of a body, may be a name that is lost, and the knowledge then says that it is not complete: nothing exact is answered from it.
- The fields of a struct without a name (`struct { float x; } s;`) are kept nowhere; the knowledge says that it is not complete.

Closed with half 2: a type and a name before `=` or `;` at the start of a line leave an open bracket (`float x = sin(` above `float y = 2.0;` declares both), since no parameter and no argument goes on like that; and `struct S` with no brace yet is a type, so `S` is coloured where it is used while its fields are still being written.

#### The caps, and what is shown beyond them

| Cap | Where | Beyond it |
| --- | --- | --- |
| 10,000 symbols | the new editor, and what files know of each other | later declarations are not known: they do not complete, fold marks and links are kept |
| 524,288 characters, a quarter of them for names alone | the same | a symbol keeps its name while names fit (131,072 characters, what the scan's cap counted), and says no more than its name once the other three quarters are used up by declarations as text, comments and parts |
| A line of more than 65,536 characters | the same | nothing of the text is known |
| none | the old editor, which reads the whole text as it always did | |

In every case the knowledge is of the text it was made of: a text that outgrows a cap gets its own, smaller knowledge, never the one of the text before (`AnalysisTest.knowsNothingOfATextWithATooLongLine`). The editor may ask a knowledge of an older revision while the next is made; that is what `at`'s word is for, and nothing is drawn from it as fact.

#### Half 2: what was settled before its code

Half 2 reads the statements of bodies and answers three things half 1 cannot: what a name means at a place (a local hides a global), what type an expression has (the overload a call means, the members after a `.`), and where a declaration is used. These were its entry conditions; each is a decision, with what was rejected.

1. **What survives a statement: the type, as text, on the symbol.** `Symbol` gets `type`: for a variable, parameter or field its type as GLSL writes it without qualifiers (`vec3`, `float[2]`, `Ray`), for a function what it returns. It is not cut, unlike `detail`, and it crosses files with the symbol, so an included function is chosen by its argument types and an included struct has its fields. A parameter without a name is a part with an empty name, so a signature is whole: `float f(float, vec3)` has two parameters. A declaration's identity is its file and where its name stands; for a function, its name and the types of its parameters, so a prototype and its definition are one. Rejected: a syntax tree or type objects in the seam. Nothing but the language reads a type, and text is what it compares.
2. **Scopes are kept; uses are not.** A knowledge keeps, for every local and parameter, its name, its type, where its name stands, from where it is visible (behind its declarator, as GLSL says) and where its scope ends: six values in flat arrays, sorted by name, so "what does `p` mean here" is a binary search and a few comparisons. It keeps nothing per use. **Occurrences are computed when asked for**, by reading the file again with its knowledge: rename and "find usages" are rare and explicit, a keypress never needs them, and kept uses would cost memory with every use in every file. "Memory does not grow with the file" was said of half 1 and is now: it grows with declarations, locals included, under a cap.
3. **The type of an expression is computed when asked for, from tokens.** `GlslExpression` reads the tokens of one expression in a `GlslStatement` and answers its type as text, asking a scope what a name is. Nothing is stored. Per keypress it types what the call scan already bounds: the arguments of the call at the caret (20 lines, 400 tokens) or the expression before a `.` on the caret's line.
4. **The budget and truncation.** Locals have a cap of their own, as many as symbols (10,000), and their names and types count against the three quarters of the characters that are not for names of declarations, so no local can cost a declaration its name. A knowledge says whether it is `complete()`: false when any cap cut it short or a statement had more tokens than it keeps. An exact answer (occurrences) is refused for a knowledge that is not complete or not of the text given; an approximate one (Look up, completion, the strip) is given from what there is.
5. **Who asks `at` by name only, and who from a place.** By name, offset -1: the Add uniform fix ("does the file declare it") and what a file asks of the files around it. From a place: Look up, completion's "is it a function", the strip, and the types above. Only the second kind sees locals. While the user types, the knowledge is of the text a moment ago and the place is off by what was typed since; that is good enough to find the function around the caret, and a local declared a moment ago is known a moment later. Occurrences take the text and its knowledge together and say nothing otherwise.
6. **Positions stay UTF-16 offsets** into the text a knowledge was made of.
7. **Bodies are read by the same statement and the same recogniser.** A statement of a body that starts as a declaration does (`type name` and then `=`, `,`, `;` or `[`) is collected in the `GlslStatement` the reader already has, and `GlslSymbols` says what it declares; any other statement is dropped at its second or third token, so reading bodies costs their declarations, not their expressions. Scopes are the braces, the header of a `for`, and the parameters for the body. **The window of two words that half 1 first had in bodies is gone since half 1's rework and is not coming back**: what remains beside the statement is the one recovery candidate of all contexts (the words since the start of a line or statement), which also has to see a function head at the start of a line in the middle of a statement, where no statement begins. The editor release builds ship asks for no locals and its bodies are skipped as before.
8. **No scope type in the seam beyond `Knowledge.Scope`**, which is what a file sees of other files. Scopes inside a text stay inside its knowledge.
9. **`tuning/ConstantContext` stays its own scan.** It asks, for one offset, what kind of bracket encloses it (the header of a `for`, `layout(...)`, an array size, a `case` label). The reader keeps declarations and scopes, not the roles of brackets; the one fact it could give, "inside a function", is three lines of that scan.

#### Half 2, reworked: certain means proven

An independent review of half 2's first shape (`.foreman/reviews/knowledge-half-2.md`, 18 findings) showed that its uses called places certain that were not: it asked a knowledge that reads every branch of every conditional, with scopes that ended at the wrong token, and a second, partial reading of directives beside it. A rename will change the user's text at the certain places, so this is the rule above all others, and the model that follows from it. It was written before the code.

**The rule. A place is certain only if it is proven: the program the compiler sees has that token there, and in that program the token means that declaration.** Whatever the model does not execute, cannot bound or cannot read is not certain: it is listed with its reason, or the question is not answered at all (null). No answer is better than a wrong certain one. This is enforced in one place, where a use gets its kind (`GlslUses.kind`, and `GlslUses.both` where one place is read more than once), and nowhere is a kind upgraded afterwards.

What follows is the model as it was written before the code, corrected where building it showed it to be wrong or too coarse; those places say so.

**State: the reader, the types and the editor's three features are built on it. The resolver this section describes was its second shape and is replaced.** A second independent review (`.foreman/reviews/knowledge-half-2-second.md`) found the editor's side safe and that resolver still calling places certain that are not: each time in something it reasoned about instead of executing (the arguments of a macro, a type written with a macro, a `#define` continued over a line end, what the app does to a text before it compiles it, a cut by a budget of its own, a symbol of an older text). What is built now is "Uses, third shape" below, which turns the rule around; where the two differ, that section holds. Of this section the rule, the two feeds, the frames, the checkpoint and the limits stand; the rules about what a macro's text may do are gone.

**One reader, two feeds.** The reader of declarations stays one reader, and the statement and the recogniser stay what it is made of.

- *For the editor* it is fed the lines as they are and reads every branch of every conditional, because an outline and completion want the names of all of them. What it says of a place then is approximate, and only Look up, completion and the strip ask it. Where it cannot know it says nothing rather than something likely: of several declarations of a name outside of functions a place means the one declared last before it (a macro defined behind a variable of its name, a variable declared behind the `#undef` of one); a type that is written with a macro (`VEC v`, also `vec3` when a `#define vec3` exists) is not known, and neither is a function with such a type; a value or a call with more tokens than are read (200 before a `.`, 400 from the name called) has no members and tells no types. An arm of a conditional that is decided off ends with it, since the reader asks the same preprocessor an exact reading asks; see "One preprocessor" below.
- *For an exact question* it is fed **the program**: the lines of one translation unit after the preprocessor ran, a root file with its includes in place and only the lines that are compiled. No conditional is left in that feed, so no declaration of one branch can be seen in another, and an include supplies its names from where it is included and only if that line is compiled. Nothing exact is ever asked of the editor's knowledge.

**The preprocessor is executed**, in order, by `GlslUses` in front of that feed:

- An environment of macros: a `#define` and an `#undef` take effect where they stand and only if their line is compiled; a macro is object-like or function-like (a `(` right behind its name), and a function-like one is used only before a `(`. `GL_ES`, `__VERSION__`, `__LINE__`, `__FILE__` and what the host says it defines (`SHADER_EDITOR`) are defined; another name the program never defines is undefined, except one that starts with `GL_`, which the compiler may define and is not known.
- A condition is read whole: every token of the directive over its continuation lines, without its comments. `#if` and `#elif` evaluate integer literals, `defined(X)` and `defined X`, `!`, `&&`, `||` and parentheses; `#ifdef` and `#ifndef` ask the environment. Anything else (a comparison, a macro as a number) is **not decided**.
- A branch that is decided off is not fed; a use in it is `INACTIVE`, and so is another `#define` of the name in it, which is what the name would be if the branch were compiled. Of a conditional that is not decided, the first branch that may be compiled is fed, so that the code keeps its shape; every place in any of its branches is listed as `CONDITIONAL`, and what the branches may have changed is not trusted behind it: a name that a branch defines or undefines is `CONDITIONAL` wherever it is used afterwards, and a name that a branch so much as mentions, since it may declare it, is listed from the conditional to the end of the function (or of the program, outside of one).
- **A conditional that is not decided has to stand between two statements, and each of its branches has to be whole statements**: it closes the brackets it opened and no others, and its code ends with a `;`, a `{` or a `}`. Then the code behind it is in the same scopes whichever branch is compiled. Otherwise, or if a branch includes a file, everything behind it to the end of the program is listed. (Corrected while building: the model first asked only that the branches open and close the same number of brackets. That is not enough: `if (c)` in one branch and nothing in the other have the same brackets, and decide whether the statement behind them is a scope of its own.)
- The payload of `#pragma`, `#error`, `#extension`, `#version` and `#line` is not code and holds no use. A word beside `##` is a fragment of another name and is never offered.

**Macros are not expanded**, so what an invocation may do is bounded from its text:

- What an invocation may do is told from the macro's text and from the text of every macro that text names, as they are defined where it is invoked.
- A name that stands in the text of a macro (not as its parameter) is `IN_MACRO` there. Where that macro is invoked and its text is more than an expression, the name is listed from there to the end of the function (of the program, outside of one), since the invocation may declare it and so hide it. (Corrected while building: an invocation whose text is an expression declares nothing, so `#define TWICE (x * 2.0)` leaves `x` certain behind `TWICE`.)
- An argument of a function-like macro is a use like any other only if the macro's text is an expression: no type, no keyword of a statement, no `{`, `}` or `;`, no `#` or `##`, and no two words in a row. Otherwise the argument is `IN_MACRO`, and its name is listed to the end of the function.
- An invocation of a macro whose text has `{`, `}` or `;` or a keyword of a statement may change where statements and scopes end: every place from there to the end of the function is listed; if its brackets do not balance, to the end of the program.

**A word is a use only where code refers to something.** In a body that is every name that is not being declared there. Outside of bodies it is a name behind the one being declared, in its initializer or its brackets; a name in square brackets before it (`float[N] a`); and, for a struct, the type before a name. The name of another declaration, of a parameter, a field or a block, and a word in the parentheses of a `layout` are no uses, whatever is declared under that name. (Added while building: without it a global `n` was "used" by a field `float n;`.) A function head with the name and the parameter types of the function asked about is another declaration of it, as a prototype and its definition are.

**Scopes belong to whole statements.** In a body the reader keeps a stack of frames, one for each statement that is not finished: a block; a `for` or a `while` with its header, whose declarations (also a `while (bool x = ...)`) are seen to the end of its whole substatement; an `if` with its condition, its statement and, if an `else` follows, that statement too; a `do` to the `;` behind its `while (...)`; a `switch`. A statement that ends ends every frame it was the last part of. This replaces the two special states a `for` had. Not read: a struct declared inside a function; it makes the knowledge incomplete.

**A conditional checkpoint is the whole continuation of the reader** (editor feed only; the exact feed has no conditional): at `#if` the reader keeps where it is, mode, depths, the function, the statement's size, the frames and how many locals there are; at `#else` and `#elif` all of it is put back, in every mode alike, and what the branch before declared in scopes that are still open is seen no further than that `#else`.

**What a declaration means is kept apart from how much of it is shown.** The parameters of a function become the locals of its body from the tokens of its head, whatever the budget does to the symbol's parts. And every limit that drops something makes the knowledge say that it is not complete: the caps of symbols, locals and characters, a symbol whose parts or texts were cut, a statement with more tokens than it keeps, more parameters or fields than a symbol keeps, frames or conditionals nested deeper than is tracked, and every place where the reader had to guess (a lost `;`, `)` or `}`, a text that ends inside a body). An incomplete program answers no exact question.

**Types:** a type that is not known never chooses a form and is never replaced by a likely one. A matrix that is not square is computed as GLSL says (`mat2x3 * vec2` is a `vec3`, `transpose` swaps), or not at all.

**Across programs.** A file may be compiled in several programs: every file that no file includes is the root of one. `Syntax.uses(sources, roots, symbol)` reads each program that may come to include the declaring file, and a place that more than one of them has is one place: certain only if it is certain in all of them; if it means the declaration in one and is not declared in another it is `NOT_INCLUDED`, if it means something else in another it is `REDECLARED`. A use that is certain but stands in a file that does not include the declaring file where it is read is listed as `NOT_INCLUDED` all the same, with the program that includes both, since the next program may include it without. A use that means another declaration of the name, made outside of functions in another file, is `REDECLARED`; that declaration itself is no place. If no program compiles the declaration (its include is switched off, or the files only include each other), its file is read as a program of its own. An include that cannot be found, or includes nested deeper than 16, leave the question unanswered.

**What is not modelled, and how it degrades:**

| Not modelled | Outcome |
| --- | --- |
| Macro expansion, `##`, `#` | The three rules above; a name built with `##` is not found |
| Comparisons, arithmetic and macros in `#if` | Not decided: both branches listed, what they touch listed behind them |
| What the compiler defines beyond `GL_ES` | Not known: not decided |
| A struct declared in a function, a struct without a name | Incomplete: no answer |
| A macro that opens or closes a brace the text does not | Everything behind it listed; in practice the reader cannot read what follows: no answer |
| A conditional that is not decided in the middle of a statement, or with a branch that is not whole statements | Everything behind it listed |
| Whether a new name is taken (`taken`) | Asked of the editor's knowledge, which reads every branch: a clash is a warning, and one too many is one |
| Text the reader has to recover from | Incomplete: no answer |
| Implicit conversions | GLSL ES has none; an argument fits a parameter only by the same type |
| Which form an unknown argument chooses | `AMBIGUOUS` |
| A member of a value of unknown type | `UNTYPED` |

**The review, finding by finding:** all eighteen are agreed with; two get another fix than the one proposed.

| Finding | Judgement |
| --- | --- |
| F1 a `for` ends at the first `;`, F2 `while` conditions | Agreed. Frames owned by whole statements |
| F3 a branch's local hides the other branch's global | Agreed, **other fix**: not branch identities inside the editor's knowledge, but exact questions asked of the preprocessed program, where the other branch does not exist; the editor's reader gets the coherent checkpoint |
| F4 macros and names conflated, F5 defines not executed, F6 conditions not read whole, F7 pragma payload, F8 `##` | Agreed. The executed preprocessor above |
| F9 matrices that are not square, F10 a default type for an unknown sampler | Agreed. Computed right; unknown stays unknown |
| F11 token cap, F12 budget drops parameters, F14 parts cap | Agreed. Identity apart from presentation; every cap makes incomplete |
| F13 an include reached is not an include executed | Agreed, **other fix** than an ordered scope: the include is fed in place |
| F15 argument types read the whole line | Agreed. Starts at the call's token |
| F16 a lookup walks every local of the name | Agreed. Each local knows the one of its name it hides; a lookup walks outward, at most as deep as scopes nest |
| F17 the other files are asked although the text knew | Agreed. Asked only when the text has no answer; answers kept per word |
| F18 places and clashes read a line and a file again for each use | Agreed. Texts, knowledge and line ends kept for one request |
| The reader's model (continuation, statements, identity apart from budget) | Agreed on all three. Kept: one reader, the statement, the recogniser. No class is added for the frames; they are the reader's state for bodies |

#### Uses, third shape: certain by positive conditions

A second independent review of the resolver (`.foreman/reviews/knowledge-half-2-second.md`) found seven more places that it called certain and that were not, two that it dropped, a cost bound it did not keep, and that the editor's reader and the resolver each read directives their own way. All seven have one cause: the resolver **reasoned about** something instead of **executing** it. The second shape said "certain unless a reason not to be is found"; every reason nobody thought of was a wrong certain. The third shape says it the other way round. Its text was reviewed before it was built (`.foreman/reviews/occurrences-model.md`, 14 changes); what was adopted is below in that review's wording, and what was not is listed at the end with why.

**A place is certain only when every one of these holds; anything else is listed with the conditions that failed, and nothing is dropped that may be a use.** They are checked in `GlslUses.kind` and nowhere else, each from a fact that execution produced.

0. **The declaration asked about is itself proven to be one, by the same checks as every place.** The question starts from what the editor's reader takes for a declaration, and that reader executes no macro: behind `#define RET return` it takes `RET x;` for a local. So the place of the declaration passes conditions 3 and 6 like any other (`GlslUses.proof`): if a macro that was not executed stands before it, or its line is compiled only under a condition that is not decided, or its name is a macro there, it is listed for that, and then nothing in that reading is certain, since nothing can be said to mean for sure what is not a declaration for sure. A third review (`.foreman/reviews/occurrences-third.md`) found this missing: the declaration was answered by where it stands.

1. **The text read is the text compiled.** The resolver reads, for each program, the string the engine passes to the driver and its source map, made by the one function `PlanSource` calls (`CompiledSource.of`: assembly, blanked host directives, generated lines, the cut behind a backslash, non-ASCII to spaces, in that order), without the tuning lines. Nothing else in this section describes that text. What the build refuses has no answer. A program of version 100 in which a line ends in a backslash has no answer, since compilers differ there. Each reading is read with the keywords of its own version: a program in which a word stands in code that the reader takes for a keyword and that its version does not know as one has no answer. The resolver lexes logical lines, joined over backslashes, each character mapped back to its file and offset; no answer if a name stands across a join, or if a block comment is open where a directive's line ends.
2. **The declaration is the one the caller sees, and every file is the one that was read.** The programs are the programs of the project folder that compile the declaring file, read from the current view (the documents as the editor has them over what is stored). If the folder's files are not all known, or a file cannot be read, there is no answer. The caller says which revision of the declaring file its symbol is of; the answer is null unless that is the revision read, and unless a declaration of that name and kind stands at the symbol's place in a program as read. The size of an array is not part of that comparison: the caller's symbol has it as it is written (`float a[N]`), the program has the number of the macro in its place. If the name stands there only in lines that no program compiles for sure, there is an answer in which nothing is certain, and the place of the declaration is listed as not compiled like every other. The answer carries the revision of every file each reading read, not only of files with places: a file without places can still change what places mean.
3. **No macro that was not executed stands before the place.** Directives are executed in order over logical lines, and only in compiled lines. A macro without parameters whose text is one number, with signs and parentheses at most, is executed: it is replaced by its tokens, never by its text. Any other mention of a macro in code, with or without parameters, also its name without `(` behind it, as an argument, or where a type or a name stands, is not executed: every place behind it to the end of the program is listed. A macro defined, undefined or redefined in an arm of an undecided conditional is undecided behind it: every mention of it is not executed, and a test of it is undecided. A macro's text is not resolved where it is defined. A mention of a macro that is not executed inside `#if`/`#elif` makes that conditional undecided; it does not taint. A macro defined again with another text while it is defined does not compile: no answer. Nothing is reasoned about what a macro's text could produce; the next step is to expand macros without parameters exactly, which replaces the number rule.
4. **Nothing on the way was cut.** The resolver has no token budget of its own. What a call's arguments and the value before a `.` are is read from the whole statement the place stands in, also over lines; a statement beyond what a statement keeps makes its places `AMBIGUOUS` or `UNTYPED`, and the reader's own limits make the program incomplete, which has no answer.
5. **A call is certain only if its form is proven.** Every form of the name that can be seen there is proven, every argument has a type that is proven, and exactly one form takes them. A type is proven only if the declarations it comes from (variable, struct, return type) are certain by these rules. An array size is part of the type and is proven only as a literal after number macros. If the form asked about is among those that can be seen and nothing proves which is meant, the call is `AMBIGUOUS`; it is never left out. **A function is its form, whichever of its heads is seen:** a head without a body and the one with it, with the same parameters as they are after number macros (a parameter without a name, with a qualifier or a precision, with its size at its name or at its type, is the same parameter), are one function, and one identity is used for the declaration asked about, for every other head and for every call (`GlslUses.entity`). Asked at any head, the other heads are declarations and the calls are uses; two heads of one form are not two forms. One form alone is meant without looking at the arguments, unless the language has a function of that name, which other arguments would mean. The same for a field: the value's type is proven and is the struct, or the value is the instance of a block (`uniform B { float x; } b;`), whose fields are that instance's; else the place is `UNTYPED`, also where a condition that is not decided keeps the struct's name from being trusted. Only a member that is no field of any struct or block of the program, as in `p.xy`, is no place. A uniform block without an instance name declares its members globally, and the reader declares them so.
6. **The line is compiled, and no conditional before it is undecided for it.** Decided off is `INACTIVE`; undecided is `CONDITIONAL` in its arms and behind it for what an arm names, and for everything if an arm is not whole statements. `GL_ES` is 1. `__VERSION__` is the version. `GL_FRAGMENT_PRECISION_HIGH` is 1 in 300 es and undecided in 100. `SHADER_EDITOR` comes from the generated lines. `__LINE__` and `__FILE__` are not executed. Any other name beginning `GL_` or containing `__` is undecided unless the program defines it. Every other name is defined only by the program. Names of an `#include` inside an arm are names of that arm; keywords are not names. One entry is kept for a conditional and a name, not one for each mention.
7. **Every program that compiles the place's file is among the programs read; else `NOT_INCLUDED`.** That holds for the declaring file and for a file that includes it, and for every place that would be certain, another head of a function too. **The programs of a folder** (`ProjectKnowledge.programs`) are the files a compiler is given as the first of a program: a file that nothing includes; a file whose own text defines `main`, which runs when it is chosen to ("Run this file") whatever includes it; and a file another one reads as a pass (`#pragma se input`). Which files include which says what a program is made of, not which files are programs: the third review found a library that was included by one file and read as a pass by another, and whose own program was not read.
8. **The reader read the program without recovering from a syntax error.** A program in which it had to guess has no answer (see what was not adopted).

**Why a place is listed** is told as the things that stand between: each a macro that was not executed or a conditional that is not decided, with its name, the file and where it is defined or stands, in the order of the program. A screen can show each such macro once and group the places by them, so one look at a macro settles many rows.

**One place read more than once** (`both`): a file included twice, or compiled in several programs. A reading in which the line is not compiled says nothing about a rename, since changing text that is not compiled changes nothing compiled. So: not compiled in every reading, `INACTIVE`; else only the readings in which it is compiled count: certain in all of them, certain; a use in some and something else in others, `REDECLARED`, or `NOT_INCLUDED` where the name means nothing in one; listed in any, listed for that reason. A place that is compiled in at least one reading is never inactive: a library with an include guard that two files include keeps its own uses.

**What a rename is given besides the places**, since it carries the same promise (the apply itself is not built here):
- *The files and revisions read.* The rename loads every document it changes first. Then, on the main thread and in one turn, it checks every one of those revisions and applies every edit as one `Document.Link`, or applies nothing. Undo takes back every document or none.
- *The bindings of a reading*, which two answers compare cheaply (`compare`): for every name of the compiled text, by its number in the program, which declaration it means (a function is the first head of its form, a member its field), and whether that is proven: its line is compiled for sure, no macro that was not executed stands before it, no condition that is not decided may have changed it, and its form or type was told. Where it is not proven, the reading has what the reader took the name for, which is a guess; for an invocation of a macro that was not executed, which macro, and what each name of its text would mean where it is invoked. Before the edit is shown as done, the edited programs are read again, and the comparison has three answers. `CHANGED`: a name means something else, also if one of the two is a guess; the whole edit is undone, and the user is shown the places that changed. `SAME`: every name is proven to mean what it meant. `UNPROVEN`: nothing was seen to change, but some names are not proven, and **nothing vouches for those**: behind `#define RET return`, changing `RET x;` to `RET y;` changes what is returned, and both readings take the name for the same local. `sameBindings` is true for `SAME` only. A rename with a place behind a macro that was not executed can therefore not be confirmed by this check; the screen has to say so. The second answer was missing before the third review: a guess that stayed the same counted as the same.
- *Whether the new name is taken*, from the same readings, not from the editor's knowledge of one file: at every place, in every reading in which it is compiled, the new name must not mean something already, must not be a macro defined anywhere in that reading, nor a word of the language. What it would hide elsewhere is what the check after the edit finds.
- *Where a rename may start:* only from the declaration or from a place `uses` calls certain (`Uses.at`); otherwise it says why the place may mean something else. Look up only finds the candidate.
- *On what a meaning depends* (`depends`): the conditions the text does not decide that name the name before one of its places, and those that kept a form or a type from being proven at one of them (an argument declared as a `float` in one arm and as a `vec2` in the other chooses another form of the function), each with its line. With one, a place may mean the declaration with one outcome and something else with another, and no rename is right for both: the screen can refuse or warn instead of leaving such a place behind. It is told also where the place is listed for another reason.
- `renameable` refuses a uniform the engine sets by name, `Tuning.HOST_MACROS` and `main`.

**What is a place and what never is.** Never: comments, the text of `#pragma`, `#error`, `#extension`, `#version` and `#include`, and a name a macro builds with `##`, which no text writes; every invocation of a macro whose text pastes is listed for that (`PASTES`). A test of a macro (`#ifdef`, `#ifndef`, `defined`, `#undef`) is a place of it: certain behind its definition, listed before it (`TEST`), as an include guard's `#ifndef` is, and never dropped. Another `#define` of the name and what means it are listed (`REDECLARED`). Another head of the function with the same parameters, also in another file, is a declaration of it. Another form of the function is no place.

**One preprocessor.** Executing directives (the macros defined at a line, the open conditionals, whether a line is compiled) is one unit, `GlslPreprocessor`. The resolver reads every program through it, and the editor's reader asks it too, for the one file it reads:

- *What `GlslDeclarations` lost:* deciding anything about a conditional. Its states for `#if` and `#if 0` are gone; it hands every directive to the preprocessor as one line and asks which arm is compiled.
- *What it does with the answer:* it still reads every arm, since an outline and completion want every name. But an arm that is decided off ends with its `#endif`: the code goes on from the end of the last arm that may be compiled, or from the `#if` if there is none, so a local declared in `#ifdef MISSING` is not seen behind it. What such an arm declares outside of functions, and a macro it defines, mean nothing outside of it (the knowledge keeps where those arms are), and a macro is one from its `#define` to its `#undef`, also inside a function where a local has its name.
- *What it kept, and why:* reading a `#define` into the symbol the outline shows (its text and parameters are presentation, not meaning); not reading `#if 0` at all, which is how code is put away and whose names nobody wants offered, now asked of the preprocessor as "written as `#if 0`"; and the checkpoint.
- *Its limit:* the editor reads one file. Its preprocessor has what the host defines, takes the version from `#version`, and behind an `#include` treats a name the file does not define itself as undecided, since the included file may define it; then every arm is read as before. It cannot know that another file includes this one and defines something first.

"Agree" means: where uses says certain, Look up names the same declaration; where Look up names a declaration, uses has the place as certain or listed. The first half is asserted for generated programs of one file, and for the three inputs of the second review where the two disagreed. The rule that a rename starts only from a place `uses` calls certain makes what is left harmless.

**Cost.** One reading of each program; one entry for a conditional and a name, found by a search; the limits are the build's, by using its assembler. An answer reads the folder, assembles each program and reads it once: for a text of 1,000, 5,000 and 50,000 lines with the name in every second line 14, 43 and 250 ms on a desktop JVM, off the main thread, when a rename asks. The case the second review made quadratic (n mentions in an undecided conditional, n uses behind it) takes 45, 62 and 111 ms for n of 5,000, 10,000 and 20,000. A field among 1,000 and 5,000 declarations with as many uses behind a `.`: 11 and 43 ms; which struct the field is of is found once for a reading, not for every member (it was 20 and 185 ms before). On a real text of 321,000 characters with 902 declarations, 18 ms a question. A conditional that is not decided n times in a row, each naming the name: 22, 89 and 179 ms for n of 1,000, 5,000 and 10,000 (the conditional a token stands in is found by halving, not by going through all of them). As many statements on one line: 6, 21 and 42 ms; a column is an offset in its line where a file has no character of two units, and was counted character by character for every name before. A condition of a directive is worked out without a call for each `!`, and parentheses deeper than 64 are not decided: the third review exhausted the stack of the reader, which the release editor shares, with twelve thousand `!`.

**Checked by.** The 37 probes of the first review, the 14 failing ones of the second and the 25 of the third (11 failing) as tests (`UsesProbesTest`, rows of `UsesTest`, `GlslReviewProbesTest`, `UsesThirdProbesTest`); `UsesSeamsTest` for what the author looked for after the third review; `UsesRenameTest` for what a rename is given.

`UsesReferenceTest`: 600 generated programs. Its reference reads the string the driver gets for every program of the folder, expands macros itself, runs every outcome of what is undecided, and binds every name by the scopes, the forms of functions and the fields of GLSL. Generated: macros with and without parameters (a macro as an argument, a macro for a type), a word before a name that is a macro (which reads as a declaration and is none, or one of another type), conditionals that are decided and that are not, arrays of the size a macro has, functions with a form for one parameter and one for two and with a form for an `int` and one for a `float`, heads without a body before and behind the body and without one, structs with fields of the same name and a local of one struct under the name of a global of the other, a block without a name and one with an instance, a second file with an include guard that is included once or twice, and that file read as a pass by a third as well, which makes it a program of its own. Asserted for each of 10,103 declarations:

- the declaration asked about, where the answer calls it certain, is declared at that place in every outcome that compiles it, and one does; where it is not certain, nothing is;
- every certain place means the declaration in every outcome of every program in which it is compiled;
- every place that means it in any outcome is certain or listed;
- nothing called inactive is compiled in any outcome;
- where a place means the declaration with one outcome and something else with another, the answer says on what it depends (101);
- the rename itself (9,934): with a fresh name at the declaration and at every place that is a use, every outcome of every program binds every name as before;
- every declaration has an answer, which the test requires: each program builds and none makes the reader guess, so no answer would be the easy way out.

And for 1,179 places that only the editor's reader takes for a declaration (a name behind a macro, a declaration in an arm that is off): no answer, or one in which nothing is certain. This is the question the third review asked and the test did not: it had accepted the declaration's place as told.

In a program of one file, Look up names the declaration at every certain place (3,488) but for 8 where the editor's reader takes `N b;` behind a number macro `N` for a declaration of `b`; it executes no macro. No rename starts from what Look up names. Behind a `.` Look up names nothing.

The test fails when a macro that was not executed taints nothing, when a conditional that is not decided taints no name, when `#undef` does nothing, when a declaration with the number of a macro in its type has no answer, and for each of the third review's faults that was put back: the declaration certain where it stands (F1), programs by includes only (F2), a head for itself instead of its form (F3), a member of a struct that is not trusted dropped (each tried).

**What the generator still cannot produce**, so that only rows and probes cover it: types other than `int`, `float` and structs, and with them vectors, swizzles and operators other than `+`; structs in structs, as parameters, as what a function returns, built by a constructor, in arrays, declared in a function or in an arm of a conditional; blocks in arrays, with a layout, in an arm or in the library; more than two forms of a function, parameters with qualifiers or without names, array parameters; macros that paste, that span lines, that have several parameters, that open or close a brace, that are defined or undefined in an arm that is not decided; `#elif` and conditions that compare or add; more than one level of includes, more than two programs, a file that defines `main` and is included; `switch`, `do`, `return`; several declarators in one declaration; version 100's words; `#line`, line continuations, block comments, characters that are no ASCII.

**On real texts**, every declaration asked (a measurement, not a test: the fourth and fifth rows are third-party texts that are not in the repository):

| Texts | Files | Declarations | No answer | Certain places | Listed places | Declarations whose meaning depends on a condition |
| --- | --- | --- | --- | --- | --- | --- |
| The bundled examples, as one folder | 34 | 173 | 0 | 463 | 3 (the name in a directive's test) | 0 |
| The bundled database shaders, each alone | 22 | 127 | 0 | 342 | 0 | 0 |
| The five ShaderToy fixtures, as imported | 20 | 160 | 0 | 387 | 0 | 0 |
| A macro-heavy corpus (shaders written for another host, and a ray tracer's), each file alone | 596 | 55,346 | 15,913 (28.7%) | 3,504 | 223,586, of them 205,992 not compiled | 66 |
| The same with `FRAGMENT` defined, as that host does | 596 | 55,936 | 19,173 (34.2%) | 8,761 | 199,120: 87,370 not compiled, 94,704 behind a macro, 11,012 conditional, 5,102 another declaration, 650 pasting, 242 a test, 40 ambiguous | 5,544 |

Three things this says. In what the app ships and imports, every declaration is answered and nearly every place is certain; the fixtures have 12 places more than before the third review, calls between a head without a body and its body, which were dropped. In texts that write a macro into nearly every declaration (`COMPAT_PRECISION`, `COMPAT_TEXTURE`), the floor keeps one place in fourteen of the compiled code certain (8,761 of 120,511) and lists the rest behind the macro that stands before them: that is the price of executing nothing but numbers, and what exact expansion of macros without parameters is for. And before the third review 19,956 were called certain there: the 11,195 that are not any more were declarations behind such a macro, called certain by where they stand.

No answer in that corpus is each time one of the rules above, found by asking the build and the same text changed, not by reading it (declarations and files; without and with `FRAGMENT`): a line that goes on behind a backslash in a version below 300, 15,703 in 33 files and 15,482 in 30, which is one large family of texts with macros over many lines; a word in compiled code that only 300 knows, 36 in 4 and 3,266 in 50 (`mat4x3`, `case`, `smooth` in texts without a version); a program that does not build because a file it includes is not there when it is asked alone, 131 in 15 and 146 in 15; a text in which the reader has to guess, or a macro is defined twice with another text, or an `#error` is compiled, 43 in 2 and 279 in 5. The first of these is the rule that costs most. It is kept as the review set it, since compilers of that version differ on such a line; these are texts for a desktop compiler, which the app does not compile as they are.

**Of the review's 14 changes, not adopted or adopted otherwise:**

| Change | What was done instead, and why |
| --- | --- |
| 1, "lexed with the keywords of its own version" | The alternative the review names: no answer when a word stands in code that the reader takes for a keyword and the program's version does not. A second keyword table in the lexer would reach the editor |
| 4, a place behind the reader's first recovery is listed (`UNPARSED`) | Stricter: such a program has no answer. Telling where the first guess began is new state in a reader that has just landed; no answer is safe and can be loosened |
| 8, no answer for a uniform block without an instance name | Modelled instead: the reader declares its members as globals, and a local hides them |
| 9, "nothing it would hide may be visible there" as part of `taken` | Left to the check after the edit (10), which executes it instead of predicting it |
| 11, give a root file the environment its includes leave | Not built; optional in the review. The editor's reader has the shared preprocessor for its own file |
| 12, comments and other forms as groups that are not ticked | Not places in the answer; a screen can add them from the editor's knowledge |
| 14, the Mesa oracle | Not run |
| 14, cost as counts at three sizes | Timed at three sizes instead, see Cost; the one count there is, is one entry for a conditional and a name |

### 8.4 The comment that belongs to a declaration

1. The comment lines directly above it, with no empty line and nothing but comments between: `//` lines or a `/* */` block, their marks removed, joined.
2. Else a comment behind it on the line it ends on (the usual place for uniforms).

A comment separated by an empty line is about the file or a section. This is what the bundled examples already do: functions have their lines above, uniforms their words behind.

### 8.5 Files know each other

A shader is several files: `#include` pulls text in, an input names another pass. The language knows no paths: a knowledge reports its `links()` as written, and the project layer resolves them and keeps, for the files of the running shader as its last build read them, each file's knowledge and which files it includes. From that:

- **Down:** a file sees what the files it includes declare, directly or through other includes: their names complete (after the language's own words, with the app's uniforms; a function with its parenthesis), and a word is found with its declaration, its comment and its file. A declaration crosses between files as its `Symbol`, whose detail is the declaration as text; no types are shared.
- **Up, as a fallback:** a library that uses a name its includer declares finds it among the declarations of the files that include it.
- **Not across passes:** an input is another program. It is a file to open, not a source of names.
- It is rebuilt when a build has read the files, on the project's worker, and belongs to that build as the list of used files does: when the text or what runs changes, the next build replaces it. Only a file whose bytes changed is read again, and a file that includes nothing and that nothing includes is not read at all, since nobody asks about it: a shader of one file costs a search for a word per build. The editor asks `OpenProject.scope(path)`, which answers from the last build whenever it is asked, so nothing is stale and nothing has to be told. A file the last build did not read sees nothing. A build for tuning, which runs the shader with one number replaced, reads nothing for it and leaves it as it is.
- It ignores order: a name declared below the `#include` line counts as visible inside the include. The editor is then more generous than the compiler, which is harmless while its knowledge marks no errors.

**The index (`project/ProjectKnowledge`) is per file, with the include edges in both directions** (`includes(path)`, `includers(path)`, both through other includes), and that is what the owner's question needs: "what if I want to rename a define in an include file?"

### 8.6 Where a declaration is used, and what rename builds on

Rename's screen is not built, and neither is the step that applies one. What they ask is built:

- `ProjectKnowledge.read(files)` reads every text file of a project folder from a view of it, with the revision of each; null if a folder cannot be listed or a file cannot be read. The view has to be the current one, the documents as the editor has them over what is stored (`OpenProject.reading()`).
- `uses(path, revision, symbol, name, builtins)` on that gives a `Uses`: the places, each with its file and that file's revision, its line, the range of the name in it, the line's text, what it is and its reasons (name, file and line of each macro or condition that stands between); where `name`, a candidate for the new name, is taken; every file that was read with its revision; on which undecided conditions a meaning depends; `at(file, offset)` for where a rename may start; and `compare(later)` for the check after the edit, which says `SAME`, `CHANGED` or `UNPROVEN` (`sameBindings` is true for `SAME` only). When a place is certain, and every other kind, is section 8.3, "Uses, third shape".
- `renameable(symbol, hostNames)` and `Syntax.isName(word)`.

The programs are those of the folder that compile the declaring file: a file nothing includes, a file whose own text defines `main`, and a file another one reads as a pass. A place in a file that some other program compiles, one that was not read because it does not reach the declaring file, is `NOT_INCLUDED`. The declaration asked about is a place like every other: where it is not proven to be a declaration, it is listed, and nothing is certain.

| What | Meaning |
| --- | --- |
| `DECLARATION`, `USE` | Certain, which means proven. `DECLARATION` is the declaration asked about, and every other head of the function it is one of |
| `IN_MACRO` | In the text of a macro, or behind a macro that was not executed; the reasons name those macros in their order |
| `INACTIVE` | In a line that no reading compiles |
| `CONDITIONAL` | In or behind a conditional the text does not decide, which names the name |
| `REDECLARED` | Means what another file declares under the name, or the declaration in one reading and something else in another; or is another `#define` of the name, or means that one |
| `NOT_INCLUDED` | In a file that a program compiles which was not read, or where the name means nothing in one reading |
| `UNTYPED` | A member of a value whose type is not proven |
| `AMBIGUOUS` | A call whose form is not proven |
| `PASTES` | Not the name: the invocation of a macro that builds names with `##` |
| `TEST` | A directive asks whether the name is defined, before the macro asked about is |

No answer (null): the folder is not all known; the file is not at the revision the symbol is of, or something else stands where the symbol says; a program does not build (an include that is missing or includes itself, the build's limits); a program of version 100 has a line that ends in a backslash or a word only version 300 knows as one; a name stands across a joined line; the reader has to guess anywhere in what is compiled (see the caps and gaps in 8.3); a macro is defined twice with different texts, or an `#error` is compiled.

Places are offsets in the text as the editor has it (`SourceText.decode`: line ends are one character).

### 8.7 State

| Part | State |
| --- | --- |
| The seam: `Knowledge`, `Syntax.knowledge`, `complete` reading it | Built |
| Half 1 of the parser | Built; the outline and its scan are gone. Both editors read it: the one release builds ship reads the names it completes and the struct names it colours from it |
| Files know each other | Built for the files of the running shader as the last build read them: completion, and `Scope.find` for Look up and the call in the strip |
| Look up for one's own names | Built, in project mode: the declaration, its comment, and the way to its line or its file |
| The call in the strip | Built, in project mode: the form that takes the arguments written so far, by their types; by their number where types do not tell |
| Half 2: bodies, scopes, types | Built: locals and parameters where they are seen, with scopes that belong to whole statements; the type of an expression when asked for; Look up on a local, members behind a `.`. Approximate where a conditional or a macro is in the way, and only what tolerates that asks it |
| Where a declaration is used | Built as an API, third shape: the text the driver gets, the preprocessor executed, certain by positive conditions, macros not expanded beyond numbers (the floor). No screen, and nothing applies a rename yet |
| The editor's reader and the preprocessor | The reader asks the shared preprocessor which arm is compiled; what an arm that is decided off declares ends with it |
| Rename | Not built |
