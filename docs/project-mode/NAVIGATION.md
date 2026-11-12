# Navigation once the app has projects

Status: proposal of 2026-11-12, for the owner to decide. No production code was written. Pictures are in `.foreman/show/nav-design/`; each one named below is a PNG there, with the HTML it was rendered from next to it.

## Summary

**Recommendation.** Rebuild the drawer as one column that leads from the folders the app knows, through a folder, to its files: tap goes in, an arrow goes out, no tree. Add one section on top of it, **Running**, that lists the shader that runs and the files it is built from, so the files being worked on are one tap from the open drawer at any depth. Make the toolbar say what is edited and what runs, and let a tap on a shader run it, so that "what runs" needs no menu entry.

Three things in it are new compared with the accepted `UX.md` and need a decision before anything is built (section 9): what a tap does to the running shader (D1), what entering a folder does (D2), and where the frame rate goes in the toolbar (D3).

**The frequent trips, in taps.** A tap is one touch on a control; scrolling, typing and Android's own picker are not counted.

| Trip, made many times per session | Today | First attempt | Recommended |
| --- | --- | --- | --- |
| Open another of my shaders | 2 | 2 for a database shader; 5 for a shader file in a folder (menu, Files, row, menu, Run this file) | 2 |
| Back to the file I had before (main.glsl ⇄ lib/common.glsl) | not possible; 2 between two shaders | 4 into `lib`, 3 back out; +1 per folder level | 2, at any depth |
| To another file of the folder while the shader runs | not possible | 3 at the root, +1 per level | 2 if the running shader uses it; else 2 at the same level, +1 per level |
| From seeing an error to standing on it, in a file that is not open | 2 (one file only) | 2 | 2 |
| Read which file runs | 0 | 0, but the text is cut off ("Preview: main.…") | 0 |

"Today" was counted on the emulator, "First attempt" from its code and screenshots, "Recommended" from the mock-ups. None of the recommended numbers has been tried in a running app.

Contact sheets, in reading order:

| Sheet | Shows |
| --- | --- |
| `sheet-0-today-and-first-attempt.png` | Real screenshots: today's drawer, menu and Examples; the first attempt |
| `sheet-model-a-drawer-is-the-folder.png` … `sheet-model-e-one-list.png` | The five compared models, four frames each |
| `sheet-recommended-1-everyday.png` … `-4-wide.png` | The recommended design, 29 frames; each also alone at twice the size as `r-NN-*.png` |
| `sheet-decisions.png` | The alternatives for decisions D3, D4 and D8 |

---

## 1. Who uses this and for what

Evidence used: the store text (`fastlane/metadata/android/en-US`), `FAQ.md`, the file names of `docs-site/src/content`, the bundled examples and `docs/project-mode/EXAMPLES.md`, the project documents, and the upstream tracker `markusfisch/ShaderEditor` read through `gh` on 2026-11-12 (all 216 issue titles; 23 issues in full). There is no usage data. How large each group is, is not known; the order below is my estimate from how often each need appears in the tracker and the documents.

| Persona | What they come to do | How often, how held | How much they have | Evidence |
| --- | --- | --- | --- | --- |
| **Wallpaper owner.** Wants a moving wallpaper and changes an example until it fits | Open an example or one of a few shaders, change a colour or a speed, set it as wallpaper | A burst, then weeks of nothing. One hand | 1 to 10 shaders, one file each, mostly without names | The store's one-line description is "Create GLSL shaders and use them as live wallpaper". Five of the fifteen FAQ entries are about wallpapers. Issues 7, 36, 72, 79, 113, 159, 170, 184. EXAMPLES has a section "Wallpapers" |
| **Couch author.** Writes shaders from scratch on the phone | Open the shader from last time, type, watch, fix errors; start a new one | Long sessions, several a week. Two thumbs while typing, one hand while looking | 20 to 100 shaders. One file each today; wants shared code | #182: "I have over 50 shaders now and when I open the application I always have to scroll down to the latest one". #68: "losing tens of minutes of coding time". #43 (rename "to organize them"). #191, a user: "Having to copy and paste useful functions that I want to reuse across different shaders is just a bad user experience". #57 (include, open since 2017) |
| **ShaderToy porter** | Paste a shader, repair it, run it | Now and then. Two hands | A ported multipass shader is 2 to 6 files (Common, Buffer A…, Image) | #39 (open since 2016, 7 comments), #67, #249, #166, #28 (multipass); FAQ "Can I paste ShaderToy code directly?" |
| **Folder keeper.** Keeps shaders in a synced or versioned folder and also edits on a desktop | Run on the phone what was written elsewhere, adjust, save back; the folder may be a whole repository | A few times a week. One or two hands | 5 to 50 files in nested folders, next to files that are not shaders | #5 (2015, 10 comments): "Dropbox/Drive/Syncthing syncing, Git versioning, file transfer, backup archives". #84: "do my work from my PC with an actual keyboard". #125, a user: "edit with a faster editor and import to this app". #104, #277 (moving and backing up). #191 is the owner's own request |
| **Learner** | Open an example, change numbers, keep the ones that worked | Short sessions, often. One hand | Under 20 shaders, mostly kept examples | #48 (tutorial), #6, #280 ("I have no other resource"); FAQ "Where can I learn more about shaders?"; EXAMPLES is built in learning order |
| **Tablet or keyboard user**, including emulators on a desktop | The same as the couch author, on a wide screen | Long sessions. Keyboard | As the couch author | #123 and #122 (Nox: the maintainer answers "the app really needs to improve for desktop use"), #54, #127 (Tab key), #244. The manifest allows resizing; the app has no landscape or wide layout at all (no `layout-land`, no `sw600dp` resources) |
| **Screen-reader user** | Any of the above | Unknown | Unknown | No issue mentions it; I assume the group is small. It is a requirement all the same: EDITOR decision D6 asks for full parity checked with TalkBack, UX 9 for state that is "not color-only". On the emulator, two of today's toolbar buttons announce only their resource id (`…:id/toggle_code`, `…:id/menu_btn`) |

Three observations that shape the design:

1. **Most people have one flat list of single-file shaders and will keep it.** After the migration that list is the folder `ShaderEditor/Shaders` (SPEC 12.2). Folders with several files serve three of the seven personas. A design that puts a "projects" level in everybody's way taxes the majority for the minority.
2. **Shaders are known by their picture.** A shader without a name is listed by the date it was made; on the emulator the drawer shows "2026-11-12 00:57:51" and six pairs of "Back camera" and "Mirror" that only the 24 dp thumbnail tells apart.
3. **Opening must not be the same as running, for one case.** #32, #226, #237 and #257 are people locked out of the app by a shader that freezes it: "I managed to work around it by switching to a shader through the slow UI". Navigation has to work while the picture is broken, and looking at a folder must not start anything.

## 2. User stories and the navigation tasks behind them

★ marks the four that happen many times per session; the design is judged on those first.

| # | Story | Frequency | Cost today |
| --- | --- | --- | --- |
| 1 ★ | Find and open one of my shaders | Several per session, every persona | 2 taps (menu button, row), plus scrolling a list of 24 dp thumbnails and dates |
| 2 ★ | Switch back to the one I had before | Many per session once a shader has more than one file; a few per session between two shaders | 2 between shaders; the app has no notion of "before" |
| 3 | Start a new shader | Once a session or less | 2 (menu, New shader); it is created at once, unnamed |
| 4 | Open a folder from elsewhere | Once per folder, then it is in the list | Impossible. On Android 10 and later only a whole database can be imported, in Settings |
| 5 ★ | Move between the files of a project while a shader runs | Many per session for the porter, the folder keeper and anyone with an include | Impossible |
| 6 | See which file runs, and change it | Seeing: all the time. Changing: a few per session | Nothing to see: the open shader runs |
| 7 ★ | See what is unsaved and what has errors, and get there | Errors: every few minutes while typing. Unsaved: before leaving | Errors: 2 taps to the line (error button, row), one file. Unsaved text is not shown at all; auto-save on leaving is the default |
| 8 | Add a file or a texture | A few per project | Texture: 4 or more (menu, Add uniform/texture, tab, picture, …). File: impossible |
| 9 | Find an example and keep a copy | Often for the learner, seldom otherwise | 3 (menu, Examples, row). Every look inserts a copy, which is why the emulator has six "Mirror" |
| 10 | Pick a wallpaper | The wallpaper owner's main story; rare in absolute numbers | 4 (menu button, row, menu, Set as wallpaper), or in Settings |
| 11 | Rename, duplicate, delete | Rare | Rename: menu button, long press. Duplicate: 2. Delete: 3. Duplicate and delete only for the open shader; another one must be opened first |
| 12 | Get back after the app was killed | Every session | 0: the last shader reopens |

## 3. Principles

Each is followed by its reason. They are numbered because the comparison refers to them.

1. **The picture and the code own the screen; navigation appears when asked for.** With the keyboard up about 30 lines of code remain on a 412 × 915 dp phone (mock-up `r-26-keyboard.png`; the soft keyboard did not appear on the emulator, so this is not measured). A permanent bar or tab row costs three of them, on every screen, for everybody.
2. **One column, one direction.** Every level is the same list: a tap goes in, the arrow goes out. No tree, because indentation eats a narrow column, targets shrink, and what is expanded becomes state to keep. No second list in a second place, because then "where am I" has two answers; that is what the first attempt did.
3. **What I am working on is one tap from the open drawer, at any depth.** The trips made most (stories 2 and 5) must not get longer as folders get deeper.
4. **A shader is known by its picture, a source file by its name and place.** Thumbnails for what has a picture: a shader that has run, an image. Nothing is rendered or decoded in the background just to fill a list.
5. **Looking changes nothing.** Only a tap on a file changes what is edited. Browsing a folder, the examples or the folder list never stops or starts a shader. Reason: observation 3, and it makes exploring free.
6. **What runs and what is edited can both be read without opening anything**, and every mark is an icon with a spoken text, not a colour. Reason: story 6 and 7, UX 9.
7. **A folder is shown as it is, one level per look.** Nothing is scanned or guessed (SPEC 1, UX 3). What the app knows by itself (what runs, what is unsaved, where the errors are, what was open last) comes from memory and is there at once, before storage answers.
8. **An action lives on its object.** On a file: long press. On the file in the editor: the toolbar menu. On the list: the drawer. A fixed menu of actions that apply "sometimes" was one of the first attempt's patch sites.
9. **Back closes the thing on top (keyboard, sheet, filter, drawer) and then leaves the app. It never changes the file.** Unsaved text is always kept, so leaving never asks.
10. **The same thing on every width.** A wide screen keeps the column open; it does not get a different design.
11. **What is temporary is fenced in.** Database shaders appear as the folder "Shaders" through one list source and one adapter for "what is open". Removing the database later deletes those two and changes no screen.

On thumb reach: the drawer's trigger is top left, the worst corner for a right thumb, as today. I accept that (see weak points) instead of spending screen on a bottom handle, and compensate inside the drawer: what is needed most is directly under the header.

## 4. Designs compared

Five models. A to E are drawn pure, so that each shows what it is good and bad at; the recommendation (section 5) is A with parts of the others.

### What the first attempt shows (`sheet-0-today-and-first-attempt.png`)

It is the baseline of what not to do, concretely:

- **One flat list holds three kinds of thing.** An action (Open folder), folders, database shaders, with no heading between them (proto-03). A folder row opens a project but shows none of its files.
- **The files are a second list in a second place**, a bottom sheet behind the first entry of the toolbar menu (proto-14, proto-04). Its author counts three taps to switch files and notes that "the sheet always reopens at the folder root". The sheet cannot leave the folder; the drawer cannot enter it.
- **Run this file is the second entry of a fourteen-entry menu**, beside database actions that do not apply to a project.
- **The toolbar cannot say what it has to.** "common.… / Preview: main.…" (proto-09): what is edited, what runs and the frame rate share about 150 dp, less when the error button shows. The folder's name is never visible.
- **A tap on an image answers with a toast** ("image.png is an image").
- **An empty editor accepts typing that goes nowhere** (proto-13).
- **Marks stop at the file.** An unsaved file has a dot; the folder it is in shows nothing.

Section 6.10 says, lesson by lesson, how the recommended design avoids each or why it accepts it.

### Model A: the drawer is the file browser of the open folder (`sheet-model-a-drawer-is-the-folder.png`)

The owner's first idea taken literally, everything else as today.

- **Hierarchy in one column:** drill-in. The drawer lists the folder of the edited file; the first row leads up; above a folder's root are the folders.
- **Database shaders and later the library:** a folder "Shaders" at the top level. **Examples:** another.
- **What runs versus what is edited:** a word "Running" on the row; in the toolbar, the second line.
- **Marks:** on file rows.
- **Actions:** New and Open folder as rows; Run this file, rename, delete on long press.
- **Thumbnails:** as today (24 dp), for shaders.
- **Back and the drawer gesture:** Back closes the drawer; swipe as today.
- **One hand:** trigger top left; rows from the top.
- **Tablet, landscape:** the drawer can stay open on a wide screen.
- **Accessibility:** one list, read top to bottom; good.
- **Cost on the existing activity:** the smallest. The drawer exists; its list and adapter are replaced.
- **Worst at:** the trip back. From `lib/common.glsl` to `main.glsl` is three taps, one more per level (frame A3), and closed it says no more than today's toolbar can hold.

### Model B: a home screen; the editor is the second level (`sheet-model-b-home-screen.png`)

- **Hierarchy:** three screens: home (shaders as a grid, folders, examples as tabs), a folder's file list, the editor.
- **Database shaders, library, examples:** tabs on the home screen.
- **What runs versus edited:** in the editor's toolbar, as in A.
- **Actions:** New as the home screen's main button; file actions in the file screen.
- **Thumbnails:** 132 dp; the best of all models for finding by picture.
- **Back:** editor → file list → home. Back no longer leaves the app from the editor, and the app must decide whether it starts at home or in the last shader.
- **One hand:** good on the home screen (grid under the thumb), bad for files.
- **Tablet:** home and editor side by side would be a third layout.
- **Accessibility:** good; ordinary screens.
- **Cost:** the largest. A new screen or two, the preview stopped and started on every visit (the GL surface belongs to the editor), a back stack, state restore for three levels.
- **Worst at:** stories 2 and 5. Every look at the files leaves the running shader (frame B3), so in practice the editor needs its own file switcher, and then that is the navigation and the home screen is a launcher.

### Model C: a bar and sheet at the bottom for files; the drawer for folders (`sheet-model-c-bottom-sheet.png`)

- **Hierarchy:** split. Folders and shaders in the drawer, the files of the open folder in a sheet with drill-in.
- **What runs versus edited, marks:** always visible on the bar (frame C1). The only model with both state and trigger under the thumb.
- **Actions:** New in the sheet; Open folder in the drawer.
- **Back:** closes the sheet. No conflict with the system's edge gesture.
- **One hand:** the best.
- **Tablet:** a bottom sheet on a wide screen is wrong; it would need a side panel there, a second design.
- **Cost:** medium. Sheet, bar, and their behaviour above the keyboard and the extra keys.
- **Worst at:** typing. The bottom of the screen is taken by the keyboard and the extra-keys row; the bar either stacks on them (27 lines instead of 30, frame C3) or hides while typing. And it keeps the first attempt's split: two lists for one hierarchy (frame C4).

### Model D: open files as tabs (`sheet-model-d-tabs.png`)

- **Hierarchy:** none; tabs are a set of open files. Finding a file needs a list behind a plus button, which is one of the other models.
- **What runs versus edited, marks:** the best: every open file shows its marks all the time (frame D1).
- **Back:** leaves the app; tabs are closed by hand.
- **One hand:** the tabs are at the top.
- **Tablet:** natural.
- **Accessibility:** a tab row is a known pattern; closing adds actions.
- **Cost:** medium, on top of whatever list finds the files.
- **Worst at:** space and upkeep. 40 dp for good (frame D2); three names fit, the rest scroll away with their marks (D3); a single-file shader gets a row for one tab (D4); closing tabs asks about unsaved text.

### Model E: one searchable list of everything (`sheet-model-e-one-list.png`)

- **Hierarchy:** none shown; the path is a second line under each name.
- **Everything in one list:** shaders, files of opened folders, examples, actions; most recent first; typing narrows.
- **What runs versus edited:** toolbar; marks on rows.
- **Back:** closes the list.
- **One hand:** poor; it opens with a keyboard.
- **Tablet and keyboard:** the best for a hardware keyboard.
- **Cost:** medium for the list. The index is the problem: knowing every file means reading whole folders, which SPEC 1 forbids on open; without it the list only knows what was opened before.
- **Worst at:** recognising. Half the library is named by a date (frame E4). And a folder has no place to be seen, so "where does a new file go" has no answer.

### Comparison

Taps for the frequent stories; then the principles each model keeps (✓), bends (~) or breaks (✗).

| | A drawer | B home | C sheet | D tabs | E one list | Recommended |
| --- | --- | --- | --- | --- | --- | --- |
| 1 ★ open another shader | 2 | 2, with a screen change | 2 | 2, and a tab more | 2 if recent, else typing | 2 |
| 2 ★ back to the file before | 3, +1 per level | 3, two screen changes | 2 to 3 | **1** | 2 | 2 |
| 5 ★ another file, one folder down | 3 | 3 | 3 | 3 | 2 and typing | 2 if in use, else 3 |
| 7 ★ to an error elsewhere | 2 | 2 | 2 | **1** if open | 2 | 2 |
| 6 read what runs, closed | cut off | cut off | ✓ | ✓ | cut off | ✓ |
| 7 see marks, closed | no | no | ✓ | ✓ | no | count in the toolbar |
| 3 new shader (with UX 2's sheet) | 3 | 2 from home, 3 from the editor | 3 | 3 | 3 | 3 |
| 9 an example, kept | 3 + copy | 3 + copy | 3 + copy | 3 + copy | 2 + copy | 3 + copy |
| Lines of code lost | 0 | 0 | 3 | 3 | 0 | 0 |
| Hundreds of unrelated files | scroll | scroll, full width | scroll in half a screen | — | **best** | filter; own files on top |
| P1 screen for picture and code | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ |
| P2 one column | ✓ | ~ three screens | ✗ two lists | ✗ | ✓ no levels | ✓ |
| P3 work in one tap at any depth | ✗ | ✗ | ~ | ✓ | ✓ | ✓ |
| P4 known by picture | ~ small | **✓** | ~ | ✗ | ~ | ✓ 40 dp |
| P5 looking changes nothing | ✓ | ✗ leaves the shader | ✓ | ✓ | ✓ | ✓ |
| P7 no scan | ✓ | ✓ | ✓ | ✓ | ✗ needs an index | ✓ |
| P10 same on a tablet | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ |
| Cost to build (my estimate) | small | large | medium | medium, plus a finder | medium, plus an index | medium |
| Worst at | the way back; closed it is mute | switching files | typing; two lists | finding; space | finding by picture; structure | reach of the trigger |

## 5. Recommendation

**Model A's column, with three additions taken from the others.**

1. **A section "Running" on top of the list** (from D: the files in use, one tap away, without a permanent row). It lists the shader that runs and the files its last build read. The set comes from the build, so nobody manages it and nothing is read for it.
2. **A toolbar that states both files** (from C and D: readable when closed). The frame rate moves into the quality control so that the second line can say what runs and in which folder.
3. **A filter in the drawer and "Last opened" on top of a folder that is not the open one** (from E: recent things first; narrowing by typing; both without an index).

And one rule that removes a menu entry: a tap on a shader runs it, unless the running shader already uses that file (decision D1).

Why the others lose:

- **B** spends the most code on the level used least (folders), stops the preview to show a file list, and still needs a switcher in the editor.
- **C** is the best for the thumb and the worst for typing, the one thing people do longest here; and it keeps two lists.
- **D** is the best switcher and no navigation. Its good part, the files in use at a glance, is kept as the Running section; its cost, a permanent row and tabs to close, is not. It stays on the table as an addition (decision D4 c).
- **E** needs an index the format forbids, and serves the people who recognise a shader by its picture worst. Its good parts are kept as the filter and "Last opened".
- **A alone** is right in structure and wrong on the frequent trip and on what the closed screen says.

**Weak points of the recommendation, plainly.**

- **The trigger is at the top left**, as today. Every switch starts with a reach. Tapping the title also opens the drawer, which is a larger target but no closer. Decision D4 offers a key in thumb reach.
- **A switch is two taps, not one.** Tabs do it in one.
- **Running is a second section and a second concept.** The same file can show twice, once under Running and once in its folder. People with one file per shader never see the section.
- **The tap rule has to be learned once**: a tap sometimes changes the picture and sometimes not. The toolbar says which, immediately, but it is a rule.
- **The open drawer covers four fifths of the code.** It is 328 dp of 412; today's is 240. That is the price of readable rows.
- **"Last opened" for a folder that is not open** shows unsaved files from the draft journal and the shader that ran last from app metadata; anything else there would need storage and is left out.
- **The location line depends on the provider.** For a folder from Android's picker only the provider's label is certain (first-attempt lessons). The path shown in the mock-ups ("Documents / code") needs `DocumentsContract.findDocumentPath`, which exists from Android 8 and which a provider may not answer. Where it does not, the line is the provider's name and the folder's name.
- **Thumbnails for shader files need a store that does not exist yet.** Until it does, a shader file shows a file icon.
- **The edge swipe competes with the system's back gesture**, as today's drawer does. On gesture navigation the drawer is opened by its button or the title.

## 6. The recommended design in detail

### 6.1 The parts

**The drawer** is 328 dp wide on a phone (the standard maximum is the screen minus 56 dp), under the toolbar as today, with a background dark enough to read on; today's shows the code through it.

```text
┌ header ─ arrow up │ path, last part bold │ filter │ menu ─┐   60 dp
│          second line: where the folder is                │
├ list ────────────────────────────────────────────────────┤
│  Running                (only in the open folder)        │
│    the shader that runs                                  │
│    the files it uses, with their folder                  │
│  All files              (this folder, one level)         │
│    folders, then files, by name                          │
├ bar ─ New ───────────────────────────────────────────────┤   60 dp
```

**A row**: a 40 dp place for a thumbnail (a shader that has one, an image) or an icon (folder, file, image); the name, with its folder in front in dimmer type where the row is not in its folder; an optional second line; marks at the end. 48 dp high, 56 dp with a second line, 44 dp for the files under the running shader.

**Marks**, in this order at the end of a row, each with a spoken text:

| Mark | Means | Spoken |
| --- | --- | --- |
| ▶ | this file runs | "running" |
| ● | unsaved text; on a folder: in it | "unsaved" |
| error icon and a number | errors of the last build in this file; on a folder: in it | "3 errors" |
| warning icon and a number | the same for warnings | "1 warning" |
| two arrows with an exclamation mark | changed outside while it has unsaved text | "changed outside" |
| lock | cannot be changed | "read-only" |
| wallpaper icon | this shader is the installed wallpaper | "wallpaper" |
| › | a folder; a tap goes in | (the row says "folder") |

The file in the editor has the selected background and is announced as selected.

**The toolbar**: menu button; title; frame rate and quality; error button with a count when the last build has errors or warnings; Run when the run mode is manual; the eye; the menu. The title is the name of the file in the editor, with ● while it has unsaved text. The second line is, when another file runs, "▶ that file · folder", otherwise the folder ("Shaders", "aurora"). A tap on the title opens the drawer.

### 6.2 Levels

| Level | Header | List | Bar | Picture |
| --- | --- | --- | --- | --- |
| **Folders** | "Folders", no arrow | Shaders; Examples; then **Recent**: folders and single files opened before, newest first, each with where it is | Open folder; Open file | `r-03` |
| **Shaders** (the library) | "Shaders", "23 shaders" | Every shader: thumbnail, name or date, date of last change. Before the migration these are the database shaders, afterwards the files of `ShaderEditor/Shaders`; the list is the same | New | `r-02` |
| **Examples** | "Examples", lock, "Read-only" | The four sections of EXAMPLES 2, each example with its line | New shader | `r-01` |
| **A folder's root** | its name; where it is | Running or Last opened; All files | New | `r-04`, `r-29` |
| **A folder inside** | the path from the root, last part bold, shortened at the start | the same | New | `r-06`, `r-07` |

### 6.3 What a tap, a long press and Back do

| Gesture | Where | Effect |
| --- | --- | --- |
| Tap | menu button, or the title | Opens the drawer on the folder of the file in the editor, that row selected and scrolled into view |
| Tap | the arrow in the header | One level up; from a folder's root to Folders |
| Tap | a part of the path | That level. The "…" at the start opens the whole path as a menu |
| Tap | a folder row | Into that folder. Nothing else changes (principle 5) |
| Tap | a file the running shader uses | Opens it in the editor; the shader keeps running. The drawer closes |
| Tap | another file that can run (it defines `main`) | Opens it and runs it; it becomes the folder's running shader (SPEC 1, rule 1). The drawer closes |
| Tap | another text file | Opens it; the shader keeps running |
| Tap | an image | A sheet with the image, its size, and **Insert as texture** (`r-23`) |
| Tap | a file the editor cannot open | Nothing; the row is dimmed and says "cannot be opened here" |
| Long press | a file or folder row | Its menu (6.6). Also offered to TalkBack as actions of the row and to a keyboard by the menu key |
| Tap | filter | The header becomes a text field; typing narrows the list shown (6.7) |
| Swipe left on the drawer, tap beside it | | Closes it |
| Back | | Closes, in this order: the keyboard, a sheet or menu, the filter, the drawer. Then leaves the app |
| Edge swipe from the left | | Opens the drawer where the system leaves the gesture to the app (three-button navigation) |

Transitions: a level change slides the list by its width, in the direction of travel, 200 ms, and is instant when the system's animations are off. The header and the bar do not move. Opening a file closes the drawer; opening a folder does not.

After Android's picker returns a folder, the drawer is open on that folder's root. If it was open before, "Last opened" is on top; if the rule of SPEC 1 finds a shader (a manifest entry or `main.glsl`), it is the first row under **Default shader**; otherwise the hint of 6.8 (`r-10`). Nothing runs until a file is tapped (decision D2).

With a hardware keyboard: Ctrl+O opens the drawer with the filter focused, arrows move, Enter opens, Escape closes, Ctrl+S saves, Ctrl+Enter runs. Not drawn.

### 6.4 The sections

**Running** appears in the open folder when the running shader's last build read more than one text file, or when a file other than the one in the editor has unsaved text, an error or a conflict. It holds the running shader, then the files it read (includes, passes, images) with their path from the root, then any file with unsaved text, an error or a conflict that is not among them. More than six: the first six and "Show all 14". With one file per shader the section is absent and the ▶ sits on the shader's row in the list.

**Last opened** takes its place in a folder that is not the open one: the shader that ran there last and the files that still have unsaved text.

**All files** is the folder shown: folders first, then files, by name; in the library by last change first, as the setting says today. Names that start with a dot are left out. The heading is absent when no section is above it.

### 6.5 Every label

Drawer: `Folders` · `Shaders` · `Examples` · `Recent` · `Running` · `Last opened` · `Default shader` · `All files` · `Read-only` · `%d shaders` · `%d items` · `single file` · `Ran here last` · `Show all %d` · `New` · `New shader` · `Open folder` · `Open file` · `Save a copy` · `Filter` (spoken) · `Up` (spoken) · `In %s` · `%1$d of %2$d`.

Drawer menu: `Refresh` · `Last changed first` · `By name`.

Row menu: `Run this file` · `Rename` · `Duplicate` · `Delete` · `Set as default shader` · `Insert as texture` · `New file here` · `Remove from list`.

Toolbar menu: `New shader` · `Save` or `Save %d files` · `Save a copy` · `Delete` · `Share` · `Set as wallpaper` or `Update wallpaper` · `Run this file` · `Add uniform or texture` · `Add include` · `Examples` · `Settings` · `FAQ` · `Show extra keys`.

New sheet: `New` · `Shader` · `Empty file` · `Name` · `Location` · `Change` · `Cancel` · `Create`.

Image sheet: `Insert as texture` · `Rename` · `Already used by %s`.

Conflict: `%s was changed outside ShaderEditor and has unsaved text here.` · `Reload from file` · `Keep my edits` · `Save a copy`.

Errors: `Showing the last picture that worked` · `Details`.

All of them need the Russian, Ukrainian and both Chinese files (AGENTS 15); until translated they carry the English text, as EXAMPLES 7 decided for examples.

### 6.6 Where every action is

| Action | Place | Notes |
| --- | --- | --- |
| New | Drawer bar; toolbar menu ("New shader") | The sheet of UX 2 (`r-22`): name, location, Create. From the drawer the location is the folder shown and there is a choice **Shader** or **Empty file**; a shader has `main` and therefore runs, an empty file opens beside what runs. That is UX 2's "New" and "New file" as one sheet |
| Open folder, Open file | Bar of the Folders level | Android's picker |
| Run this file | Long press; toolbar menu, only when the file in the editor is not the running one and can run | Mostly replaced by the tap |
| Set as default shader | Long press on a shader in a folder | UX 3; writes the manifest |
| Rename, Duplicate, Delete | Long press | Delete also in the toolbar menu for the file in the editor, so that it stays three taps as today |
| Save, Save a copy, Share, Set as wallpaper | Toolbar menu | They act on what is edited and what runs |
| Insert as texture | The image's sheet; long press on an image | UX 4's "existing project-local file in place" |
| Remove from list | Long press on a row under Recent | UX 6: only the shortcut. If the folder has unsaved text the question says so |
| Refresh; sort order | Drawer menu (`r-25`) | Refresh also runs when the app returns to the front |

### 6.7 Empty states and how it degrades

| Situation | What is shown | Picture |
| --- | --- | --- |
| No shader in the library | "No shaders yet. A new shader is saved here, in ShaderEditor / Shaders." · New shader · Examples | `r-19` |
| No file in the editor | The editor is replaced by "No file open. Create a shader or pick an example." It is not a text field, so nothing can be typed into nowhere | `r-19` |
| An empty folder | "Nothing in this folder" · New | — |
| A folder where nothing runs | "Nothing here runs yet. Tap a shader to open and run it." | `r-10` |
| Filter finds nothing | "No name here contains “…”" | — |
| The listing takes long | The header, Running and the bar are there at once. Under All files a spinner; after two seconds "Still reading this folder…" and **Stop**. Stop leaves the level usable with what is known | `r-18` |
| The listing fails | The reason in one line and **Try again** | — |
| Access to the folder is lost | "ShaderEditor can no longer open this folder. …" · Choose folder again · Save a copy · Remove from list. Files with unsaved text stay listed and editable | `r-17` |
| A very large folder | Rows arrive in pages of 200 with "Show more"; the filter works on what has arrived and says "%1$d of %2$d" | `r-08`, `r-09` |
| A file over the editor's limit (16 MB, EDITOR D7) | Dimmed, with its size; needs the size from the listing, not a read | — |
| No thumbnail | The file icon | — |
| The provider gives no path | Second line: the provider's name and the folder's name only | — |
| A single file opened from another app | A level with that one file and **Open folder** | `r-20` |
| Read-only folder | Lock in the header; one sentence and Save a copy; New is replaced by Save a copy; the row menu has no Rename or Delete | `r-15` |
| A conflict | A bar in the file itself with the three actions of UX 5; the row carries the mark | `r-16` |

The filter searches names only, in the list that is shown. It does not walk into folders; that would be the scan principle 7 excludes. A search through subfolders can be added later as an explicit, cancellable action.

### 6.8 Every screen and state

| State asked for in the brief | Picture |
| --- | --- |
| First run with only examples | `r-01-first-run` |
| Several database shaders and no project | `r-02-library`, `r-03-folders` |
| One open project with five files and a subfolder | `r-04-project-root`, `r-05-editing-include`, `r-06-subfolder`, `r-29-browse-other` |
| A deep folder | `r-07-deep-folder` |
| A folder with hundreds of unrelated files | `r-08-many-files`, `r-09-filter` |
| Nothing running | `r-10-nothing-running`, `r-19-empty-library` |
| An error in a file that is not open | `r-11-error-elsewhere`, `r-12-error-list`, `r-13-error-drawer` |
| Unsaved files | `r-14-unsaved` |
| A read-only folder | `r-15-read-only` |
| A conflict | `r-16-conflict` |
| A lost folder grant | `r-17-lost-grant` |
| Slow storage, a single file | `r-18-slow-folder`, `r-20-single-file` |
| Actions | `r-21-row-menu`, `r-22-new`, `r-23-image`, `r-24-toolbar-menu`, `r-25-drawer-menu`, `r-26-keyboard` |
| Landscape | `r-27-landscape` |
| A tablet width | `r-28-tablet` |

On a phone in landscape the drawer is the same, with 44 dp rows. From 840 dp of width it stays open beside the code and the menu button folds it away; below that it is the phone's drawer, also on a small tablet in portrait.

### 6.9 What happens to each thing in today's drawer and menu

| Today | Becomes |
| --- | --- |
| Drawer: list of database shaders, 24 dp thumbnail and title | The level **Shaders**: 40 dp thumbnail, title, date of last change |
| Drawer: tap opens and runs | The same |
| Drawer: long press renames at once | Long press opens the row's menu; Rename is its second entry. One tap more for rename; in exchange duplicate and delete work on any shader without opening it |
| Drawer: "No shaders" text | The empty state of 6.7 |
| Drawer: sorted by last change when the setting says so | The same order, chosen in the drawer menu. The setting "Sort by last modification" leaves Settings; its value is carried over |
| Toolbar: title, frame rate below | Title; below it what runs and the folder; the frame rate joins quality (D3) |
| Toolbar: quality spinner | The same list, behind the control that also shows the frame rate |
| Toolbar: error button, Run, eye, menu | Unchanged; the error button gains the count. Eye and menu get spoken names, which they lack today |
| Menu: Undo, Redo | Unchanged |
| Menu: New shader | Stays, and is also the drawer's New. Opens the sheet of UX 2; that sheet costs one tap more than today's immediate creation, which is UX 2's decision, not this proposal's |
| Menu: Save shader | "Save", or "Save 2 files" when more than one has unsaved text |
| Menu: Save as new shader | "Save a copy" (UX 5). Duplicate on long press is the quick form |
| Menu: Delete shader | "Delete", for the file in the editor; also on long press for any file |
| Menu: Share shader | "Share" (UX 7) |
| Menu: Set as wallpaper / Update wallpaper | Unchanged; it applies what runs |
| Menu: Add uniform/texture | "Add uniform or texture"; beside it "Add include" from Gate A |
| Menu: Examples | Stays as a shortcut: it opens the drawer on the level Examples. The separate Examples screen goes when examples become a folder (X10) |
| Menu: Settings, FAQ, Show extra keys | Unchanged |
| Settings: wallpaper shader, default new shader | Unchanged by this proposal |

### 6.10 The first attempt's lessons, one by one

From `.foreman/proposals/first-ui-lessons.md`.

| Lesson | Here |
| --- | --- |
| The title area is crushed; "Preview: main.glsl" and the error count are cut off; the project's name is never visible | The frame rate leaves the second line (D3). The second line is "▶ file · folder" and is cut from the end, so the folder goes first and the running file last. The error count is on the error button |
| Switching files takes three taps and the sheet reopens at the root | Two taps for the files in use, at any depth. The drawer opens on the folder of the edited file |
| Run this file is buried in the menu | A tap on a shader runs it (D1). The entry remains on long press and, when it applies, in the toolbar menu |
| With no file shown the editor is empty and swallows typing | The editor is replaced by an empty state that is not a text field. And it is rare: entering a folder does not empty the editor (D2) |
| Folders and shaders share one scroll list | Levels: the library is one folder among the others |
| "Local storage" says nothing about where a Downloads folder is | The second line shows the provider's name and, where the provider answers, the path; otherwise the provider's and the folder's name. Accepted as a limit of Android |
| A tap on an image shows a toast | A sheet with the image and Insert as texture |
| The conflict dialog cannot be dismissed and asks one file at a time | A bar in the file concerned; other files with a conflict carry the mark and are listed under Running |
| Removing a recent folder warns that unsaved changes are lost | Kept; the question names how many files |
| `ShaderManager` defines "what is open" as a database row; ten "is a project open" branches | One adapter for "what is open" with two implementations, built first (N1 in section 8). The activity asks it for title, text, save, delete; it never asks "is this a project" |
| `ShaderListManager` is a `ListView` of rows addressed by long ids; folders only fit into a header view | A new list with its own row model (N2), fed by a library source or a folder source. The old manager is not extended; it is deleted at the end |
| The menu is a fixed set of database actions in XML | The menu is built from what the open item can do |
| One `UndoRedo` on a process-wide history; setting the text fires "modified" and "edit paused", which rebuilds and repeats the error message on every switch | Needs the editor to show a document without those side effects; the merged `editor/Document` is the per-file state. Listed as a dependency of N6 |
| `ErrorListModal` cannot survive rotation | Out of scope here; noted for M6 |
| No thumbnails for projects, no sizes or dates, writability not exposed, one listing per folder level on each read in picker folders, `load` reads before it can refuse | Section 8 lists these as what the object must gain. Until then: file icons, no size limit shown, no "last changed first" outside the library, and New and Rename offered and refused with a reason |
| "Showing last successful build" and an empty preview are not available from the engine | The error list's first line and the empty state need both; listed in section 8 |
| Provider change notifications are not used | Not needed: refresh when the app returns and from the drawer menu |
| Reload from file drops the draft | Not a navigation matter; noted for M9 |

## 7. Pictures

All under `.foreman/show/nav-design/`. `node build.mjs && ./render.sh` regenerates every page and picture; `lib.mjs` has the parts, `frames-recommended.mjs` and `frames-models.mjs` the screens, `assets/nav.css` the styles. Rendering used Playwright's headless Chromium, which is installed on this machine; `render.sh` falls back to Google Chrome.

The mock-ups use the app's own material: colours from `colors.xml` and `syntax_colors.xml`, the editor font `jetbrains_mono.ttf`, icons from the app's vector drawables (the folder, file and image icons from the first attempt's branch), thumbnails from `drawable-nodpi/thumbnail_*.png`, and the list style of today's Examples screen as seen on the emulator. Icons the app does not have (arrows, lock, pencil and a few more) are standard Material shapes. The pictures behind the code are gradients or blurred thumbnails, not running shaders. The three screenshots of today's app and the five of the first attempt on sheet 0 are real.

## 8. Consequences

### 8.1 Sentences of `docs/project-mode/UX.md` that would change

| Where | Now | Would become |
| --- | --- | --- |
| UX 1 | "The existing recent/library surface gives quick access to previously opened shaders/folders and existing DB shaders." | The drawer is one column: Folders (the library "Shaders", "Examples", and what was opened before), then a folder, then its files |
| UX 1 | "The in-app file list only navigates the currently open folder." | The drawer shows the folders the user has opened and, inside each, its files. It never shows storage the user has not granted; choosing a location stays with Android's picker |
| UX 2 | "Location, defaulting to the open folder." | Location, defaulting to the folder the drawer shows |
| UX 2 | "**New file** in the file list does the same without changing the running shader, for include files and passes." | New in the drawer offers Shader or Empty file. A shader runs; an empty file opens beside what runs |
| UX 3 | "It loads the file list lazily and picks the running shader by the one rule in SPEC 1 … If none applies, open the folder with nothing running and show **Open a shader, then Run**." | It shows the folder in the drawer. The shader the rule picks is the first row; a tap opens and runs it. Nothing runs before that (D2) |
| UX 3 | "Keep the running shader visible in the preview title, for example `Preview: main.glsl`." | The toolbar's second line names the running shader when it is not the file in the editor: "▶ main.glsl · aurora" |
| UX 3 | "**Run this file** is a file/editor action that selects a different GLSL file as the running entry, remembered locally for that folder." | A tap on a file that can run and that the running shader does not use selects it as the running entry, remembered for that folder. Run this file does the same on request for any file that can run (D1) |
| UX 3 | "For a folder with nothing running, opening a GLSL file can focus the file; Run then selects it. … No hidden heuristics that classify arbitrary source text as a complete shader." | A file can run when it defines `main`. That is read from the one file being opened, never from a scan; a file without it opens for editing only |
| UX 6 | "Rename a file in the open-folder list using one name field." | Unchanged; the place is the row's menu |
| UX 9 | "Long filenames retain a way to see the full path" | The drawer's header shows the path; rows under Running show each file's folder |

SPEC 1's rule for the running shader keeps its four steps. Step 1 ("the file the user last chose with Run this file") would read "the file the user last ran in this folder".

### 8.2 What the open-project object must provide

It has today (`project/OpenProject`, merged): `list(folder)`, `load`, `text`, `shown`, `show`, `unsaved`, `conflicts`, `refresh`, `running`, `findRunning`, `runThisFile`, `prepare`, `diagnostics(file)`, `errors`. The design additionally needs, in the order the pull requests need them:

| Need | For | Cost |
| --- | --- | --- |
| Entries with size and time of last change, from the query that lists them (`ProjectFiles.Entry` has name and folder only) | Dimming files over the limit without reading them; "last changed first"; the library's date line after the migration | Columns of the same query; none extra |
| The ids of listed children kept, so that opening a listed row is one read (today each read lists every folder on the path) | Opening a file three levels down without three listings | Memory |
| **What the last build read**: paths in order, each with its kind (text, image). `Preparation.dependencies` has it; the object has to keep and expose it, and answer "does the running shader use this path" | The Running section; the tap rule | None; the build happened |
| **Can this file run**: does its text define `main`, from the lexer, for one loaded file | The tap rule; when Run this file is offered | One pass over a text already in memory |
| Marks for a folder: how many files under a path are unsaved, have errors, have a conflict | Marks on folder rows | Memory |
| What the store can do: write, create, rename, delete | Read-only state; which row actions exist | The store knows; expose it |
| One listener, called on the main thread when unsaved, diagnostics, running, used files or conflicts change | Drawer and toolbar redraw from one event instead of being pushed from many places | Small |
| The folder's name and where it is, on the object (today only in the recents model) | Header, toolbar | Small |
| A thumbnail per shader file, kept as app metadata by session and path, captured when the shader has run | Row pictures outside the library | A new small store |
| From the engine: whether an earlier build succeeded; an operation that clears the picture | "Showing the last picture that worked"; the empty state | Engine work, already named in the lessons |

### 8.3 What the recents model must provide

It has (`ProjectSessions`): sessions by id, `Recent(id, locator, name, location, opened, unsaved)`, `recent()`, `remember`, `touch`, `running(id)`, `remove`. It needs:

- **For a folder that is not open**: the file shown last, beside the running choice it already keeps; and the paths with unsaved text, which its draft journal can list without opening the folder.
- **Two fixed entries** that are not sessions the user can remove: the library and the examples.
- **Where it is**, as good as the provider allows: its label, plus the path where the provider answers.
- **The last open folder and file**, to restore after the process died (story 12).
- **Whether it can still be reached** is not stored. It is found out when the folder is entered, and shown then.

And one seam outside both: **the library as a list source**. Before the migration it reads the database (id, title, thumbnail, date); afterwards it is the folder source like any other. The drawer has one adapter over one row model and never asks which.

### 8.4 A path of small pull requests

All behind the existing gate `@bool/project_mode` (on in debug builds only). This path takes the place of the screen parts of M10 and M11 in the plan; their non-visual parts are merged. "Emulator" means the change has to be looked at in the running app.

| # | Change | Check |
| --- | --- | --- |
| N1 | One adapter for "what is open" (title, second line, text, unsaved, save, delete, copy, share, wallpaper), implemented for a database shader with today's behaviour. `MainActivity` and the managers use only it. No visible change | JVM; emulator once, for no change |
| N2 | The navigation model in plain Java: levels, the row model, sections (Running, Last opened, All files), order, filter, marks for folders, and the tap rule as a function. Tests for each | JVM |
| N3 | What 8.2 asks of the object and the store, each with tests | JVM |
| N4 ★ | The new drawer view, showing the library only: wider, header, rows with date, long press menu, order in the drawer menu. Old and new drawer chosen by the gate in one place | Emulator |
| N5 ★ | The Folders level, Recent, Open folder and Open file through the picker; entering a folder lists it; loading, failure and lost access | Emulator |
| N6 ★ | Opening a file of a folder: the second implementation of N1's adapter; the tap rule; the toolbar's title and second line; frame rate with quality; Running and the marks. Depends on the editor showing a document without firing "modified" | Emulator |
| N7 ★ | The New sheet; the row menus for files and folders; the toolbar menu built from what the open item can do | Emulator |
| N8 ★ | The image sheet and Insert as texture (with M12) | Emulator |
| N9 ★ | Read-only, the conflict bar, Save a copy through the New sheet (with M13) | Emulator |
| N10 ★ | Examples as a folder (with X10); the Examples menu entry opens the drawer there | Emulator |
| N11 ★ | Filter; landscape; the drawer kept open from 840 dp; keyboard shortcuts; spoken names and row actions | Emulator; TalkBack on the phone |
| N12 | Thumbnails for shader files | Emulator |
| N13 ★ | With the gate lifted: delete `ShaderListManager`, `ShaderAdapter`, `drawer.xml`, `row_shader.xml`, the fixed `main_menu.xml`, the "Sort by last modification" setting, and after X10 the Examples screen with its fragment and adapter | Emulator |
| — | With M21: delete the database list source and the database implementation of N1's adapter | JVM |

N1 to N3 change nothing a user sees and can go first, in parallel with the editor track.

## 9. Open decisions for the owner

Each with my recommendation first.

**D1. What a tap on a file does to what runs.** This decides whether the most frequent story costs two taps or five.
- **a (recommended).** It runs the file, unless the running shader already uses it or it cannot run. Opening a shader shows that shader; opening a part of the running picture keeps the picture. Costs one rule to learn and the test "defines `main`".
- b. A tap only opens; Run this file is always explicit, as UX 3 says now. Simple to state; then every shader opened from the library shows its code over another shader's picture until a second action.
- c. A tap always runs the file. Today's behaviour; breaks editing an include or a pass, which would replace the picture by an error.

**D2. What entering a folder does**, from the list or after the picker.
- **a (recommended).** It shows the folder. The shader that ran there last, or the default shader, is the first row; a tap runs it (`r-29`, `r-10`). Looking is free; a folder that is huge, slow, broken or holds a shader that freezes the phone can be inspected first. One tap more to resume.
- b. It opens the folder and runs its shader at once, as UX 3 says now. One tap less; entering Examples or the library just to look would replace what is open.
- c. The picker runs it, a row in the list only shows it. Follows UX 3 to the letter; two behaviours for one act.

**D3. Where the frame rate goes.** Picture: `sheet-decisions.png`, first row.
- **a (recommended).** Into the quality control ("58 fps · 1/2"), which still opens the quality list with one tap. Frees the second line for what runs and the folder.
- b. It stays in the second line, after the running file. Nothing moves; the folder has no place, and with the error button or longer names the line is cut off as in the first attempt.

**D4. A faster way back to the file before.** Picture: second row.
- **a (recommended for the first version).** None: two taps through Running. Nothing is added to the screen; decide again after using it.
- b. A key at the start of the extra-keys row that names the previous file. One tap, in thumb reach while typing, no line of code lost; absent when extra keys are switched off.
- c. A tab row for the files in use, shown only when there is more than one. One tap and all marks in view; three lines of code and a second place to look.

**D5. What Back does.**
- **a (recommended).** It closes what is on top, then leaves the app.
- b. With the drawer open it goes up one level first. Familiar from file managers; leaving the drawer from a deep folder takes several presses.
- c. It also returns to the previous file. One-tap "back to main.glsl" for free; leaving the app then takes as many presses as files were visited.

**D6. Where Examples is reached.**
- **a (recommended).** A folder at the top of the drawer, and the menu entry stays as a shortcut to it. Two taps as today.
- b. Only in the drawer. One entry less; three taps.
- c. It stays a separate screen. No change for the learner; a second list design to keep.

**D7. Wide screens.**
- **a (recommended).** From 840 dp the drawer stays open beside the code (`r-28`).
- b. Always the phone's drawer. Nothing to build; a tablet shows a third of a screen of list over an empty rest.

**D8. The library's rows.** Picture: third row.
- **a (recommended).** A list: 40 dp picture, name, date. Eleven per screen, one row design for everything.
- b. A grid of 108 dp pictures. Eight per screen, better for recognising, a second list design.

**D9. File names in the library after the migration.**
- **a (recommended).** As files are named: "Plasma.glsl". The same everywhere.
- b. Without the ending in the library only. Looks exactly as before the migration; two rules for showing a name.

## 10. Not verified

- **Nothing was built.** The design has not been used in a running app. Its tap counts come from the mock-ups.
- **The first attempt was not run by me.** Its taps and behaviour are from its code (`FileListSheet`, `ProjectManager`, `MainMenuManager` on `origin/proto/project-ui-slice1`), its twenty screenshots and its author's lessons file.
- **Lines of code with the keyboard up** (30; 27 with a bar or tabs) are from the mock-up's geometry with a 290 dp keyboard. On the emulator the soft keyboard did not appear when the editor was focused.
- **How the drawer's edge swipe behaves under gesture navigation in this app.** I describe the platform's usual behaviour; I did not try it.
- **Whether `TouchThruDrawerLayout` can be kept open on a wide screen** with the preview behind it without a new layout.
- **`DocumentsContract.findDocumentPath`**: that it exists from Android 8 is from memory; which providers answer it, I did not test. The location lines in the mock-ups show the good case.
- **The cost of listing on slow providers.** No measurement; the two-second message is a guess at a threshold.
- **TalkBack was not run.** The spoken texts are specified, not heard.
- **Persona sizes.** There is no usage data; the tracker shows who writes issues, not who uses the app. `docs-site` was read by file name only.
- **The size of N1.** How much of `ShaderManager` can move behind one adapter without behaviour change is an estimate from reading it, not from doing it.
- **That a shader file "defines `main`" is always decidable from its own text.** A `main` that comes from an include, or a ShaderToy `mainImage`, would make a file runnable that the test calls not runnable; Run this file on long press covers it, by hand.

## Decisions of 2026-11-12

The owner took every open decision as recommended (option a in each):

| # | Decision | Taken |
| --- | --- | --- |
| D1 | What a tap on a file does to what runs | Runs it, unless the running shader already uses it or it has no `main` |
| D2 | What entering a folder does | Shows it, with the shader that ran there last as the first row; nothing starts running by entering |
| D3 | Where the frame rate goes | Into the quality control; the toolbar's second line names what runs and the folder |
| D4 | A faster way back to the previous file | None in the first version: two taps through Running |
| D5 | What Back does | Closes what is on top, then leaves the app |
| D6 | Where Examples is reached | A folder at the top of the drawer, plus the menu entry as a shortcut |
| D7 | Wide screens | The drawer stays open from 840 dp |
| D8 | Library rows | A list with a 40 dp picture, name and date |
| D9 | File names in the library after migration | As the files are named, for example `Plasma.glsl` |

D1, D2 and D3 change sentences of UX.md; UX.md is to be edited accordingly before the first screen is built. The mock-ups this document refers to are not part of the repository.
