# The editor core

Status: accepted on 2026-11-12 with the decisions of section 11. Written for the owner to decide and for engineers to build from. Nothing of the editor itself is merged yet; the prototype behind the numbers is a throwaway branch (section 5).

## Summary

**Recommendation: build a small editor view owned by this app, on a document model that is an immutable tree of lines.** It lays out and draws only the lines on screen, colours them from a per-line restart of the existing `GlslLexer`, and takes keyboard input through its own `InputConnection`. No new dependency. About 4,000 production lines in about 18 small pull requests, the first seven of them pure Java with tests.

Why not something smaller: the platform `EditText` can be configured to type acceptably at 5,000 lines (7 ms a key on the owner's phone, growing with the text), but it cannot open, paste or undo large text fast, because it lays out the whole text on the main thread and has no way not to (section 2). Why not a library: the one real candidate is 45,000 lines under LGPL and would be the largest dependency of an app whose maintainer wants few (section 3).

What the prototype measured (section 5 has the method and every number):

| On the owner's phone, main thread | Prototype of the recommended design | Platform `EditText` in its best configuration, without colours | `EditText` hosted and coloured as the app does today, rebuilt in the test app |
| --- | --- | --- | --- |
| One keystroke at 500 / 5,000 / 50,000 lines | 1.1 to 1.6 / 0.5 to 1.5 / 0.4 to 1.4 ms | 4 / 7 / 34 ms | 2.4 s at 5,000 (the real app: 2.2 to 3.3 s, other agent) |
| Open 5,000 / 50,000 lines | 2 to 3 ms at both (the rest, 10 to 107 ms, in the background) | 0.15 s / 3.0 s | 1.1 s at 5,000 without the time to set the spans (the real app: 3.5 to 5 s) |
| Paste 5,000 lines | 2 to 12 ms | 140 to 240 ms | not measured |
| Undo that paste | 1 to 8 ms | 30 ms | not measured |

On the desktop JVM the document model costs 1 to 3 µs per keystroke at 5,000, 50,000 and 500,000 lines alike.

The riskiest part is the keyboard connection, not speed. Section 4.5 spells out its contract and traps. The prototype took text from Gboard through it, but Gboard never used composing text in these runs, so that half of the contract is untested (section 5.4). The plan keeps the old editor selectable until the new one has every behaviour of today's (section 6).

## 1. Requirements as testable numbers

The owner's words: a keystroke "must take milliseconds, not 10s, not 100s and definitely not 1000s of milliseconds, regardless of edit size", and "opening files and pasting large shaders should equally be fast".

**What "independent of size" means here.** For every user action, the work on the main thread is bounded by what is visible plus the size of the edit itself, plus a logarithm of the document size for finding the place. Nothing on the main thread may be proportional to the length of the document. Work that is proportional to the document by nature (reading a file, saving, handing the text to the compiler, analysing the whole file) happens off the main thread and never blocks typing or scrolling.

**How it is measured.** Main-thread time of an action: from the moment the input reaches the view (key event or `InputConnection` call) to the end of drawing the frame that shows the result. On a device this is the time inside the input call plus the frame's input, animation, layout, draw and sync stages as `FrameMetrics` reports them. The reference device is the owner's phone (Samsung SM-S938B, Android 17, 60 to 120 Hz). One frame at 120 Hz is 8.3 ms. The numbers are for a release build; a debuggable build is several times slower (section 5).

| # | Action | Criterion on the reference phone | Complexity on the main thread |
| --- | --- | --- | --- |
| R1 | Type or delete one character, Enter, Tab | Median ≤ 2 ms, 99th percentile ≤ 4 ms, at 500, 5,000 and 50,000 lines. The median at 50,000 lines is within 1.5 times the median at 500 | O(log n + visible lines) |
| R2 | Paste N characters | 5,000 lines (235 KB): ≤ 8 ms, one frame. 1 MB: ≤ 30 ms. The cost follows the pasted size, not the document size | O(N + log n + visible) |
| R3 | Open a file of N lines | Main thread ≤ 8 ms in total, at any size. Text on screen, coloured and editable ≤ 100 ms after the request for 5,000 lines and ≤ 300 ms for 50,000, storage time included for a local file | O(visible); the O(N) part is off the main thread |
| R4 | Scroll and fling | Every frame inside the display's frame time. Main thread ≤ 3 ms per frame at any size | O(visible) |
| R5 | Select all | ≤ 2 ms | O(visible) |
| R6 | Undo or redo a change of any size, for example a 5,000-line paste | ≤ 4 ms | O(log n + visible) |
| R7 | Idle | No frames and no main-thread work except the caret blink (≤ 1 ms twice a second) | O(visible) |
| R8 | Switch to another open file | ≤ 8 ms | O(visible) |
| R9 | Colours follow typing | The edited line is coloured correctly in the frame that shows the keystroke. Lines whose meaning changes at a distance (an opened block comment) are correct on screen in the same frame and everywhere within 100 ms at 5,000 lines and 1 s at 50,000 | O(visible) on the main thread, O(n) off it |
| R10 | Memory | At most 4 bytes per character of the file, plus the undo history, which is capped | |

**Up to what size.** I propose three bands and argue for them:

- **Up to 50,000 lines or 2.5 MB per file: every criterion holds as written.** That is ten times today's stress file. Real shaders are 50 to 3,000 lines; project mode splits code into several files, so files get smaller, not larger. A band of ten times the largest file anyone has shown is a margin, not a use case.
- **Up to 500,000 lines or 25 MB: typing, scrolling, pasting and undo stay as fast** (the cost per keystroke of the document model is flat to 500,000 lines, section 5.1). What grows is everything that is O(n) by nature: loading takes on the order of a second off the main thread, the whole-file analysis that feeds completion lags by about a second, memory is about 2 bytes per character, and the shader compiler has long since refused the file. This is where the design degrades instead of falling over.
- **Above an admission limit the editor does not open the file for editing** and says so. I recommend 16 MB (decision D7). A limit that is checked before the read is how the project layer already treats its other resources (SPEC 11).

**Long lines are the one known exception to "bounded by what is visible".** The unit of storage, tokenizing and layout is the line, so a keystroke costs time proportional to the length of the line it is in. Measured for the document model on the JVM: 8 µs in a line of 10,000 characters, 100 µs in a line of 1,000,000 (section 5.1). Tokenizing and measuring a line add roughly 10 ns per character (an estimate from the lexer's whole-text speed, not measured per line). So a minified 100,000-character line costs about a millisecond per keystroke, and a 5 MB file that is one single line is slow to edit (tens of milliseconds per key) but does not hang or crash. The stock widget has the same limit, because `DynamicLayout` reflows whole paragraphs.

## 2. Why the stock widget cannot meet them

Read from the API 36.1 framework sources in the local SDK (`android/widget/TextView.java`, `Editor.java`, `android/text/DynamicLayout.java`, `SpannableStringBuilder.java`, `Layout.java`). Line numbers refer to those files. The other agent's report (`.foreman/reviews/editor-large-file.md`) found the first three mechanisms by profiling; I confirmed them in the source and add the rest.

### What is proportional to the text, and whether configuration removes it

| # | Operation | Source | Cost | Can it be configured away? |
| --- | --- | --- | --- | --- |
| 1 | Every edit measures every line | `TextView.checkForResize` (11683) sets `sizeChanged` whenever the width is `wrap_content`, so every edit ends in `requestLayout()`; `onMeasure` (11313) then calls `desired()` (11246), which loops over all lines calling `getLineMax` | O(lines) text measuring per keystroke | **Yes.** Give the widget an exact width. It is `wrap_content` today only because it sits in a `HorizontalScrollView` |
| 2 | Every edit that adds or removes a line re-measures every line below it | `Editor.drawHardwareAccelerated` (2196) takes its line range from `canvas.getClipBounds` (`Layout.getLineRangeForDraw`, 1218). The `EditText` is as tall as the document because it sits in a `ScrollView`, so the range is the whole text. Every block from the first changed one on is marked `needsToBeShifted` (2244 to 2249), and with horizontal scrolling `drawHardwareAcceleratedInner` calls `getLineLeft` and `getLineRight` for every line of each such block (2317 to 2331) | O(lines below the edit) per keystroke | **Yes.** Make the `EditText` the size of the screen and let it scroll itself; the loop then stops at the last visible line (2260). Price: `TextView` has no fling of its own (its `Scroller`, 957, only serves programmatic scrolls), so the app writes one, and the padding problem that the layout file names as the reason for the `ScrollView` comes back |
| 3 | emoji2 reflows the whole text on every edit | `AppCompatEditText` wraps the text for EmojiCompat; `SpannableBuilder.endBatchEdit` reports `onTextChanged(0, length, length)` (the other agent read this in the emoji2 1.3.0 bytecode) | O(n) reflow per keystroke | **Yes.** `setEmojiCompatEnabled(false)`, or use the platform `EditText` |
| 4 | Every edit walks every span | `SpannableStringBuilder.change` (327) loops over all spans at 334 and again at 409, then `restoreInvariants` (1723); `sendToSpanWatchers` (602) loops over all spans; `moveGapTo` (169 to 215) loops over all spans whenever the caret jumps | O(spans) per keystroke. With one colour span per token that is O(n): 9,400 spans at 5,000 lines | **Mostly.** Colour only the visible lines. Then every scroll adds and removes spans, each of which invalidates the display list of its text block |
| 5 | With an accessibility service enabled, every edit copies the whole text | `TextView.ChangeWatcher.beforeTextChanged` (16575): `mBeforeText = mTransformed.toString()` | O(n) copy per keystroke; about 0.1 ms per 100 KB | **No.** The field and the watcher are private. Any enabled accessibility service triggers it, not only TalkBack |
| 6 | An input method that monitors extracted text gets the whole text on every change | `Editor.extractTextInternal` (1989 to 2030) | O(n) copy plus a binder transfer | **No**, it is the keyboard's choice. `flagNoExtractUi` (set today) avoids the full-screen mode, not monitoring |
| 7 | **Opening lays out the whole text on the main thread** | `setText` drops the layout; the next measure builds a `DynamicLayout`, whose constructor calls `reflow(mBase, 0, 0, length)` (618): a `StaticLayout` pass over every paragraph | O(n) with a constant of tens of microseconds per line, synchronous, before the first frame | **No.** There is no lazy and no background path for an editable layout. `PrecomputedText` is for text that is not edited |
| 8 | The same full pass on every new layout | `nullLayouts`/`makeNewLayout` (10942) after a change of font, text size or tab width, and after the activity is recreated (rotation) | As 7 | **No** |
| 9 | Pasting or undoing N lines lays out N lines | `DynamicLayout.reflow` (640) for the changed range | O(N) with the constant of 7. Allowed by "the size of the edit", but a 5,000-line paste costs what opening 5,000 lines costs | **No** |
| 10 | Saving state copies the whole text into the bundle | `EditText.getFreezesText()` returns true; `TextView.onSaveInstanceState` (7059) copies the text. The other agent got a `TransactionTooLargeException` leaving the app with 235 KB | O(n), and a crash above about 500 KB | **Yes.** `setSaveEnabled(false)` and keep the text elsewhere |
| 11 | An autofill service is sent the whole text when the field gains focus | `View.notifyEnterOrExitForAutoFillIfNeeded` → `AutofillManager.startSessionLocked`. My stock comparison crashed on the owner's phone with `TransactionTooLargeException` (594,592 bytes for 235 KB of text) until it did what the app's editor does | O(n) and a crash | **Yes,** and the app does: `getAutofillType()` returns `AUTOFILL_TYPE_NONE` |

Two things are local and fine: `DynamicLayout.reflow` handles an ordinary edit paragraph by paragraph, and its bookkeeping per edit is a scan over block arrays (`updateBlocks`, 958) with one entry per 400 characters, which is microseconds even at megabytes.

### What tuning can and cannot achieve

**Typing can be made usable on the stock widget, but not flat.** With rows 1 to 4, 10 and 11 fixed (exact size, self-scrolling, no emoji2, colours only on visible lines, no saved text) the per-keystroke work that remains is the edited paragraph plus the O(n) terms of rows 5 and 6. I measured the best case of that configuration: a platform `EditText` the size of the screen, scrolling itself, with no spans at all.

| Platform `EditText`, best case, on the owner's phone | 500 lines | 5,002 lines | 50,021 lines |
| --- | --- | --- | --- |
| One keystroke, main thread | 3.7 to 4.3 ms | 6.7 to 7.3 ms | 34 ms |
| Open, main thread | 25 to 31 ms | 144 to 165 ms | 3.0 s |
| Paste 235 KB | 185 to 245 ms | 140 to 235 ms | not run |

So a keystroke is single-digit milliseconds at 5,000 lines: about 300 times better than today, and good enough to type on. It is not flat: it grows with the text, to 34 ms at 50,000 lines, and it is measured without any colour (one span per token on the same configuration costs 66 ms per key at 5,000 lines, so the colouring would have to be windowed as well). On the emulator, which has no accessibility, autofill or content-capture service, the same configuration costs 1.4 ms per key at 5,000 lines; the phone has all three, which fits rows 5 and 6. I did not profile the difference. Section 5.3 has the method and the rows in between.

**Opening, large pastes, large undos and layout changes cannot be fixed**, because rows 7 to 9 are how `DynamicLayout` works: 0.15 s to open 5,000 lines and 3 s to open 50,000 in the best case, a quarter of a second for a 5,000-line paste. That is the owner's second sentence ("opening files and pasting large shaders should equally be fast"), and no setting reaches it.

**The cost of the tuned stock widget is not small either.** It needs: a fling and scroll bars written by the app, a way to keep padding without the `ScrollView`, span windowing that follows scrolling, and an `EditText` whose text is no longer the truth for a file of project mode (per-file undo, drafts and file switching still need a document outside the widget, and a file switch is a `setText`, row 7). It keeps the stock widget's per-keystroke copies (rows 5 and 6) and its freeze on open. It would be a third round of patching the same widget, and it leaves the app exactly where the owner does not want it: suffering from a decision made long ago.

**Being fair to the two fixes that landed.** #146 (no redraw loop) and #147 (tab stops) are right and worth upstreaming whatever happens to this proposal: they remove a 100 % busy main thread at every file size and make small files pleasant. The other agent's phone numbers after both: about 0.1 to 0.2 s of main-thread work per key at 534 lines, 0.3 to 0.4 s at 1,050, more than 2 s at 5,002, and 3.5 to 5 s to open 5,002 lines. Rows 1 to 3 explain what is left for typing; row 7 and the colour spans explain opening.

## 3. The options

| | (a) Keep `EditText`, tune | (b) `EditText` over a window of the document | (c) An editor view owned by this app | (d) A third-party editor widget | (e) A web editor in a `WebView` |
| --- | --- | --- | --- | --- | --- |
| Typing at 5,000 lines | 7 ms at best on the owner's phone, growing with the text (section 2) | A few milliseconds if the window is about 200 lines | 0.5 to 1.5 ms, flat (measured) | Fast (designed for it) | Fast |
| Open, paste, undo of large text | No: full layout on the main thread | Open yes; paste and undo inside the window only | Yes (measured) | Yes | Yes |
| Keyboard input | The platform's, complete | The platform's, but restarted whenever the window moves | Ours to write and test: the main risk | The library's own | Chromium's, very well tested |
| Selection, handles, magnifier, accessibility | The platform's | Break at the window's edges; select all needs a fake | Ours to write | The library's own | The browser's |
| New code | About 400 lines, plus a document for project mode anyway | About 1,500 lines of the hardest kind | About 4,000 lines | Glue of about 800 lines, plus 45,000 lines we do not own | Bridge and glue of about 1,000 lines, plus a JavaScript bundle |
| Dependencies | None | None | None | One, large | A bundler in the build, or a minified blob in the repository |
| Upstream story | Easy: small fixes | Hard to defend | Large but in small pure steps; no dependency | A hard sell to a maintainer who wants few dependencies | A very hard sell |

**(a) Keep `EditText` and tune.** Covered in section 2. Cheapest, and right for upstream as bug fixes. It cannot meet R1 at 50,000 lines, nor R2, R3, R6 and R8 at any large size, and the per-keystroke copies of rows 5 and 6 remain. If the owner decides that typing at today's file sizes is all that matters, and that opening may take 0.15 s at 5,000 lines and 3 s at 50,000 at best, this is the answer and section 4 is not needed.

**(b) Keep `EditText` but show it only a window of the document.** The widget holds some 200 lines around the screen; the app swaps text in and out as the user scrolls. Costs and risks: the keyboard sees offsets of the window, so every move of the window is a `restartInput` that drops composing text and makes keyboards flicker; a selection cannot extend past the window, so select all, long drags and "delete from here to the end" need a second, fake implementation; the scroll bar and scroll range must be faked with spacers; undo has to live outside the widget and fight the widget's own. It gets the speed with the largest number of special cases, and each of them is a place where text can be lost. I would not build it.

**(c) A purpose-built editor view.** Section 4. It costs the most code and takes over three things the platform gives for free today: the keyboard connection, touch selection with handles and magnifier, and accessibility. In return every criterion of section 1 is met by construction, the document becomes the place where project mode keeps per-file text and undo, and nothing is added to the dependency list. The app's needs are narrow (one language, monospace fonts, no line wrap, no folding), which is what makes an own editor tractable here when it would not be for a general-purpose one.

**(d) An existing open-source editor widget.** Checked today, 2026-11-12, against GitHub's API and Maven Central unless marked "from memory":

| Candidate | Licence | State | Size | What it is |
| --- | --- | --- | --- | --- |
| Sora Editor (`Rosemoe/sora-editor`, `io.github.rosemoe:editor`) | LGPL-2.1 | Active: last push 2026-11-10, release 0.24.7 in October 2026, 1,460 stars, 61 open issues | The `editor` artifact is 798 KB; its module has 294 source files, 1.8 MB of source, roughly 45,000 lines by my estimate from the byte count. Depends on `androidx.annotation`, `androidx.collection` and the Kotlin standard library | A complete code editor view with its own text storage, layout of visible lines, selection handles, magnifier, completion window and `EditorInputConnection extends BaseInputConnection`. From memory: used by several Android IDE apps; highlighting comes through its own `Language` interface, with TextMate and tree-sitter modules as separate artifacts |
| CodeView (`AmrDeveloper/CodeView`) | MIT | Last push December 2023 | Small | From memory: a subclass of the platform's auto-complete `EditText` with regex highlighting. It inherits everything in section 2, so it solves nothing here |
| The editor of Squircle CE (`massivemadness/Squircle-CE`) | Apache-2.0 | Active | Part of an app, not a maintained library on its own | From memory: also built on `EditText`, with highlighting limited to the visible region. Same floor as option (a) |

So there is one real candidate. Adopting Sora Editor means: the app's largest dependency, under a licence stricter than the app's own MIT; the Kotlin standard library as a declared dependency; its look and behaviour for handles, completion and scrolling instead of the platform's; `GlslLexer`, the completion strip, error marks, undo per file and drafts adapted to its interfaces; and every keyboard bug fixed on its schedule or in a fork of 45,000 lines. What it saves is real: its keyboard connection and touch selection have years of bug reports behind them. I did not build against it, so its behaviour with this app's keyboards is not verified. I would choose it only if section 8's abandon conditions are met.

**(e) Anything else.**

- **A web editor (CodeMirror 6) in a `WebView`.** Technically strong: it draws only the viewport, and the keyboard goes through Chromium's text input, the best-tested path on Android after `TextView`. Against it: a JavaScript toolchain in the build (F-Droid builds from source) or a minified bundle in the repository; a bridge between Java and the page for every edit, diagnostic and completion; tens of megabytes of memory for the renderer process next to a GL preview; and a maintainer who would have to accept a web app inside his Android app. From memory, no part of this was built.
- **Jetpack Compose's text field.** It also lays out the whole text, and it would add Compose to an app that deliberately has none.
- **One `EditText` per paragraph in a list.** Selection and the keyboard cannot cross items.

**Recommendation: (c), with (a)'s two landed fixes offered upstream now.** The requirement includes opening and pasting, which rules out (a) and (b) on mechanism, not on taste. Between (c) and (d) the deciding facts are the upstream story and long-term ownership: (c) adds no dependency and arrives as small pure-Java pieces a maintainer can read; (d) is quicker to a first result and permanently larger than the app's own editor code would be.

## 4. The design

### 4.0 The modules

Seven modules. Each has a small interface and hides one decision. The first four have no Android imports and are tested on the JVM.

| Module | Where | Interface, in a line | Hides |
| --- | --- | --- | --- |
| `Text` | `editor/`, pure Java | An immutable text: `length`, `lineCount`, `line(n)`, `lineStart(n)`, `lineOf(offset)`, `replace(from, to, text)`, `slice`, `substring` | How text is stored and how lines are found |
| `Document` | `editor/`, pure Java | One file being edited: `text()`, `revision()`, `selection`, `replace(...)`, `batch`, `undo`, `redo`, `isModified`, change listeners | Undo and its grouping, how positions survive edits, cleaning of incoming text |
| `LineStyles` | `editor/`, pure Java | `runs(line)`: the colour runs of a line, always for the current text | Per-line lexer state, incremental re-tokenizing, what is done now and what later |
| `TextInput` | `editor/`, pure Java | The operations a keyboard may ask for (`commit`, `setComposing`, `deleteSurrounding`, ...) applied to a `Document` | The composing region and every rule of the keyboard contract |
| `Analysis` | `editor/`, pure Java plus one executor | For a `Text`: tokens, outline, names for completion, insertion points; delivered for a revision | That whole-file work runs on another thread, and when |
| `CodeView` | `widget/`, Android | A view: `setDocument(document, viewState)`, `setLineStyles`, `setLineMarks`, appearance setters | Layout of a line, drawing, scrolling, touch, handles, keys, the `InputConnection`, accessibility |
| GLSL glue | `fragment/EditorFragment` and a small controller | What `EditorFragment` offers today | Auto-indent rules, completion, error marks, the update delay, the ShaderToy check, Add uniform |

`CodeView` does not know GLSL. It asks `LineStyles` for the colour runs of a line number and a `LineMarks` object for the lines to mark. `Document` does not know about views: a file that is not on screen still has a document that can be edited (Add uniform, Convert) and undone.

### 4.1 The document model

**Storage: an immutable balanced tree of lines.** A leaf holds up to 32 lines as strings; an inner node holds up to about 32 children and knows, for its subtree, the number of characters and the number of lines. An edit builds a new tree that shares every untouched node with the old one: a keystroke copies one leaf and the three or four nodes above it. This is the document structure of CodeMirror 6, which has carried that editor through documents of millions of lines.

Why this and not the alternatives:

| | Gap buffer | Piece table | Tree of lines (chosen) |
| --- | --- | --- | --- |
| Type at the caret | Fastest: one array write | Fast | A few microseconds (measured: 1 to 3 µs, flat from 5,000 to 500,000 lines) |
| Type somewhere else | Moves the gap: O(distance) | Fast | The same few microseconds |
| Find line n, or the line of an offset | Needs a separate line index, which shifts on every edit (measured with a plain index: 35 µs at 50,000 lines, 380 µs at 500,000) or a cleverer one | Needs line counts in a tree over the pieces | Built in: both are one descent, 0.1 to 0.4 µs |
| A copy another thread may read | Copy the whole text: 1 ms at 235 KB, 10 ms at 23 MB, per copy, on the main thread | Cheap only if the piece list is itself a persistent tree | Free: the text is immutable, hand over the reference |
| Undo of a large change | Stores the removed characters: O(size) | Cheap | Stores the removed subtree: O(log n) time and memory |
| Code | About 150 lines plus the line index | 1,000 lines or more to do well | About 400 lines (the prototype has it) |

The deciding property is the cheap copy. The lexer for the lines below the screen, the outline, the draft journal, saving and the compiler all need the whole text off the main thread. With a mutable buffer every one of them starts with an O(n) copy on the main thread or with a lock; with an immutable text there is nothing to copy and nothing to lock. It also makes loading safe to do in the background (a text under construction is not visible to anyone), and it turns undo of a 5,000-line paste into putting a subtree back.

The price: more allocation per keystroke than a gap buffer (a new leaf array and a few small objects, a few hundred bytes), and a cost per keystroke proportional to the length of the edited line, because a line is one immutable string (section 1, long lines). On Android 8 and later, strings of ASCII text take one byte per character; the measured total is about 2 bytes per character including the tree.

**Lines.** A line ends at `\n`. Line endings are normalized where text enters the document, the same rule `SourceText.decode` applies to files: `\r\n` and a lone `\r` become `\n`. Today's editor also replaces no-break spaces (U+00A0) pasted from other apps; that moves to the same place. So the model never holds a `\r`, and "line n" means the same to the editor, the lexer, the compiler's diagnostics and the user.

**Positions.** A position is a UTF-16 offset into the text, which is what `String`, the keyboard contract and `Tokens` use. Line and column are derived on demand in O(log n). There are deliberately no general "markers" that live in the text. The few positions that must survive edits are held by their owners and moved by one function, `Change.map(offset)`: the selection and the composing region (owned by `Document` and `TextInput`), undo records, and line marks (which track lines, below).

**The unit of an edit** is one replace: `replace(from, to, text)`. It produces a `Change(start, removed, inserted)`, where `removed` and `inserted` are themselves `Text` values, so a change is cheap to keep however large it is. Each change increments the document's `revision`. Several replaces between `beginBatch` and `endBatch` are one undo step and end in one notification; the keyboard's batch edits map onto this directly.

**Change events.** Listeners are called on the main thread after each change with the change and the text before it. The line range a change touches follows from it without a search: first line `before.lineOf(start)`, line breaks removed `removed.lineCount() - 1`, line breaks inserted `inserted.lineCount() - 1`. That is all `LineStyles`, the line marks and the view need to update themselves.

### 4.2 Undo and redo

Undo is a property of the document, so every file has its own history and it survives the view being destroyed.

- A step is a list of changes plus the selection before and after. Undo applies the inverse changes in reverse order and restores the selection.
- **Typing is grouped.** A typed character joins the previous step if that step is typing too, the caret is where that step left it, and less than a second has passed. Moving the caret, pasting, a completion or a batch ends the group. Today every text change is its own step, so undoing a word takes one tap per letter; this is a visible change for the better.
- The history is capped (1,000 steps), as today's can be.
- The document remembers which step was current when the file was last saved, so "modified" becomes false again when the user undoes back to the saved state.
- Not persisted: after the process dies, the text comes back from the draft journal and the history is empty. That is today's behaviour too (`ShaderEditorApp.editHistory` lives in the process).
- One history, not two. Today the app's `UndoRedo` and `TextView`'s built-in undo (Ctrl+Z on a hardware keyboard) both exist and do not know of each other.

### 4.3 Tokens and colour

**Colour is a function of a line's text and the lexer state at its start.** `GlslLexer` gets a second entry point next to `lex(text)`:

```java
/** Tokenizes one line that starts in state; returns the state of the next line. */
static int lexLine(CharSequence line, boolean breakFollows, int state, LineTokens out)
```

**What a line has to carry.** I read the lexer's fields for what survives a line end. It is six bits:

| Bits | Meaning | Example |
| --- | --- | --- |
| 2 | Inside a block comment; or inside a `//` comment continued by a backslash | A `/*` opened 4,000 lines up |
| 1 | Inside a directive that a backslash continues | A multi-line `#define` |
| 1 | The next word is a directive name | `#` then a continuation, rare |
| 2 | What the last code token was (nothing special, `.`, `struct`, a name), for the lexer's guess of field selections and type names | `struct` at the end of a line, the name on the next |

So one byte per line. `LineStyles` keeps the end state of every line in a gap buffer of bytes. In the prototype `lexLine` reuses the lexer's existing code unchanged, by presenting the line with its line break; on the 5,002-line test file its tokens are identical to those of `lex(text)` for every character.

One rule cannot hold line by line: the lexer turns a name into a type when another name follows it, and it does that retroactively. When the two names are on different lines, a per-line lexer cannot repaint the earlier line. The clean answer is to stop the guess at the line end in both entry points, as a small visible change, and let the outline colour declared type names everywhere, which it already does. The prototype guards the case; the test file has no instance.

**After an edit.** `LineStyles` tokenizes the changed lines again, then continues downwards for as long as a line ends in a different state than it did before, and stops at the first line whose end state is unchanged. For ordinary typing that is the edited line and nothing else: 1 µs on the desktop JVM, text change included. The view asks for the runs of the lines it draws; the answer comes from a small cache keyed by the line's string and start state (strings are immutable, so identity is a sound key).

**A block comment opened 4,000 lines up.** Typing `/*` changes the end state of that line, so the walk continues. It is given a budget: the lines on screen plus a margin, 200 lines, 25 to 45 µs on the desktop JVM. Everything on screen is therefore correct in the same frame. Lines below the budget are marked stale from a "dirty from" line. A background task then continues the walk on the immutable text and delivers the new states for the revision it worked on (measured for the rest of the file, cold: 1 ms at 5,000 lines, 13 ms at 50,000, 0.2 s at 500,000). If the user scrolls into the stale part first, those lines are drawn with their old states for a few frames and repainted when the result arrives. Nothing waits. The same budget applies to a large paste: the pasted lines on screen are tokenized now, the rest in the background.

**Outline, completion, diagnostics.** These attach to the document, not to the view:

- **`Analysis`** runs on one background thread. A short time after the last change (50 ms, not the one-second update delay) it takes the current `Text` and computes what needs the whole file: `GlslLexer.lex`, `Outline.of`, and from them the declared names, the declared type names and the insertion points for Add uniform. The result carries the revision it was made for.
- **Declared type names** reach colour as a set of names, not as positions: `LineStyles` paints an identifier as a type if its text is in the set. Names stay valid while the user types, positions would not.
- **Completion** is computed on the main thread at once, from the tokens of the caret's line (always current) and the names of the last analysis (possibly one keystroke old). Today completions wait for a tokenization of the whole file.
- **Diagnostics** carry a file and a line. `LineMarks` holds them per document and moves them when lines are inserted or removed above them, which is what the background-colour spans do implicitly today. The view draws a mark as a background over the whole line.
- **The seam for other file types** stays the one LANGUAGE.md proposed, with one method added: a `Syntax` offers `lex(text)`, `lexLine(...)` and `complete(...)`. A file that is not GLSL gets a syntax that returns no tokens.

The existing whole-text types (`Tokens`, `Outline`, `GlslCompletion`) stay as they are. `TokenDiff` and `EditedRange`, which exist to repair colour spans, are no longer needed and go with the old widget.

### 4.4 Layout and drawing

**No line wrapping; horizontal scrolling.** This is what the editor does today, so nothing changes for users. It is also the right default for code on a phone: indentation carries meaning, wrapped code hides it, and people who write shaders on a phone keep lines short or scroll. It makes layout trivial and exact: every line has the same height, so line n is at `n × lineHeight` and the line at a y position is a division. Soft wrap is not built. It could be added later without changing the modules (it needs a per-line count of visual rows, summed in the same tree as the line counts), and it would roughly double the layout code, so it should wait for someone to ask (decision D3).

**One line, one layout.** `LineLayout` is the unit: for a line's text and colour runs it answers `xOf(column)`, `columnAt(x)`, `width()` and `draw(canvas)`. It has two implementations behind that interface:

- **The fast path** for a line that contains only printable ASCII and tabs, in a font whose ASCII glyphs all have one width. Every font the app ships is such a font. Positions are arithmetic: column times character width. Runs of one colour are drawn with one `drawText` each.
- **The platform path** for every other line: a `StaticLayout` of that single line, with colour spans. The framework then does what it does in `EditText`: font fallback, emoji, combining characters, right-to-left runs, and caret positions by grapheme cluster (`getPrimaryHorizontal`, `getOffsetForHorizontal`, `getOffsetToLeftOf`).

| Kind of text | Supported | How |
| --- | --- | --- |
| ASCII in a monospace font | Yes, fast path | Arithmetic |
| Tabs | Yes | Each tab is the tab width, as today (decision D4 offers real tab stops) |
| Accented Latin, Cyrillic, Greek, CJK, emoji, combining marks | Yes, platform path | Per-line `StaticLayout`; the caret moves by whole clusters |
| Right-to-left text inside a line (an Arabic comment) | Displayed and editable through the platform path, in a left-to-right paragraph | The platform's bidi algorithm per line. Selection across mixed-direction text is drawn as the platform computes it |
| Right-to-left paragraph direction, vertical text | Out of scope | The paragraph direction is always left to right; code is |
| Ligatures (the "ligatures" setting) | Yes | A run of one colour is drawn in one call, so `!=` in one colour still forms its ligature |
| A proportional font | Works through the platform path for every line | Slower; no such font ships |
| Lines longer than 10,000 characters | Drawn without colours, only the visible columns on the fast path | Fails safe: slower in that line, never a hang |

**Drawing only what is visible, and caching it.** `onDraw` computes the first and last visible line from the scroll position and draws those. My first version drew every visible line's text on every frame; on the phone that cost about 5 ms per frame for some 75 lines, because each `drawText` shapes text again. That fails R1 and R4, so the design changed: **each visible line's drawing is recorded once into a `RenderNode` and replayed** until the line's string or start state changes. Typing re-records one line; scrolling records only the lines that come into view. The cache finds a line by the identity of its string, not by its number, so a line that only moved (Enter above it) or came back (undo, scrolling back) is not recorded again. Measured on the phone: 0.2 to 1.0 ms per `onDraw` instead of 5 ms (section 5.3). `RenderNode` is public from Android 10 (API 29). On Android 6 to 9 the view draws directly, as the first version did. Those devices have 60 Hz screens and a 16.7 ms frame; the direct path costs 5.5 ms per frame on the reference phone and was not measured on an old device (section 8).

**The rest of a frame.** All of these are drawn directly, for visible lines only:

- **Gutter:** line numbers, right-aligned, in a strip that stays in place when the text scrolls sideways (today the numbers scroll away with the text, because they are part of the `EditText`; decision D5). Its width follows the digit count of the line count.
- **Line marks:** a background in the error or warning colour over the whole line, as today.
- **Selection:** one rectangle per visible selected line; on the platform path the shape the platform computes.
- **Composing text:** an underline.
- **Caret:** a rectangle, blinking twice a second, and it is the only thing that redraws when idle.

**Scrolling.** The view is the size of its place on screen and scrolls itself with the `View` scroll offsets, an `OverScroller` for flings and the standard scroll bars. The vertical range is `lineCount × lineHeight` plus room below the last line. The horizontal range is the widest line laid out so far, never less than the tree's longest line in characters times the character width (the tree can keep the longest line length per node; the prototype does). Edge effects are standard `EdgeEffect`s.

### 4.5 Input

Everything the user does becomes one of a small set of commands on the `Document` (insert, delete, move, select, undo). The commands and the movement arithmetic (next word, line start, page) are pure functions and are tested on the JVM. The view only translates events into commands.

**Touch.**

| Gesture | Behaviour | Built with |
| --- | --- | --- |
| Tap | Place the caret, show the keyboard, show the insertion handle | `GestureDetector`; hit test is a division for the line and `LineLayout.columnAt` |
| Double tap, long press | Select the word, show the two handles and the floating toolbar | `startActionMode(callback, ActionMode.TYPE_FLOATING)`, available from API 23 |
| Drag after long press | Extend the selection by words | |
| Drag a handle | Move that end of the selection; scroll when near an edge | The platform's handle drawables (`textSelectHandleLeft`, `textSelectHandleRight`, `textSelectHandle`), drawn inside the view |
| Magnifier | While a handle is dragged | `android.widget.Magnifier`, API 28; none before, as in `EditText` |
| Scroll, fling | Both directions, locked to the dominant axis | `OverScroller`, `VelocityTracker` |
| Mouse | Click, drag to select, wheel to scroll, right click for the toolbar | `onGenericMotionEvent`; needed for desktop mode |

**Hardware keyboard.** Arrows, Home, End, Page Up and Down, with Shift for selection and Ctrl for words and document ends; Backspace and Delete, with Ctrl for words; Enter with auto-indent; Tab (inserts a tab when the "tab for indent" setting is on, else moves focus, as today); Ctrl+A, C, X, V, Z, Shift+Z and Y. Keys the view does not handle go to the activity as they do now.

**Clipboard.** Copy and cut put plain text on the clipboard. Paste takes the clip as plain text, normalizes line ends and inserts it as one change and one undo step. Copying more than about 500,000 characters cannot work on Android (the clipboard goes through a binder transaction of about 1 MB, the same limit that crashes the stock widget's saved state); the view catches the failure and says so instead of crashing.

**The input method connection.** This is where home-grown editors fail, so here is the whole contract as this design implements it.

*What the view declares* in `onCreateInputConnection`:

- `inputType`: with "hide native suggestions" on, which is the default, `TYPE_NULL`, exactly as today. Keyboards then keep no composing text and send plain key events or one `commitText` per key. With the setting off, `TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_MULTI_LINE | TYPE_TEXT_FLAG_NO_SUGGESTIONS`, as the layout file says today.
- `imeOptions`: `IME_FLAG_NO_EXTRACT_UI | IME_FLAG_NO_FULLSCREEN | IME_FLAG_NO_ENTER_ACTION`.
- `initialSelStart` and `initialSelEnd` from the document. Left at their defaults, keyboards assume the caret is at 0 and corrupt their own model of the text.
- `initialCapsMode = 0`, and from API 30 `setInitialSurroundingText` with a window of text around the caret.
- `onCheckIsTextEditor()` returns true, or the keyboard will not show for the view.

*The model.* `TextInput` holds the composing region (a range, moved through every change with `Change.map`), the batch depth, and nothing else. It is pure Java. The Android class is a thin shell that extends `BaseInputConnection` for the few things that class does without an `Editable` (below) and forwards everything else.

| Call | What it must do | Trap |
| --- | --- | --- |
| `commitText(text, pos)` | Replace the composing region if there is one, else the selection; clear the composing region; place the caret | `pos` above 0 counts from the end of the new text (1 means right behind it); 0 or less counts back from its start. Paste from the keyboard's clipboard arrives here with large text |
| `setComposingText(text, pos)` | The same replace, but the new text becomes the composing region | Voice input and glide typing replace the region again and again; each replace is one change in one typing undo group |
| `setComposingRegion(start, end)` | Mark existing text as composing, changing nothing | Sent when the caret lands in a word. The next `setComposingText` replaces that whole word. An editor that ignores this call duplicates text. Arguments may be reversed or out of range: clamp |
| `finishComposingText()` | Keep the text, forget the region | Called at any time, also on a connection that is no longer current; must never fail |
| `deleteSurroundingText(before, after)` | Delete around the selection, not the selection | Counted in UTF-16 units; must not split a surrogate pair. `deleteSurroundingTextInCodePoints` counts code points |
| `setSelection(start, end)` | Move the selection | Used by cursor-swipe on the space bar, many times a second |
| `getSurroundingText(before, after)`, `getTextBeforeCursor(n)`, `getTextAfterCursor(n)`, `getSelectedText`, `takeSnapshot` | Return text around the selection. `getSurroundingText` is what a current Gboard asks first (seen in the prototype); without an `Editable`, `BaseInputConnection` answers it with `null` | The keyboard blocks on these calls (with a timeout), so the main thread must be free: one more reason for section 1. `n` can be huge: answer with at most a few thousand characters. Returning an empty string before the caret makes some keyboards send a key event for Backspace instead of `deleteSurroundingText`; both must work |
| `getExtractedText(request, flags)` | A window of text around the caret with `startOffset` set, never the whole document | The stock widget returns everything, which is row 6 of section 2. If the monitor flag is set, send `updateExtractedText` on changes |
| `beginBatchEdit`, `endBatchEdit` | Count nesting; open and close a document batch | Autocorrect is a batch of delete plus commit. Notify the keyboard and redraw once, at the outermost end. `endBatchEdit` returns whether a batch is still open |
| `performContextMenuAction(id)` | Select all, cut, copy, paste, undo, redo | The clipboard and undo buttons on keyboards call this |
| `performEditorAction`, `sendKeyEvent` | Enter and Backspace often arrive as key events, and with `TYPE_NULL` every key does. Seen in the prototype: Gboard sends Backspace as a key event even in ordinary text mode | They go through the same commands as a hardware key |
| `getCursorCapsMode` | 0 | No automatic capitals in code |
| `commitCompletion`, `commitCorrection`, `commitContent`, `requestCursorUpdates`, handwriting gestures | Declined (`false`) | Deferred. Cursor anchor updates matter for floating candidate windows of some CJK keyboards |
| `closeConnection()` | End an open batch, finish composing | |

*What the view owes the keyboard.*

- After every change of the selection or the composing region, whoever caused it, once per batch: `InputMethodManager.updateSelection(view, selStart, selEnd, composingStart, composingEnd)`. A keyboard whose idea of the caret differs from the editor's deletes or duplicates text. This is the most common bug in custom editors.
- When the text changes under the keyboard without its doing (undo, a completion, Add uniform, a conversion): finish composing in the model first, then `updateSelection`. When the whole text is replaced (another file): `restartInput(view)`.
- When the user moves the caret by touch while text is composing: keep the composing region and report the new selection. The keyboard decides to finish composing; the editor does not guess.
- Positions are offsets into the whole document. The tree turns them into lines in O(log n), so there is no window of offsets to keep in step with the keyboard.

*What `BaseInputConnection` gives for free, and what it does not.* For free: `sendKeyEvent` (dispatches the event to the view through the proper channel), `performEditorAction` (turns Enter into key events), meta key state, the default answers of the optional calls. Not usable: every text method, because `BaseInputConnection` implements them against an `Editable` (its `getEditable()`), and returns `false` or `null` without one. So the text methods are ours, about fifteen of them, ten lines each over `TextInput`. I considered the alternative of giving `BaseInputConnection` an `Editable` facade over the document and letting it do the work. It was rejected because its behaviour would then live in framework code that differs across API 23 to 37 and cannot run in a JVM test, and because the facade needs a faithful span table (the selection and the composing region are spans there). It stays the fallback if our own implementation proves unreliable (section 8).

*Keyboards.* From memory, not verified here except where section 5.4 says so:

- **Gboard**, from memory: with `TYPE_TEXT_FLAG_NO_SUGGESTIONS` it commits character by character and does not compose; with suggestions on it composes word by word, sends `setComposingRegion` when the caret enters a word, and resynchronizes by reading the text when `updateSelection` surprises it; with `TYPE_NULL` it sends key events. **Observed** (section 5.4): the first and the last hold. The Gboard on the owner's phone and on the emulator did not compose in a multi-line field at all, not even with suggestions allowed, and not in a platform `EditText` either.
- **Samsung Keyboard** is known to keep predicting when only `NO_SUGGESTIONS` is set, so it composes more often than Gboard does, and it asks for extracted text. `TYPE_NULL`, today's default, turns that off.
- **Voice input** streams partial results as composing text and commits at the end.

The owner's phone uses Gboard as its keyboard (read from its settings, not changed), so Samsung Keyboard has to be tested on another device or by the owner (section 6).

*How it is tested.* `TextInput` is pure, so every rule above is a JVM test. The prototype logs each call a keyboard makes; such logs become replayable tests ("this sequence from Gboard must produce this text"). That is the piece of test infrastructure worth having here, and it is a list of calls, not a framework.

### 4.6 Accessibility

`EditText` gives TalkBack everything for free, up to 100,000 characters (it trims what it reports, `TextUtils.trimToParcelableSize`). A custom view gives nothing until it is written. The minimum that makes the editor usable with TalkBack, planned before the switch:

- Report as an editable, multi-line text field (`getAccessibilityClassName` returns `android.widget.EditText`).
- The node's text is a window: the visible lines, with the selection reported relative to it. Reporting the whole document is neither possible (binder size) nor what the stock widget does for large text.
- Events: text changed (so typed characters are spoken), selection changed, and "traversed at movement granularity".
- Actions: move by character, word, line and page; set selection; cut, copy, paste; scroll.

Deferred: one virtual node per line for explore-by-touch, and anything for braille displays and Switch Access beyond what the above gives. This is the one area where the new editor will at first be worse than the old for some users, and it cannot be avoided cleanly (decision D6).

### 4.7 Loading, saving and paste

- **Load:** a background thread reads the bytes, decodes them with `SourceText.decode`, builds the `Text` and the line states, and posts the finished values to the main thread, which wraps them in a `Document`. The main thread's share is setting a field and drawing one frame. Measured on the phone: the main thread's share is 2.0 to 2.7 ms at 5,000 and at 50,000 lines.
- **Save, drafts, compile:** each takes the document's current `Text` (a reference) and does its O(n) work on its own thread: joining to a string, encoding, stripping trailing white space for the saved copy as `getCleanText` does today.
- **Paste:** splitting the clip into lines is O(pasted size): 0.7 to 8.6 ms for 235 KB on the phone. For a paste from the clipboard that work, and reading the clip, go to a background thread, and the main thread does one replace when the text is ready, a frame or two later. Text that a keyboard commits arrives on the main thread and is inserted there; that is "the size of the edit". Tokenizing pasted lines beyond the budget goes to the background, so the end of a large paste can be without colours for a few frames.

### 4.8 Threading

The main thread owns every `Document`, `LineStyles`, `TextInput` and view; nothing else reads or writes them. **The single rule: only immutable values cross threads.** A background task receives a `Text` and the revision it belongs to, and returns an immutable result tagged with that revision; the main thread uses the result if the document is still at that revision, uses the part above the first later edit where that is well defined (line states), or drops it. There are no locks and no shared mutable state, so there is nothing to get wrong by inspection, which is what the old tokenizer hand-off was patched five times for (LANGUAGE.md).

### 4.9 Project mode

- **One document per file.** The editing session keeps a map from project path to `Document`, created when a file is first shown or edited. Text, selection, undo history and scroll position are per document, as UX 6 asks.
- **Switching files** is `CodeView.setDocument(document, viewState)` plus `restartInput`. The line states of a document are kept with it, so a switch costs one frame.
- **Drafts.** After a change the session hands the current `Text` to its own thread a moment later; that thread calls `EditingSession.setDraft(path, text.toString(), base)`, which is already written to be used off the main thread. The unsaved dot is `Document.isModified()`.
- **Process death.** The journal restores the text of every draft; documents are rebuilt from it; histories start empty; the caret and scroll position of the active file come from the saved instance state, which is now a few integers instead of the text.
- **Diagnostics** arrive with file and line and go to the `LineMarks` of that file's document, whether it is on screen or not. Selecting an error in the list switches to its file and calls `navigateToLine`.
- **Insertions from other screens** (Add uniform, Add texture, Add include, Convert) are functions from a `Text` and its analysis to a replace. They are applied to the document of the target file, so they are undoable and need no view.
- **Reload from file, Keep my edits** (UX 7): reload is one replace of the whole text, undoable; nothing else in the editor is involved.

### 4.10 What is deliberately not built

Multiple cursors. Code folding. Soft line wrap. A minimap. Bracket matching and auto-closing brackets (neither exists today). Find and replace (it does not exist today; the document makes it easy to add, and it is the first thing I would add afterwards). Rectangular selection. Drag and drop of text. Spell checking. Inline squiggles under error ranges (errors mark whole lines, as today). Variable line heights, images or widgets between lines. Right-to-left paragraphs. A general marker or span API on the document. A plug-in interface for languages beyond `Syntax`. Stylus handwriting gestures, cursor anchor updates for keyboards, content commit (images). The "process text" entries (Translate and the like) that the platform adds to an `EditText`'s toolbar. Autofill and smart text selection, which are switched off or useless for code today.

### 4.11 Every behaviour of today's editor, and what becomes of it

From `ShaderEditor`, `LineNumberEditText`, `LineNumberGutter`, `IndentTabs`, `ShaderToySource`, `UndoRedo`, `EditorFragment`, `CompletionsAdapter`, `ExtraKeysManager` and `fragment_editor.xml`.

| Behaviour today | In the new editor |
| --- | --- |
| Syntax colours per token, 21 styles, from resources | Kept. Colours update with each keystroke instead of one update delay after the last one (LANGUAGE.md already decided this) |
| "Disable highlighting" setting, applied above 4,096 characters | Kept as a switch that makes `LineStyles` return no runs. Its reason (slow highlighting) is gone; decision D8 offers to remove it |
| Update delay: after a pause, `onEditPaused(text)` triggers the compile | Kept, same setting, same callback. The text is handed over as a `Text` once the receiver takes one; a `String` until then |
| `onTextModified` after each user edit; "modified" state | Kept, from the document. Also true again/false again through undo |
| Line numbers, optional, gutter as wide as the digit count | Kept. The gutter stays in place during horizontal scrolling (D5) |
| Horizontal scrolling, no wrap, padding around the text | Kept |
| Font, text size, ligatures on or off | Kept |
| Tab width setting; every tab is that many columns wide | Kept exactly (D4) |
| Tab key inserts a tab when "tab for indent" is on, else moves focus | Kept |
| Auto-indent on Enter: copy the line's indentation; one more tab after `{`, an operator or an open parenthesis; continue a `//` comment when the caret is inside the line | Kept, as a pure function with tests. Today it does not run when the caret is at the very end of the text; that gap is closed |
| No-break spaces replaced by spaces | Kept, where text enters the document |
| ShaderToy conversion: text that defines `mainImage` and no `main` is wrapped and its uniforms renamed | Kept. Checked from the analysis, off the main thread, instead of by a regex over the whole text on every keystroke. The conversion becomes one undoable replace; today it wipes the history |
| Error and warning lines get a background colour; cleared when the user edits or a new result arrives | Kept, through `LineMarks`. Marks now follow their lines when text is inserted above them |
| Error list, `navigateToLine` moves the caret to the line unless it is already there | Kept |
| Completion strip: the word before the caret against directives, declared names, language words, built-in uniforms; default keys `{ } ( ) [ ]`; nothing inside comments; a completion inserts the missing rest of the word | Kept. Updates at once on each keystroke |
| Extra keys row and its Tab button, auto-hide with the keyboard | Kept; it only needs `insert` |
| `insert(text)` replaces the selection | Kept |
| `addUniform(statement)`: removes an existing declaration of the same uniform, inserts after the last `uniform` line, else after the last `#endif`, else at the top | Kept, as a function on the text; becomes one undo step |
| `getCleanText()`: trailing white space removed from the copy that is saved, run or shared | Kept |
| Undo and redo from the menu; history cleared on a new text; kept across view recreation | Kept, per document. Typing is grouped |
| "Hide native suggestions" (default on) gives the keyboard `TYPE_NULL` | Kept |
| `flagNoExtractUi`, no autofill | Kept |
| Toggle code visibility hides the keyboard | Kept |
| The view saves its whole text in the instance state | Not kept, on purpose: this is the crash the other agent found. The text lives in the document and the draft journal |
| Platform selection handles, magnifier, floating toolbar with cut, copy, paste, select all | Rebuilt (4.5). The toolbar loses the platform's extra entries (Share, Translate and other "process text" apps); Share can be added in one line if wanted |
| Platform undo on Ctrl+Z, separate from the app's | Replaced by the one history |
| TalkBack support of `EditText` | Rebuilt to the minimum of 4.6 before the switch; less complete at first (D6) |
| Text drag and drop, stylus handwriting into the field | Lost, declared (4.10) |
| The preview behind the editor, the drawer, the toolbar | Untouched; the editor is one view in the same place |

## 5. Proof

The prototype is the branch `proto/editor-core` on the fork (commit `ae13aed9`), marked throwaway in every file and in its commit; it is not a pull request and must not be merged. Its `proto-evidence/` folder holds the raw output behind every number below. It contains: `Text`, `Document` (with undo) and `LineStates` in pure Java; `GlslLexer.lexLine`; a view that draws visible lines and has its own `InputConnection`; the platform `EditText` with the same clocks; and one launcher alias per measured configuration.

What "measured" means below:

- **JVM:** OpenJDK 17 on the M1 Pro host, median of thousands of calls after a warm-up. The host was heavily loaded by other agents (load average 18 to 38 on 10 cores), so absolute values are pessimistic and noisy; the shape across sizes is what counts.
- **Phone:** the owner's Samsung SM-S938B, Android 17, a build that is not debuggable (a debuggable build ran the same code two to six times slower, and far slower on first use), compiled on the device with `cmd package compile -m speed`. The thermal status was 1 to 2 (light to moderate) throughout. The test text is the 5,002-line `horizon-5000.glsl`, its first 500 lines, or ten copies of it (50,021 lines, 2.3 MB).
- **Self-driven runs:** the activity types by itself, through the view's `InputConnection`: 60 characters, every 20th a line break, then 20 deletes, one every 150 ms, in the middle of the text. Then a paste of the 235 KB file, an undo, select all, replacing everything by one character, and one fling at 24,000 px/s. Each keystroke logs the time inside the call and the time of the `onDraw` that showed it; `FrameMetrics` gives the main-thread stages of that frame.
- **Ranges** are over the runs of a configuration (one to four each; a single value is a single run). They differ by up to three times between runs, more than between sizes; I believe this is the CPU's frequency state after idle pauses and did not establish it.

### 5.1 The document model on the JVM

| Operation | 5,003 lines, 235 KB | 50,021 lines, 2.3 MB | 500,201 lines, 23 MB |
| --- | --- | --- | --- |
| Type at the caret (one key in 40 is Enter) | 3.0 µs | 1.0 µs | 0.6 µs |
| Backspace | 3.4 µs | 0.7 µs | 0.3 µs |
| Type at a random place | 1.8 µs | 0.9 µs | 1.8 µs |
| Offset to line / line to offset / read a line | 0.21 / 0.17 / 0.08 µs | 0.13 / 0.08 / 0.04 µs | 0.38 / 0.29 / 0.08 µs |
| Paste 5,002 lines: split into lines, then insert | 0.14 ms, then 24 µs | 0.12 ms, then 8 µs | 0.07 ms, then 5 µs |
| Paste 20,008 lines (940 KB): split, then insert | 0.65 ms, then 17 µs | 0.44 ms, then 13 µs | 0.29 ms, then 14 µs |
| Select all and delete | 1.8 µs | 0.3 µs | 0.4 µs |
| Paste 5,002 lines, undo, redo, per step | 7 µs | 8 µs | 6 µs |
| Load: build the text from a string | 0.19 ms | 0.76 ms | 18 ms |
| A copy for another thread | none needed | none needed | none needed |
| Memory | 2 bytes per character (the string alone: 1) | 1.9 | 1.9 |
| For comparison, a gap buffer with a plain line-start array: type at a random place | 3.3 µs | 35 µs | 380 µs |
| For comparison, a gap buffer: a copy for another thread | 1.1 ms | 1.2 ms | 10.5 ms |

The smaller sizes show larger times in some rows because they ran first, before the JIT had finished; no row grows with size. One long line: a keystroke costs 3.5 µs in a line of 1,000 characters, 8 µs at 10,000, 30 µs at 100,000 and 100 µs at 1,000,000.

Correctness: 16,000 random edits, with undo and redo in between and undo back to the start at the end, agree with a `StringBuilder` in content, length, line count, line lookup, substring and slice.

### 5.2 Incremental tokenizing on the JVM

| Measure | 5,003 lines | 50,021 lines | 500,201 lines |
| --- | --- | --- | --- |
| Type one character and re-tokenize (text change included) | 1.3 µs | 1.0 µs | 0.8 µs |
| Type `/*` on line 11: the 200 lines of the budget | 38 µs | 26 µs | 45 µs |
| The rest of the file after that, as the background task would do it, cold, one run | 0.9 ms | 13 ms | 210 ms |
| For comparison: tokenize the whole text (`GlslLexer.lex`) | 3.9 ms | 37 ms | 437 ms |
| For comparison: the outline of the whole text | 0.6 ms | 2.5 ms | 43 ms |

- Per-line tokens equal whole-text tokens for all 234,890 characters of the test file.
- After the same 16,000 random edits (which include `/*`, `*/`, `//` and line breaks), the incrementally kept line states equal a fresh computation.
- The whole-text figures are several times higher than the 1.4 ms per 5,000 lines of the brief, which I attribute to the loaded host.

### 5.3 On the phone

**Typing.** Main-thread time per keystroke: the time inside the input call plus the main-thread stages of the frame that shows it. Median of 80 keystrokes per run; the range is over runs.

| | 500 lines | 5,002 lines | 50,021 lines |
| --- | --- | --- | --- |
| **Prototype view** (line cache) | 0.10 + 1.0 to 1.5 = **1.1 to 1.6 ms** | 0.05 to 0.10 + 0.5 to 1.4 = **0.5 to 1.5 ms** | 0.04 to 0.10 + 0.4 to 1.3 = **0.4 to 1.4 ms** |
| Prototype view, 90th percentile of the frame | 1.4 to 1.8 ms | 0.7 to 1.7 ms | 0.5 to 1.6 ms |
| Prototype view, slowest frame of the run | 2.1 ms | 1.0 to 2.7 ms | 0.6 to 1.8 ms, and one frame of 6.9 ms in one run |
| Prototype view without the line cache (the path for Android 6 to 9) | not run | 0.1 + 5.5 = 5.6 ms | 0.1 + 5.4 = 5.5 ms |
| Prototype view, every line through a `StaticLayout` | not run | 1.0 to 2.4 ms | 1.2 ms |
| **Platform `EditText`, best case** (screen-sized, scrolls itself, no spans, no emoji2) | 2.9 to 3.5 + 0.9 = **3.7 to 4.3 ms** | 6.3 to 6.9 + 0.4 = **6.7 to 7.3 ms** | 34 + 0.2 = **34 ms** (90th percentile 39, slowest 88) |
| Best case plus one colour span per token | not run | 64 + 1.6 = **66 ms** | not run |
| `EditText` hosted as the app hosts it (in the two scroll views), no spans | not run | 1.7 + 109 = **111 ms** | not run |
| Hosted as today, with colour spans | not run | 1,340 + 1,040 = **2.4 s** (7 keys before the run ended) | not run |

The last row is the app's situation and agrees with the other agent's 2.2 to 3.3 s on the real app. The rows between show how much each tuning step buys and where it stops: on this phone the best configuration of the stock widget still costs 7 ms per key at 5,000 lines and grows with the text. On the emulator the same best case costs 0.9 + 0.5 ms at 5,000 lines. I did not profile the difference; the phone has an accessibility service, an autofill service and a content-capture service enabled and the emulator has none, which fits rows 5 and 6 of section 2.

**Typing through a real keyboard.** Gboard on the phone, tapped key by key, in the 50,021-line document: 0.03 to 0.07 ms inside each call, 0.26 to 0.53 ms of main-thread time for the frame, 2 to 8 ms from vsync to the frame on screen. Section 5.4 has the calls.

**Opening.** Main thread means everything the main thread does until the first frame is drawn.

| | 500 lines | 5,002 lines | 50,021 lines |
| --- | --- | --- | --- |
| Prototype view: background (build text and line states) | 1.4 ms | 10 to 14 ms | 87 to 107 ms |
| Prototype view: main thread | 2.2 ms | **2.0 to 2.6 ms** | **2.1 to 2.7 ms** |
| Prototype view: activity created to first frame drawn | 30 to 44 ms | 30 to 59 ms | 104 to 128 ms |
| Platform `EditText`, best case, no spans: main thread | 25 to 31 ms | **144 to 165 ms** | **3.0 s** |
| `EditText` hosted as today, with colour spans (spans built beforehand, not counted): main thread | not run | 1.1 s | not run |

**Large changes** at 5,002 lines unless noted; the call, then the `onDraw` of the frame after it.

| | Prototype view | Platform `EditText`, best case |
| --- | --- | --- |
| Paste 235 KB (5,002 lines) | 0.7 to 8.6 ms, then 1.3 to 3.9 ms | 85 to 135 ms, then 53 to 108 ms |
| Undo that paste | 0.05 to 0.4 ms, then 1.0 to 7.3 ms | 29 to 31 ms |
| Replace the whole text by one character | 0.4 to 1.5 ms | 7 to 10 ms |
| Undo that (put 5,000 or 50,000 lines back) | 0.5 to 2.5 ms, then 1.2 to 1.9 ms | not run |
| The same four at 50,021 lines | Inside the same ranges | not run |

The frames after these changes show a screen of lines that were not on screen before. Recording such a screenful (about 75 lines) took 1.0 to 8.7 ms across all runs, 2.0 to 2.7 ms when opening.

**Fling**, one fling of about 160 frames:

| | Prototype view |
| --- | --- |
| Main thread per frame: median, 90th percentile, slowest | 0.25 to 1.3 ms, 0.3 to 2.0 ms, 0.6 to 3.1 ms |
| Whole frame (vsync to on screen): median, 90th percentile, slowest | 2.0 to 5.3 ms, 2.3 to 6.5 ms, 6.8 to 16.8 ms |

The stock widget's fling was measured only as hosted today, where the system scrolls a pre-drawn layer: 0.8 ms of main thread per frame. The best-case stock configuration has no fling to measure (section 2, row 2).

### 5.4 The keyboard connection with a real keyboard

Gboard is the keyboard on the owner's phone and on the emulator. I tapped its keys one at a time (each tap after a check that the prototype was in front) and logged every call.

| Setting of the view | What Gboard sent |
| --- | --- |
| At focus | `getSurroundingText(60, 60)`; while the prototype still answered that with `null`, as `BaseInputConnection` does, `getTextBeforeCursor(600)`, `getTextAfterCursor(600)` and `getSelectedText` instead. Then `finishComposingText` and `requestCursorUpdates(MONITOR)`; answering the last with `true` or `false` made no difference |
| Text, multi-line, no suggestions (the app's setting when "hide native suggestions" is off) | One `commitText` per letter and for Enter; **Backspace as a key event** (`sendKeyEvent` down and up, key code 67), not `deleteSurroundingText`; a fresh read of the text around the caret after Enter and after the first Backspace |
| Text, multi-line, suggestions allowed | The same. Glide typing a word arrived as one `commitText("hi")`. A tap inside an existing word caused no `setComposingRegion` |
| `TYPE_NULL` (the app's default) | Every key as a key event, and a read of the text around the caret after each |
| A hardware-style key sent by the test tool | Gboard turned it into `commitText` plus the key-up event |

After typing `foo bar`, Enter, `xx` and three Backspaces the text on screen was the original line plus `foo bar`, as it should be; the screenshot is not in the repository because it shows the owner's status bar.

**What this proves:** the view is accepted as a text editor by a real keyboard; committed text, key events and text queries work at 50,021 lines at well under a millisecond each; the two paths the app uses by default work.

**What it does not prove:** composing text. In these runs Gboard never sent `setComposingText` or `setComposingRegion`, with suggestions allowed, with glide typing, on the phone or the emulator. It did not send them to a platform `EditText` with the same settings either, so this is how this Gboard treats such a field, not a refusal of the prototype. The composing rules of section 4.5 are implemented in the prototype and exercised by nothing but my reading of the contract. Voice input and other keyboards, Samsung Keyboard above all, will use them. That is the first thing T10 has to test (section 6).

Two facts the traces added to the design: `getSurroundingText` is the first thing a current Gboard asks, and `BaseInputConnection` answers `null` to it without an `Editable`, so the view must implement it; and Backspace arrives as a key event even in ordinary text mode, so the key path is not optional.

### 5.5 Against the criteria of section 1

| # | Criterion | Measured | Verdict |
| --- | --- | --- | --- |
| R1 | Keystroke: median ≤ 2 ms, 99th percentile ≤ 4 ms; flat with size | Median 0.4 to 1.6 ms at all three sizes; slowest of 80 at most 2.7 ms in eight runs, one frame of 6.9 ms in the ninth | **Met.** It needed a change of design: drawing every visible line on every frame cost 5.5 ms, so each line's drawing is now cached (4.4) |
| R2 | Paste 235 KB ≤ 8 ms; 1 MB ≤ 30 ms | 2 to 12 ms in total (call 0.7 to 8.6, frame 1.3 to 3.9). 1 MB not measured on the phone | **Met in the faster runs, missed by up to 4 ms in the slower.** Most of the call is splitting the clip into lines. Design change: a paste from the clipboard builds its text on a background thread, which leaves the insert (microseconds) and the frame on the main thread. Not rebuilt and re-measured |
| R3 | Open: main thread ≤ 8 ms; on screen ≤ 100 ms at 5,000 lines, ≤ 300 ms at 50,000 | Main thread 2.0 to 2.7 ms at both sizes; first frame 30 to 59 ms and 104 to 128 ms after the activity was created, from a file inside the app | **Met** |
| R4 | Fling: every frame inside the frame time; main thread ≤ 3 ms | Main thread: median ≤ 1.3 ms, slowest 3.1 ms. Whole frame: 90th percentile ≤ 6.5 ms, slowest frame of a run 6.8 to 16.8 ms | **Main thread met. "Every frame" not shown:** a run's slowest frame was above 8.3 ms in about half of the nine runs, and I have each run's maximum, not the count |
| R5 | Select all ≤ 2 ms | The command is under 0.1 ms. The prototype also scrolls to the end, so its frame is a new screenful (1.1 to 8.7 ms); the design does not scroll | **Command met; the designed frame not measured** |
| R6 | Undo a large change ≤ 4 ms | 1.1 to 7.7 ms (call 0.05 to 2.5, frame 1.0 to 7.3) | **Met in the faster runs, missed in the slower,** for the same reason as R2: the frame after it shows a screenful that has to be recorded |
| R7 | Idle: nothing but the caret | A caret blink is one frame of 0.3 to 1.1 ms on the main thread; nothing else ran | **Met** |
| R8 | Switch file ≤ 8 ms | Not built. It is one screenful: 1.0 to 8.7 ms | **Likely, not measured** |
| R9 | Colours follow typing | On the JVM: the edited line in 1 µs, the budget in 26 to 45 µs, the rest of 5,000 lines in 0.9 ms and of 50,000 in 13 ms. On the phone the prototype continues on the main thread in slices and was not timed | **Met on the JVM; background hand-over not built** |
| R10 | Memory ≤ 4 bytes per character | 1.9 to 2 on the JVM | **Met on the JVM; not measured on Android** |

**The one soft spot is the frame that shows a screen of new lines** (after a jump, a large paste or undo, a file switch): 1 to 9 ms, against one keystroke's 0.5 to 1.5 ms. It is inside "milliseconds" and far from the stock widget's 50 to 100 ms for the same event, but it does not always fit my own 4 ms and 8 ms lines. Two things in the design keep it small and one is left to decide at T8:

- The cache finds a line by its string, not its number, so lines that only moved (Enter above them) or came back (undo, scrolling back) are not recorded again. The prototype does this.
- A full screen is the worst case and is bounded by the screen, not the file.
- If it still matters on slower phones, spread the recording of a new screen over two frames, drawing the lines not yet recorded directly. Not built.

### 5.6 What the prototype is not

It has no selection handles, no magnifier, no floating toolbar, no accessibility, no word movement, no auto-indent, no completion strip and no error marks, and its background work runs in slices on the main thread instead of on a thread. Those are sections 4.5, 4.6 and 4.8 on paper only. Nothing in it should be copied into production without the tests section 6 asks for.

## 6. The path there

I call this track T. It follows the plan's standing rules: one idea per pull request, at most about 350 added production lines, refactoring apart from behaviour change, ★ marks a change users can see. Line counts are estimates; the prototype's `Text` is 420 lines with comments, its view 800 for about a third of the behaviour.

| PR | Change | Added lines | What it proves, and how it is tested | Needs a phone |
| --- | --- | --- | --- | --- |
| T1 | `Text`: the immutable tree of lines | 350 | Random edits against a `StringBuilder`; offsets, lines, slices | No |
| T2 | `Document`: replace, change events, selection, batches, cleaning of incoming text | 200 | JVM | No |
| T3 | Undo and redo with typing groups and the saved point | 180 | JVM: undo to the start after random edits | No |
| T4 ★ | `GlslLexer.lexLine` and `LineTokens`; the type guess stops at a line end | 120 | JVM: per-line tokens equal whole-text tokens on every bundled shader; the one intended difference is listed | No |
| T5 | `LineStyles`: line states, the incremental walk, the budget, the stale range | 220 | JVM: after random edits the states equal a fresh computation | No |
| T6 | `TextInput`: the keyboard contract of 4.5 | 250 | JVM: one test per rule, plus recorded keyboard sequences replayed | No |
| T7 | Edit commands: caret movement, words, delete, auto-indent, tab | 250 | JVM. Auto-indent is pinned from today's behaviour first | No |
| T8 | `LineLayout` and `CodeView` drawing: visible lines, the line cache, gutter, caret, selection, line marks; reachable only from a debug screen | 350 | Emulator by eye; frame times on the phone | Yes |
| T9 | Scrolling: fling, scroll bars, edge effects, caret into view, keyboard insets | 200 | Phone: R4 | Yes |
| T10 | Keys and the `InputConnection` shell | 300 | Phone: composing text first of all (section 5.4), with voice input and with Samsung Keyboard (decision D12); Gboard with and without suggestions; a hardware keyboard | Yes |
| T11 | Touch selection: tap, double tap, long press, drag, handles | 350 | Phone, by hand | Yes |
| T12 | Floating toolbar, clipboard, magnifier, mouse | 250 | Phone; desktop mode for the mouse | Yes |
| T13 | Accessibility minimum (4.6) | 200 | Phone with TalkBack | Yes |
| T14 | `Analysis`, `LineMarks`, completion from line tokens | 250 | JVM | No |
| T15 | `EditorFragment` can host either editor behind a setting "New editor", off by default: fonts, sizes, tab width, line numbers, update delay, errors, completion strip, Add uniform, ShaderToy check. Probably two PRs | 400 | Emulator: the table of 4.11 as a checklist, old and new side by side | No |
| T16 | Loading, saving and drafts take and give a `Text` | 200 | JVM for the seam; emulator | No |
| T17 ★ | The new editor is the default; the old one stays selectable for one release | 20 | Phone: every criterion of section 1 measured with a release build at 500, 5,000 and 50,000 lines | Yes |
| T18 | Delete `ShaderEditor`'s `EditText` code, `LineNumberEditText`, `LineNumberGutter`, `IndentTabs`, `UndoRedo`, `EditedRange`, `TokenDiff` | −1,900 | The existing tests | No |

**Size of the whole:** about 4,000 added production lines and 2,000 test lines in 18 to 20 pull requests, 1,900 lines removed at the end. T1 to T7 and T14 are about 1,800 lines of pure Java that can be built and reviewed without a device, in parallel with other work.

**Order against the other tracks.**

- T1 to T3 should land before the project-mode work that gives each file its own text and undo (M10), so that work builds on `Document` instead of on the `EditText`.
- L14 (colour only visible lines) and L15 (colours as tokens arrive) of LANGUAGE.md are superseded: both are properties of the new view. Do not build them on the old widget.
- The fix for the `TransactionTooLargeException` (do not save the text in the instance state) is worth doing on the old editor now; it is one line and independent.

**Upstream, in digestible pieces.**

1. Now: #146 and #147, as bug fixes. They stand on their own.
2. The core, T1 to T7: seven small pull requests of pure Java with tests and no user-visible effect. A maintainer can judge them without a device. The honest weakness is that they are unused code until the view arrives, so I would offer them together with the next step, as one stated plan.
3. The view behind a setting, T8 to T16: "an experimental editor for large shaders", off by default, old editor untouched.
4. After a release with the setting: T17 and T18.

The maintainer may still decline a 4,000-line editor. Then the fork carries it; it is built to be carried, as two packages (`editor/`, and `CodeView` with its helpers in `widget/`) that touch the rest of the app only through `EditorFragment`.

## 7. Open decisions for the owner

Each has a recommendation; they are independent unless noted.

| # | Decision | Options | Recommendation |
| --- | --- | --- | --- |
| D1 | Which way | (a) Tune `EditText` only, accept slow open and paste. (c) Build the editor of section 4. (d) Adopt Sora Editor | **(c)** |
| D2 | Default keyboard mode | Keep "hide native suggestions" on by default, so keyboards get `TYPE_NULL` as today. Or default to ordinary text input with suggestions off | **Keep today's default.** It is also the simplest keyboard path, which lowers the risk of the switch |
| D3 | Line wrap | None, as today. Or plan a "wrap lines" setting | **None now.** Revisit when a user asks |
| D4 | Tabs after text | Every tab is one tab width wide, as today. Or real tab stops (a tab advances to the next multiple of the tab width), as other editors do | **As today at the switch**, so that nothing moves on screen; real tab stops as a separate small change afterwards if wanted |
| D5 | Line numbers during horizontal scrolling | Stay in place. Or scroll away with the text, as today | **Stay in place.** Errors are reported by line number |
| D6 | Accessibility before the switch | The minimum of 4.6 before the default changes. Or full parity with `EditText` first. Or switch first and follow up | **The minimum first**, and keep the old editor selectable for TalkBack users until explore-by-touch works |
| D7 | Largest file the editor opens | 16 MB. Or 5 MB. Or no limit | **16 MB**, with a message above it |
| D8 | The "disable highlighting" setting | Remove it with the old editor. Or keep it | **Remove.** It exists because highlighting was slow |
| D9 | Life of the old editor after the switch | Selectable for one release, then deleted. Or deleted at the switch. Or kept | **One release** |
| D10 | Typing and undo | A run of typing is one undo step. Or one step per keystroke, as today | **Grouped** |
| D11 | Upstream | Offer the plan of section 6 now. Or build in the fork first and offer the finished editor. Or keep it in the fork | **Offer the plan now** with T1 as the first pull request; it costs nothing to ask and shapes the pieces |
| D12 | Samsung Keyboard | The owner switches his phone to Samsung Keyboard for one test session at T10. Or a second device. Or ship with Gboard tested only | **One session on the owner's phone**; I may not change that setting myself |

## 8. Risks, and what would make me abandon the recommendation

| Risk | How likely, how bad | What reduces it |
| --- | --- | --- |
| The keyboard contract: a keyboard we did not test loses or duplicates text | The main risk. Likely to produce some bug; each one is serious for the user | Today's default is `TYPE_NULL`, the path with the fewest rules. `TextInput` is pure and tested with replayed sequences. The old editor stays selectable. In the prototype, committed text, key events and text queries held with Gboard; composing text was never sent and is untested (section 5.4) |
| Touch selection does not feel like the platform's | Likely at first; annoying, not destructive | Platform handle drawables, platform floating toolbar and magnifier; by-hand comparison at T11 |
| TalkBack users are worse off | Certain at first | D6 |
| Old and slow devices (Android 6 to 9) | Unknown: not measured. They take the direct drawing path, about 5 ms per frame on the reference phone and several times that on an old one | Measure at T8 on an old device or a throttled emulator. If it fails there: record lines into `Picture`s, or keep the old editor as the default below API 29 |
| The per-line lexer and the whole-text lexer drift apart | Low; bad for trust in colours | One implementation, two entry points, and T4's equality test over every bundled shader |
| Upstream declines | Possible | Section 6: built to be carried |
| It takes longer than estimated | Likely; the estimate for T10 to T13 is the soft one | T1 to T7 are useful to project mode on their own. The switch (T17) is the only point of no return, and it is small |

**I would abandon (c):**

- **For (d), Sora Editor,** if after T10 text input with composing keyboards is still wrong on the reference phone in ways the model cannot express, or if T10 to T13 together pass twice their estimate. The core (T1 to T7) would largely be lost; that is the cost of the bet, about 1,800 lines.
- **For the `Editable` facade** (4.5) as a smaller retreat, if only the keyboard methods are the problem: keep everything else and let `BaseInputConnection` drive the document through an adapter.
- **For (a)** if the owner decides that opening and pasting large text may stay slow. Then the right amount of work is the configuration described in section 2 and nothing else.

Nothing measured so far points to any of these.

## 9. Not verified

- **Composing text on a device.** No keyboard sent `setComposingText` or `setComposingRegion` in any run. Samsung Keyboard, voice input and a physical keyboard were not tested; the key-event path was tested with Gboard's own key events and with keys injected by the test tool.
- **Old and slow devices.** Everything on a device ran on the owner's phone and on an API 36 emulator. Nothing ran on Android 6 to 9, where the line cache does not exist.
- **A release build of the app.** The phone ran a non-debuggable debug build of the prototype, compiled on the device, without R8.
- **Parts of the criteria:** a 1 MB paste on the phone (R2), the frame of select all as designed (R5), a file switch (R8), the background hand-over of line states on a device (R9), memory on Android (R10), and how many fling frames exceeded the frame time (R4).
- **Why runs differ by up to three times** between repetitions, and why the best-case stock widget is five times slower per key on the phone than on the emulator. Both have a plausible cause named in section 5; neither was profiled. The phone's refresh rate during the runs was not recorded.
- **The JVM figures** come from a host under heavy load.
- **Touch selection, handles, magnifier, toolbar, accessibility, word movement, auto-indent in the new model:** designed, not prototyped.
- **Sora Editor** was not built against. Its licence, size, dependencies and activity were read from GitHub and Maven Central today; what it is like to use is from memory. The statements about CodeView, Squircle CE, CodeMirror and Compose are from memory.
- **The line counts** of section 6 are estimates.

## 10. Left behind on the devices

- **Phone:** the prototype was installed as `de.markusfisch.android.shadereditor.edproto` and uninstalled. The device tool installed its own helper, `com.callstack.agentdevice.snapshothelper`, on first use; I uninstalled it as well, as the other agent had done. No setting was changed, no file was pushed, and input was sent only while the prototype was in front. `pm list packages --user 0` shows `de.markusfisch.android.shadereditor.debug.test` and nothing else of ours, as before.
- **Emulator `emulator-5554`:** the prototype was installed and uninstalled. The device tool had switched the keyboard to its helper; I selected Gboard for the test, which was also the selected keyboard when I took the lock. The lock file is removed.
- **Repository:** the branch `proto/editor-core` on the fork. Nothing else was pushed, no pull request was opened, and no shared branch was touched.

## 11. Decisions of 2026-11-12

The owner decided the open decisions of section 7. Where a decision differs from the recommendation above, this section wins and the affected steps of section 6 change as noted.

| # | Decision | Taken |
| --- | --- | --- |
| D1 | Which way | Build the editor of section 4 (option c) |
| D2 | Default keyboard mode | Keep today's default: native suggestions hidden |
| D3 | Line wrap | None for now |
| D4 | Tabs after text | **Real tab stops from the start**: a tab advances to the next multiple of the tab width. Shaders with tabs after code look slightly different from the switch on; say so in the change that switches |
| D5 | Line numbers during horizontal scrolling | Stay in place |
| D6 | Accessibility before the switch | **Full parity with `EditText` first.** T13 grows from "the minimum" to parity (explore by touch, granular navigation, text selection and editing through the accessibility actions, announcements), is checked with TalkBack on a phone, and gates T17 |
| D7 | Largest file the editor opens | 16 MB, with a message above it |
| D8 | The "disable highlighting" setting | Removed with the old editor |
| D9 | Life of the old editor after the switch | **Deleted at the switch.** The period in which the new editor is selectable behind a setting is the safety net, so T17 and T18 land together and there is one editor afterwards |
| D10 | Typing and undo | A run of typing is one undo step |
| D11 | Upstream | **Build and prove it in the fork first**, then offer the finished editor behind a setting in small pieces. Nothing is offered upstream while earlier pull requests wait for the maintainer |
| D12 | Keyboards | **A keyboard matrix, with composing text exercised on purpose.** Today's default mode (native suggestions hidden) makes keyboards send plain key events and committed text, which is why the prototype never saw composing text; the matrix therefore runs every keyboard in both modes. Keyboards: Gboard (typing, glide typing, voice input, and one layout that always composes, such as Pinyin or Japanese, because the app has Chinese, Russian and Ukrainian users who write comments in their language), Samsung Keyboard, Microsoft SwiftKey, and a hardware keyboard. They are tested on the owner's phone; a worker may switch the default keyboard for the test and must switch it back; a keyboard that is not installed is installed by the owner, not sideloaded. Each session's calls to the input connection are recorded and replayed as JVM tests of `TextInput` (T6), so a keyboard's behaviour stays covered after the session |

The requirement behind all of this, in the owner's words: "neither 1 nor 3 seconds per keypress is acceptable in any capacity. Editing the shader must take milliseconds, not 10s, not 100s and definitely not 1000s of milliseconds — regardless of edit size", and "this is not just about edits. Opening files and pasting large shaders should equally be fast."

## 12. What changed while building the document model (T1 to T3)

Three points of section 4 were built differently, for reasons found while building them. This section wins over sections 4.0 to 4.2 on them.

- **Cleaning of incoming text lives in `Text.of`, not in `Document`.** Loading and pasting build their `Text` on a background thread and hand it to the document; cleaning in `Document` would either cost time proportional to the text on the main thread or be skipped on those paths. So no `Text` anywhere holds a carriage return or a no-break space.
- **A batch does not end a run of typing.** Keyboards wrap each committed character in a batch edit, so ending the run at every batch would undo typed text letter by letter again. A batch is one undo step; a batch that contains a change that is not typing starts its own step, so an autocorrection is undone on its own.
- **The undo history is bounded twice:** at 1,000 steps and at 4,000,000 characters of removed plus inserted text. Past either bound the oldest steps are dropped; the newest step is always kept. A cap on steps alone does not bound memory when one step can be a whole file.

Measured on the JVM at 5,000, 50,000 and 500,000 lines: a typed character costs 0.5 to 3.4 µs and does not grow with the number of lines; an edit of one line of a 500,000-line text creates at most 12 new tree nodes. Still open from section 4.4: `Text` does not yet keep the longest line per node, which the view needs for its horizontal scroll range.

## 13. After the independent review of the core (2026-11-12)

An independent review of the pure-Java core (T1 to T7, T14) found 29 issues, one of them serious (undo of a mixed keyboard batch also undid typing from before it). All were fixed with a regression test each, except the deviations below, which are accepted on purpose. This section wins over sections 4.1 to 4.5 where they differ.

**What changed in the interfaces.**

- **`Document`.** A batch decides its undo step when it ends: it continues the run of typing only if it is one typed change that touches the run; otherwise it is a step of its own. Every text in the history has a state number; a save takes `state()` with the text and reports `markSaved(state)` when it finishes, so undo back to an asynchronously saved text shows unmodified. Replacing a range by the same text only moves the selection.
- **`TextInput`.** A change counts as the keyboard's only during `TextInput`'s own call to the document. Updates to the keyboard go out one at a time, stored before any listener runs. Both ranges of a surrounding delete are checked before anything is deleted; the code-point variant follows Android's rule for invalid surrogates. A selection longer than the 4,096-character window makes `getSelectedText` and `getSurroundingText` return null; extracted text is cut but reports the real anchor and head. `finishComposingText` on a closed connection returns true.
- **What one character is** comes from `Motion.Boundaries`, by default `java.text.BreakIterator`. The Android shell may supply the platform's or the layout's answer through the same seam. Emoji with skin tones, joined emoji and flags are one character on Android and several on the desktop Java that runs the tests, so they are checked on a device (T10, T11).
- **`LineStyles` and the view.** The view calls `show(from, to)` with the lines it draws, `runs(n)` for each, and, while `continuation()` is not null, runs it off the main thread and calls `apply(result)` until it returns false. Work after an edit or a `show` is at most the shown lines plus the budget; `apply` stores at most 4,096 states per call.
- **`LineMarks.set(revision, diagnostics)`** does not install diagnostics made for an older revision, and drops a mark that can no longer be placed (its line joined to text in front of it). A missing mark is better than one on the wrong line.
- **Indenting many lines is one change**, so its undo is one tree replacement.
- **Completion** offers at most 50 words from names sorted off the main thread, and leaves out the declaration being typed only when the names are for the current revision.
- **Keyboard sessions** record, per line, the time since the session began and who acted (a connection number or `app`); connections have `open` and `close` lines; app actions are recorded as what was asked (undo, redo, a key, a command) with the resulting changes as expected output. Lines collect in memory until the shell drains them; at 1,048,576 characters the session ends with a `truncated` line.

**Accepted deviations from section 1, with their measured size.**

- **The line states are a gap buffer of bytes.** An edit far from the last one moves bytes in proportion to the distance in lines: 5.5 µs median at 500,000 lines on the desktop JVM. Opening allocates one byte per line. Both are proportional to the file in theory and negligible in practice; a chunked structure is not worth its code.
- **Error marks are scanned on every edit.** Their number is the number of diagnostics of one build, which is small.
- **Analysis is paced, not run 50 ms after every change** (2026-11-13, after a phone run showed 8 s of background CPU in 13 s of typing at 50,000 lines): one pass at a time; after a pass that took d, the next starts no sooner than 8·d after it ended and never sooner than 50 ms after the last change; a pass gives up at the next line once the document changes; one more pass runs when typing stops. Background CPU from analysis while typing is so at most about a ninth of the time, whatever the file size; in large files completion names and type colours lag by up to 8·d. Not yet measured on a phone.
- **The whole-file analysis has a fixed memory budget** (fixed 2026-11-13, after it ran out of memory on a 500,000-line text): it reads the text line by line, keeps no tokens, at most 10,000 declarations and 131,072 characters of names, about 1 MB in all, and leaves the outline empty when a line is longer than 65,536 characters. Past a cap, completion offers built-ins and the earlier names only and later declared types are not coloured. Measured on the desktop JVM at 500,000 lines: 1 MB kept instead of 84 MB. The editor's total memory on the phone is still to be measured before the switch (T17), with the limit of decision D7 in mind.
- **A one-line commit from the keyboard is typing for undo**, whatever its length: a glide word and a keyboard's clipboard paste are the same call. Text the view itself pastes goes through `Document.replace` and is its own step.
- **Recording is an instrument, not a feature**: it is bounded, off by default, and meant for small texts.

**Still to do on devices:** the Android shell must send a lone committed line break to the Enter command (Gboard sends Enter as text), build `replaceText` from the primitives, and close an old `TextInput` itself on Android 6, which has no `closeConnection`.
