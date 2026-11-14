# File lifecycle: who keeps a project file's truth

status: proposal, for the owner to decide (section 8)
written: 2026-11-13, design sub-agent, on `integration/project-mode` at `f2da2e33`
answers: `.foreman/reviews/project-core-2.md` (verdict "rework") and the owner's questions on memory, undo, drafts and flash wear

How to read the evidence marks: **[code]** I read it in this checkout, **[doc]** quoted from documentation or source I fetched today, **[probe]** I ran it (throwaway, outside the repo), **[review]** the reviewer ran or traced it, **[memory]** from my memory and not checked, **[judgement]** my assessment.

## 0. The answer in short

1. **One owner.** A main-thread `FileRecord` per path owns the file's truth: the `Document` (if loaded), the storage revision the text was last equal to (`base`), what storage was last seen to hold (`seen`), and how far the journal has acknowledged. Nothing else stores a base, a conflict or a "drafted" flag. The worker does I/O and holds no truth; it gets immutable requests and returns immutable outcomes.
2. **One adoption rule.** A file has at most one storage operation in flight. Its result is adopted only by the record, only if it is the operation the record is waiting for, and it may replace text only if the document is still at the revision the request captured. Text and base are always assigned together, on main. This closes the review's findings 1 to 4 by construction, not by guards in each method.
3. **A self-contained journal per unsaved file**: a snapshot of the text plus appended change records, in app-private storage. Appended a few seconds after typing without a forced sync; synced when the app goes to the background. A journal exists exactly while the file has unsaved text. Recovery never needs the stored file, so it works when the file changed elsewhere, was deleted, or the folder is gone.
4. **Undo stays as it is in memory.** It already survives a save. I recommend persisting it in a separate small history file, written when a document leaves memory and when the app goes to the background, tied to the text by content hash and ignored on any mismatch (Neovim's undo-file idea, different trigger). It is a cache: deleting it loses nothing but undo.
5. **Memory is a byte budget**, not "modified plus five clean". The shown document always stays; everything else (text, history, per-line state) shares a budget of heap/8, between 8 and 32 MB, least recently used first out. Because journal and history file hold everything, a dropped document costs the user a reopen of some milliseconds and nothing else. Modified files may be dropped too.

What this does not change: `Text`, `Document`'s undo design, the store interface and its content-hash revisions, selected-file-only preparation. The review said to keep those; I agree after reading them.

## 1. The model

### 1.1 The parts and what they are relative to the owner

| Part | Is | Is not |
| --- | --- | --- |
| `FileRecord` (new, main thread) | THE owner of one path's truth: which text is current, what it is based on, what storage was seen to hold, whether it is protected | a doer of I/O |
| `Document` | the live text, selection and undo of a loaded file; the record holds it and is its only writer besides the editor view | the owner of "saved": it only knows a saved *state number* the record gives it |
| Store (`ProjectFiles`) | the user's files; a revision is the content hash of the bytes **[code]** | a place that remembers anything about the editor |
| Journal (new format, worker only) | the durable copy of unsaved text, one file per unsaved path, self-contained | a source of truth while the app runs: it follows the record, and is read only at open/recovery |
| History file (optional) | a cache of a document's undo steps, valid for exactly one text (hash) | needed for anything |
| Worker | one serial queue per process; runs every store and journal call | holder of bases, conflicts or drafts (today's `FileTexts.bases` and `EditingSession` are exactly that and are the cause of findings 1, 17, 18) |

### 1.2 What a record holds

```
path
document   Document or null            loaded or not
generation int                         counts the documents this record has had; a result for an earlier one is void
base       revision | ABSENT           storage content the text was last equal to (adopted, never merely read)
seen       SAME | CHANGED(r) | MISSING | UNAVAILABLE(reason)
                                       the latest observation of storage; a fact, not adopted
kept       boolean                     the user chose "Keep my edits"; auto-save skips the file until an explicit save
unsaved    boolean                     document.isModified(), or for an unloaded file: a journal exists
journal    NONE | AT(revision) | FAILED(reason)
                                       how far the worker has acknowledged the journal
op         the one operation in flight (LOAD, SAVE, CHECK) with its ticket, or null; others wait their turn
```

Everything the drawer and toolbar show is derived from these fields (section 6).

### 1.3 The states

| # | State | In fields |
| --- | --- | --- |
| S0 | Not loaded | no document, `unsaved` false. Costs nothing. |
| S1 | Clean at R | document, not modified, `base` = R, `seen` SAME |
| S2 | Modified on R | modified, `seen` SAME. Protected once `journal` = AT(current revision) |
| S3 | Modified, storage moved (conflict) | modified, `seen` CHANGED(R') or MISSING |
| S4 | Storage missing or unreadable | not modified, `seen` MISSING or UNAVAILABLE. The text is shown with a notice; a build treats the file as storage has it (absent), so there are no ghosts |
| S5 | Unloaded with unsaved text | no document, `unsaved` true (journal exists). Appears after a restart ("recovered") and after a modified document was dropped from memory. Becomes S2 or S3 on open |
| — | Read-only | not a state: a capability of the store (`offers`; the view over the database is such a store today **[code]**). The file is S1/S2 like any other; Save reports "read-only" and the journal keeps the text |
| — | Over the size limit | never gets a document: `open` answers TOO_LARGE before reading the bytes |
| — | Recovered, base present / changed / gone | S5 with `seen` SAME / CHANGED or MISSING / UNAVAILABLE, set by the check at open. Same states, no special case, because the journal does not need the base |

```
S0 not loaded ──open──► S1 clean at R ──edit──► S2 modified on R ──storage seen changed──► S3 conflict
      ▲                   │  ▲  ▲                 │  ▲                                       │  │
      └────── drop ───────┘  │  └─ save, undo ────┘  └──── Keep my edits (base := seen) ─────┘  │
                             └─────────────────── Reload from file (adopted) ───────────────────┘

S1 ──storage seen missing or unreadable──► S4
S2, S3 ──drop from memory, or the process ends──► S5 ──open──► S2 or S3
```

### 1.4 The transitions

Every decision is taken on the main thread. "Worker" means the request is executed there and the outcome comes back to main for adoption.

| Event | From | Triggered by | Result | Durable afterwards |
| --- | --- | --- | --- | --- |
| open | S0 | user (tap), main; worker reads the store with a byte bound | S1 with text and base assigned together; or a problem (missing, unavailable, too large, not text) | nothing new |
| open | S5 | user; worker reads journal (snapshot + replay) and checks the store revision | S2; S3 if the store is not at the journal's base; S1 if the store holds exactly the journal's text | nothing new |
| edit | S1, S2, S3 | user, main, synchronous in `Document` | S2 (or stays S3) | nothing yet; the record schedules a flush |
| flush | S2, S3 | record's timer: 2 s after the last change, at most 5 s after the first unflushed one; at once on file switch, save, run (choosing what runs, and every build) and background | same state, `journal` = AT(revision) on the worker's acknowledgement; FAILED(reason) otherwise, visible and retried | the text, in the journal: survives a process kill at once, a power loss after the next sync |
| undo back to the saved text | S2 | user | S1 | journal deleted after the next flush |
| save | S2 (with `kept`: explicit saves only); never S3, which must be resolved first; S4 with the file missing, explicit saves only, expecting no file (D6) | user or auto-save, main captures (text, state, base); worker writes with `expected = base` | written at R2: S1 at R2, or S2 on R2 if typed meanwhile. Refused as changed: S3 with `seen` = what was found. Failed: unchanged, problem shown, Retry | the store holds the text; journal deleted (or re-based if typed meanwhile) |
| check (resume, Refresh) | every loaded or journaled file | lifecycle or user; worker reads revision, and bytes if changed | clean and changed and untouched since the request: S1 at R' (text replaced as one undoable step). Clean and missing/unreadable: S4. Modified and changed/missing: S3. Modified and storage holds exactly my text (our own save finished just before a crash, or someone wrote the same): S1 at that revision. Otherwise nothing | nothing changes on disk, except that a journal equal to storage is deleted |
| Reload from file | S3 | user, naming the revision the dialog showed | a check; adopted as for a clean file only if the document is untouched since the tap and storage is still what the dialog showed. Then S1 at R'; the user's text is the undo step before it | journal deleted only now, after adoption; history file written at once so the discarded text stays recoverable |
| Keep my edits | S3 | user, naming the revision the dialog showed; main, immediate | S2 on R' with `kept`: base := seen. Ignored if `seen` has moved since the dialog was drawn (the dialog redraws) | base and `kept` written into the journal header |
| drop from memory | S1, or S2 with journal acknowledged; not shown, no op in flight | byte budget, main | S0 or S5 | history file (if adopted, section 3) |
| rename, delete through the app | any | user; an operation like the others (one at a time per file); delete of a file with unsaved text asks first | the record, its journal (rewritten under the new path) and its history file move together, or go together | the store change and the journal change, in one worker task |
| background (`onStop`) | all | lifecycle | flush everything, then sync journals and history files; `onStop` waits for this, bounded at a few hundred ms, because the process may be killed afterwards without notice | everything typed |
| close project | all | user/lifecycle | flush with sync; pending results are dropped on arrival | as background |

Three properties to keep in the head:

- **Text and base move together, on main, in one method.** A read that is not adopted changes nothing.
- **The journal follows the record.** It is created by the first flush after an edit and deleted only on main's command, after the record became clean: by an adopted save of exactly its text, an adopted reload, or undo. No transition deletes it first and reads afterwards. The worker never decides anything about a journal.
- **`seen` is only ever an observation.** It becomes `base` in exactly two ways: the text is adopted with it (reload, clean refresh, save), or the user says "keep my edits over this revision".
- **One meaning of "the file holds this text".** A file holds a text when opening the file gives exactly that text, character for character. Every place that says saved uses this: a save, a check that compares unsaved text with storage, and the comparison of a journal with storage when a project opens or is refreshed, for files in memory and not. It is not "writing the text gives these bytes": writing is many to one (a surrogate without its pair is written as `?`). So unsaved text is found saved exactly when a fresh open would show it, whatever the line ends or byte order mark of the file. And a text no file can hold (half of a surrogate pair, a NUL, a byte order mark as first character) is not saved at all: Save fails for that file with the line and position of the character, nothing is written, and the journal keeps the text exactly. Nothing is ever reported as saved while the stored text differs.
- **Every read of storage is an observation, and conflict is not stored.** What a check, a reload or a refused save found, and the revision it read, are kept even when the text read is not taken (the user typed meanwhile, the dialog's revision is stale). A file is in conflict exactly while it has unsaved text and what was last seen is not its base; so the conflict and its revision follow every later observation, for files in memory and for journaled ones alike, and a refresh checks all of them. Unsaved text that storage is seen to hold exactly is saved at that revision, by the same adoption as a save of that text.

## 2. The journal, compared

### 2.1 What Neovim does

- Swap file: "The swap file is updated after typing 200 characters or when you have not typed anything for four seconds. ... After writing to the swap file Vim syncs the file to disk." **[doc]** `runtime/doc/recover.txt`, defaults `'updatecount'` 200 and `'updatetime'` 4000 **[doc]** `options.txt`. Note the sync: Neovim's `'fsync'` is on by default and covers the swap file **[doc]**; so "no forced sync per flush" is our choice, not Neovim's.
- The swap file is not a change log and not a copy of the file. It is a block file of text pages. "A data block that was filled when starting to edit a file and was not changed since then, can have a negative block number. This means that it has not yet been assigned a place in the file. When recovering, the lines in this data block can be read from the original file." **[doc]** `src/nvim/memline.c` header comment; recovery reads those lines from the original by line number and inserts `???LINES MISSING` when it cannot (`memline.c` near line 1055). It compares the original's modification time with the one stored in the swap header and warns "E308: Warning: Original file may have been changed" (near line 950). So recovery after an outside change is best effort, by line number, with a warning. We should do better than that, not copy it.
- Undo file: written "when writing a buffer to a file", restored on read, off by default **[doc]** `'undofile'`. "Vim will detect if an undo file is no longer synchronized with the file it was written for (with a hash of the file contents) and ignore it when the file was changed after the undo file was written" **[doc]** `undo.txt`; the hash is SHA-256 over the buffer text (`undo.c`, `u_compute_hash`). The whole undo tree is rewritten on every write **[memory]**.
- Reload: `'undoreload'` (default 10000 lines) saves the whole buffer as one undo step when a file is reloaded after an outside change **[doc]**. That is what `OpenProject.takeStored` does today **[code]** and what this design keeps.

### 2.2 Assumptions for the numbers

Two hours of editing a day; 20,000 characters changed a day (fast for a phone) **[judgement]**; a flush about every 10 s of activity, 720 a day; 20 saves a day with manual save, up to 300 with save-on-run; the app goes to the background 30 times a day with something unsaved. Auto-save and save-on-run are both on by default **[code]** `Preferences`, so for most users unsaved text is short-lived and the journal is mostly a net for the seconds between saves; the manual-save user is the one who depends on it for hours. "Logical" bytes are what the app writes. Physical flash writes are larger for small appends, because the file system rewrites the last 4 KB block and its metadata at each writeback: I assume about 12 KB per writeback interval with activity **[memory, judgement]**, at most 1,440 intervals in two hours, about 17 MB a day at worst, independent of design.

**Flash wear, stated honestly:** a 128 GB phone flash endures on the order of 100 TB or more of writes **[memory]**. The worst row below (whole 2 MB text at every pause) is about 0.5 TB a year, well under 1% of that per year. For shader-sized files every design is far below any wear concern. Wear does not decide this. What decides it: work per flush proportional to what was typed and not to the file (worker time, battery, the queue the Save button waits behind), the number of syncs (a sync forces a file-system commit and a flash cache flush, typically tens of ms **[memory]**), and whether recovery depends on the stored file.

### 2.3 The three designs

**(a) Whole-text snapshot per flush, as today, with pacing.** Today: one file per draft, whole text encoded twice, written to a temporary file, `fsync`, rename, on every `persist()` **[code]** `DraftJournal.put/replace`; nothing calls `persist()` on a timer (finding 21). "With pacing" adds the 2 s/5 s rule. It cannot safely drop the sync: a new file renamed over the old draft without a sync can leave neither after a power loss on some file systems **[memory]**.

**(b) Append-only change log against a base named by content hash.** The journal holds only records `(start, removed length, inserted text)`; recovery reads the stored file, verifies its hash and replays. A full-text draft is written as the fallback when an outside change is observed or the app goes to the background.

**(c) Recommended: self-contained journal = snapshot + change records.** One file per unsaved path:

```
header   fixed size: magic, version, base revision or ABSENT, kept flag, checksum
path
snapshot text as UTF-8 with length and CRC-32, taken at the first flush after the file became modified
record*  length, start, removed length, inserted UTF-8, CRC-32      (appended, no sync)
```

- First flush after a file becomes modified writes header + snapshot (temporary file, sync, rename: one sync). Later flushes append the changes since the last acknowledged revision.
- When the records exceed the snapshot's size, but not before 16 KB and at the latest at 128 KB, the next flush writes a fresh snapshot instead (compaction). This bounds replay at about 12,000 records.
- Base and `kept` sit in the fixed header and are overwritten in place (one block) when a save succeeds while the user typed on, and on Keep my edits. A revision is always `sha256:` plus 64 hex digits **[code]** `StoredFiles.revision`, so the field has a fixed width. Startup reads the headers, and for each the text once, to compare it with what storage holds.
- Deleted once the record is clean: after an adopted save of exactly its text, an adopted Reload, or undo back to the saved text. A journal file exists ⇔ the file has unsaved text. If the process dies between a successful save and the deletion, the next open finds storage equal to the journal's text and deletes it then, so there is no false conflict.
- It is (b) with the fallback always inside, so there is one mode instead of two, at the price of one text-sized write per save cycle.

| | (a) snapshots, paced | (b) log on hash base + fallback | (c) snapshot + log |
| --- | --- | --- | --- |
| Logical bytes/day, 20 KB file | 720 × 20 KB = 14 MB | 0.2 MB log + 30 × 20 KB fallback = 0.8 MB | 0.2 MB log + 20 × 20 KB snapshots (+ compaction) ≈ 0.8 MB |
| Logical bytes/day, 2 MB file | 720 × 2 MB = 1.4 GB (or pace by size and lose more on a kill) | 0.2 MB + 30 × 2 MB = 60 MB | 0.2 MB + 20 × 2 MB = 40 MB; with save-on-run 300 × 2 MB = 600 MB, the same again as the saves themselves write |
| Syncs/day | 720, one per snapshot | 30 fallback + 30 background | 20 snapshots + 30 background |
| Work per flush | encode whole text (2 MB: tens of ms on a phone **[judgement]**) | bytes typed | bytes typed |
| App killed (process only) | loses ≤ 5 s | loses ≤ 5 s | loses ≤ 5 s |
| Power lost | loses ≤ 5 s (every snapshot is synced) | loses ≤ 5 s + what the system had not written back yet (≈ 5 to 30 s **[memory]**); a torn last record is cut off by its checksum | as (b) |
| File changed elsewhere while dead | text recovered, conflict shown | recovered only from the last fallback; edits since then are lost if the base no longer matches | text recovered, conflict shown |
| Storage unavailable at restart | recovered, editable | **not recoverable until storage returns**, unless a fallback exists; violates NAVIGATION 6.7 | recovered, editable |
| Recovery time | read + decode text | read store + hash + replay; unbounded without compaction (which needs a snapshot, i.e. becomes (c)) | read text + at most 128 KB of records, replay at most about 12,000 records; measured in section 2.4 |
| Memory | today every draft's `Text` stays in `DraftFiles` and all are built at open (finding 20); paced: none extra | the unflushed changes (bytes) | the unflushed changes (bytes) |
| Dropping a modified document | possible (snapshot is complete) | possible only if base present | possible |
| Undo | independent | independent | independent |
| Corruption | atomic rename: old or new **[code]** | torn tail cut; **unreadable base = total loss of unsaved text** | torn tail cut; snapshot protected by checksum and atomic rename; base not needed |
| Two files | two files, independent | same | same |
| Lone document | recovered even if the grant is gone | lost if the grant is gone | recovered even if the grant is gone |
| Code | least: keep `DraftJournal`, add owner/pacing/acknowledgement (needed in every design) | record codec, replay, torn tail, **two modes and the rule that switches them** | record codec, replay, torn tail, compaction: about 250 lines more than (a) **[judgement]** |
| Risk | lowest | highest: the fallback rule is exactly the kind of clock-dependent state the review found broken | moderate: one append path, one rewrite path; any doubt degrades to a rewrite |

### 2.4 Measured

**[probe]** A throwaway program with this checkout's `Text` class, a varint record `(start, removed length, inserted)` with a CRC-32 each, 20,000 random single-character changes (95% typed, 5% deleted, a caret jump every 40), on the desktop JVM of this Mac, single runs. A phone is slower by a factor I did not measure; EDITOR.md's phone numbers suggest 3 to 10 times **[judgement]**.

| | 20 KB, 572 lines | 2 MB, 57,145 lines | 16 MB, 457,145 lines |
| --- | --- | --- | --- |
| Log of 20,000 changes | 207 KB (10.4 bytes a change) | 219 KB (10.9) | 236 KB (11.8) |
| Encode all 20,000 records | 9 ms | 12 ms | 5 ms |
| Encode a snapshot (what (a) does per flush, twice today) | 0.3 ms | 19 ms | 104 ms |
| Recover: decode snapshot | 0.1 ms | 11 ms | 38 ms |
| Recover: replay 20,000 records, checksums verified | 66 ms | 111 ms | 60 ms |
| SHA-256 of the text | under 1 ms | about 20 ms | 150 ms |

Replaying yielded the same text as editing in memory in all three. Replay costs 3 to 5 µs a record whatever the file size, so the 128 KB bound (about 12,000 records) is about 40 to 70 ms here and well under a second on a phone, once, on the worker, when that file is opened. The same probe measured 2.2 bytes of heap per character for a 2 MB `Text` on the JVM, which agrees with EDITOR.md 4.1 ("about 2 bytes per character including the tree").

Probe source: `/Users/anton/dev/ShaderEditor/.foreman/proposals/file-lifecycle-probes/JournalProbe.java` (it does not write files and does not measure sync or flash behaviour).

**What a refresh costs, and where.** For every file with unsaved text a refresh reads the stored file, decodes it as opening does and compares it with the unsaved text; for a file that is not in memory it also reads the journal. A reviewer measured, on a desktop JVM with real folders: one shown modified 16 MiB document about 140 to 180 ms, twenty unsaved 1 MiB files not in memory about 260 to 300 ms; no phone number. All of it, the read, the decoding, the journal replay and the comparison, runs on the worker. Main captures the document's immutable text when it asks (constant time) and takes the answer (a few field assignments; a replacement of the text only when a file without unsaved text changed).

### 2.5 Recommendation and its honest margin

**(c).** It writes in proportion to typing, syncs about 50 times a day instead of 720, never depends on the stored file, and has one mode. (b) is rejected: it saves one text-sized write per save cycle and pays for it with a second mode and with losing text in the two scenarios the owner and NAVIGATION 6.7 care about (changed while dead, storage unavailable).

The honest margin: for files up to about 100 KB, **(a) with pacing is within a small factor of (c) in physical bytes** and needs the least code; what it keeps is a sync at every flush, 720 a day against 50. If the owner prefers the least code now, (a) paced is a defensible first step, and (c) can replace it later behind the same record interface, because nothing but the worker's journal class knows the format and there is no user data in it yet. I recommend going to (c) directly because the format is cheapest to choose now, the 2 MB case in (a) is either wasteful or loses more on a kill, and (c) is what lets modified documents leave memory cheaply (section 4).

How the journal stays correct without owning truth: the worker executes these commands from main and decides nothing about the text itself. Each carries the path and the document generation.

| Command | Does | Refused when |
| --- | --- | --- |
| keep(from, to, changes, text, base, kept) | appends the changes when the journal is at `from` and its records need no rewrite; otherwise writes the whole text as a new journal file and renames it into place. One task of the worker either way | — |
| header(base, kept) | overwrites the header in place: after a save that the user typed through, and on Keep my edits | there is no journal of this generation |
| retire | deletes the journal | — |
| dismiss | deletes a journal that cannot be read, for the user who gave it up | the journal can be read: a snapshot written since took its place |

Every command is acknowledged or fails with a reason. Any confusion degrades to a full rewrite, never to a wrong append. The queue is first in, first out, so a command cannot overtake an earlier one; the revisions are a cheap second guard, not what correctness rests on.

**Who may change a journal file.** Only the journal object of the project that is open on the session, checked on the worker when the command runs. A project takes the folder when it opens and gives it up as the last thing it asks of the worker when it closes; a project opened on the same session later takes it from one that never closed. The folder is owned as the folder it is, by its canonical path, not by the spelling of the path a journal was made with. Every command of a journal that does not own the folder is refused, so nothing a closed or replaced project still asks for can write or delete. A closed project also stops following its documents and asks the worker for nothing more. The same ownership decides whether a session may be forgotten: removing a recent deletes the session's folder only when no project owns its journal and the journal folder is empty, checked together.

Failure handling: the record's `journal` advances only on the worker's acknowledgement. A failed write sets FAILED(reason), which the snapshot type exposes ("unsaved changes are not protected"), keeps the document pinned in memory, and is retried with a snapshot on the next flush. A journal whose snapshot fails its checksum is kept on disk untouched, reported once, and the file opens from storage.

**Damage is never answered with older text in silence.** A journal that cannot be read stays on disk until the user dismisses it, and everything that would use its text says so instead of taking the stored file in its place: opening the file shows the stored text with the notice, also when the stored file cannot be opened either; saving answers "unreadable" for that path and writes nothing; a copy or a build that needs the file fails for that path with the reason. Only the opened editor document falls back to the stored text, because there the notice is on screen. A journal whose header is damaged cannot name its file: the project counts such journals, the recent entry still shows unsaved text, and since a journal's file name is derived from its path, opening the file it belongs to finds it and offers to dismiss it.

## 3. Undo

**In memory: no change to the design.** `Document` keeps steps of `Change(start, removed, inserted)`, typing grouped, bounded at 1,000 steps and 4,000,000 characters **[code]**. Texts in steps share structure with nothing (they are the removed and inserted pieces), so the cost is the edited characters plus a small per-step overhead. The editor-core review found it sound; I found no reason to touch it. Two small additions only:

1. `Document` exposes its history size (`historyChars` exists privately **[code]**) so the byte budget can count it.
2. `Document` can export and restore its history as an immutable value (steps, redo steps, state numbers, saved state). About 60 lines, package-private to `editor`.

**What history survives today and in this design:**

| Event | Today | Proposed |
| --- | --- | --- |
| Save | survives (`markSaved` keeps the steps) **[code]** | same |
| Outside change adopted (clean refresh, Reload) | survives, the replacement is one undoable step **[code]** | same |
| File dropped from memory | lost | restored from the history file; unsaved text that is opened again stays marked unsaved when undo reaches the stored text, until the next check of storage |
| App killed in the background | lost | restored (written at `onStop`) |
| Crash or power loss in the foreground | lost | lost back to the last background or drop; the text itself is recovered from the journal |
| Outside change while dead, or journal replayed to a different text | — | history ignored (hash mismatch), as Neovim does |

**The history file.** One per path, in the session's private folder: SHA-256 of the text it applies to, the steps and the selection. Written on the worker when a document is dropped from memory, at `onStop` for documents whose history or selection changed since the last write, and at once after a Reload, before the journal of the replaced text is removed (so the discarded text stays recoverable). Read at open; adopted only if the hash equals the hash of the text just opened; deleted otherwise.

**What the history may decide: undo and the selection, nothing else.** The hash is taken over the text's characters, each encoded by itself as the journal encodes them, not over the bytes a file gets: those turn every lone surrogate into the same `?`, so two different texts would share a name. The history does not carry which state was saved, and the store revision is not used as its name. Whether a text is saved is decided only by where it came from: a document opened from storage is saved at the state it opens in, a document opened from a journal is unsaved whatever undo leads to, until a save or a check that finds storage holding exactly its text. So losing or misreading a history can lose undo and never text or the saved state. All histories of a session together are bounded (twice the size limit of a document); the oldest are deleted when a project opens, which is what removes the histories of files deleted or renamed elsewhere.

Cost: the file is rewritten whole each time, as Neovim's is. A day's typing is under 100 KB of history; the worst case is the document cap, about 4 MB, for instance after reloading a 2 MB file. Written only when the history changed since the last write.

Why not on every save, as Neovim does: with save-on-run that rewrites the history hundreds of times a day for nothing; the cases where the history is needed again are leaving memory and leaving the process.

Why on Android at all, when desktop editors mostly do not: on a phone the process dies in the background without the user doing anything. Without this, undo silently empties after a few minutes in another app. **[judgement]**

**Global budget instead of per-document caps.** The per-document cap stays as the bound for one document. The total is bounded by the memory budget of section 4, which counts history bytes; there is no second mechanism.

**Alternative considered: one log that is both journal and undo history.** Journal records would carry the removed text and step boundaries, and a clean file would keep its log. It makes undo survive a crash too. I do not recommend it: it couples the part that must never be wrong (recovering text) to undo's grouping rules, it breaks "a journal exists ⇔ unsaved text", and compaction would have to preserve history. The split keeps the journal minimal and the history disposable.

**First version:** history file as above. **Deliberately later:** undo that survives a foreground crash; undo across an outside change; a time-ordered local history (as JetBrains has); trimming old steps of background documents before dropping whole documents.

## 4. Memory

**What a loaded document costs.**

| Part | Size | Source |
| --- | --- | --- |
| Text | about 2 bytes per character, tree included (ASCII, Android 8 and later; about twice that on Android 6 and 7, where strings are two bytes a character **[memory]**) | **[doc]** EDITOR.md 4.1, measured there; **[probe]** 2.2 on the JVM |
| Line states | 1 byte per line | **[doc]** EDITOR.md 13 |
| Undo history | the removed and inserted texts, about 2 bytes per character, plus a few hundred bytes per step **[judgement]**; at most 1,000 steps and 4,000,000 characters per document, so at most about 8 MB | **[code]** `Document` |
| Whole-file analysis (outline, names) | at most about 1 MB per document | **[doc]** EDITOR.md 13 |
| Error marks | the diagnostics of one build | small |

A 20 KB shader is about 40 KB of text, and with a day's history under 200 KB. A 2 MB file is about 4.5 MB of text and line states.

**The rule.**

1. The shown document always stays, whatever its size (bounded by the 16 MiB open limit). It is the only one that is exempt: a document that was opened and not shown counts like any other.
2. A document is pinned while it has an operation in flight or changes the journal has not acknowledged (seconds), and from the moment it is opened until every caller that asked for it was told and its callback returned, whatever a callback calls meanwhile, so each of them can show it.
3. All other loaded documents, clean or modified, share a budget of `memoryClass / 8`, at least 8 MB and at most 32 MB, counted as `2 × characters + lines + 2 × history characters` (the table above; three integers the document already has). When over, the least recently used is dropped first, in one order for documents with and without unsaved text. Checked at the end of every transition (so finding 36 cannot recur).
4. Whole-file analysis (up to 1 MB each, and recomputable) is kept for the shown document only and dropped when another file is shown.
5. A file with unsaved text that is not loaded costs nothing: today every draft's whole `Text` is built at open and kept **[code]** `EditingSession.restore`.

**Why these numbers.** A typical project (ten files of 20 KB) costs about 2 MB with a day's history, so in ordinary use nothing is ever dropped and the rule is invisible. 8 MB is one eighth of the smallest heap the app meets (64 MB class on API 23 **[memory]**) and still holds about a hundred ordinary shaders. 32 MB caps it on large-heap phones, so the editor stays within a small multiple of what a plain native editor would use rather than growing with the device. "Why five?" has no good answer; a count ignores that one file can be 400 times another.

**What the user loses when a document is dropped.** With the history file: a reopen (read and decode: under 1 ms for a shader and about 30 ms for 2 MB on the desktop **[probe]**, a frame or a few on a phone) and nothing else; text, unsaved edits, undo, selection and scroll come back. Without the history file (if decision D1 goes the other way): undo and scroll position of that file.

**One more limit worth stating.** The review's probe needed more than 64 MiB of heap to lex a 4.5 MB file **[review, finding 19]**. The 16 MiB open limit is therefore not safe on small heaps regardless of this design. Proposal: files above 1 MB open as plain text without whole-file analysis; that belongs to the editor, not to this lifecycle, and is listed as an open point.

## 5. Concurrency

### 5.1 The rules

- **R1. Main decides, worker does.** All records, documents and the project's selection state belong to the main thread. The worker executes store and journal calls and returns immutable outcomes. No object is shared under a lock; main never waits for the worker (finding 18). That includes opening and closing a project: the registry of live projects belongs to main, a project is opened by a task of the queue, which puts it after the writes pending there, and is handed to the caller on main by callback. `Projects` has no method that returns a project. Nobody is handed a closed project as the one they opened: when one caller closes it in its callback, the callers told after it get a project opened anew. The document of an opened file is built on the worker too, with its history, and handed to main whole, so a device that has no memory for a file within the limit answers "too large for this device" from the one place that answers it for the read.
- **R2. One serial queue per process**, created by `Projects`, not injected as an arbitrary `Executor`. Tests pass a queue they step by hand through a package-private constructor (finding 22).
- **R3. One operation per file at a time.** A record has at most one of LOAD, SAVE, CHECK (and rename, delete) in flight, with a ticket. A second request of the same kind joins it (two opens: one read, two callbacks). A different one waits its turn in the record and captures text, state and base only when it starts, so a save that waited writes the newest text. Journal flushes are not operations in this sense; they are ordered by the queue and validated by revision (2.5).
- **R4. Adoption.** An outcome is handed to `record.adopt(ticket, outcome)`. If the ticket is not the record's current one (the record was closed, the file dropped or renamed), the outcome is discarded whole, including the revision it saw. Nothing else reads worker results.
- **R5. Text is replaced only if untouched.** An outcome that would replace document text (clean refresh, Reload) is applied only if `document.revision()` equals the revision the request captured. Otherwise its revision becomes `seen`, and the file is in conflict (S3) with the user's later typing intact.
- **R6. Base moves only with the text or by consent.** `base` is assigned in `adopt` in the same call that gives the document the text, or marks it saved, or by Keep my edits naming the revision it accepts. The expected revision of a write is passed by value from the record; the worker keeps no bases.
- **R7. The record is consistent before anyone hears.** `adopt` updates the record's fields inside the document batch, before `endBatch` notifies listeners (finding 17).
- **R8. Durable means acknowledged.** `journal` advances on the worker's acknowledgement only; failure is state, shown and retried (finding 5). A write is one task of the worker with a final answer, never a question back to main, because main may be gone (close) or the fence may already have passed (`flush(done)`). Text counts as protected only when the acknowledged write is the last one sent and names the revision the document has now. `flush(done)` and `close` are that write for every document, then one sync of the journals behind them; `flush(done)` answers after both. A write that failed counts as a failure until the last write sent is answered, also when a later one is already on its way: nobody is told meanwhile that the text is held. **The fence does not depend on any answer still on its way.** A write that was sent and not acknowledged may have failed, and after `close` its failure is never heard; so at the fence every file whose last write is unacknowledged sends what its journal is to be again, the text whole or the removal, behind the one on its way. The same holds for the session's small facts (file shown, file chosen to run, project open last): the fence sends them as they are now even while an earlier write of them is unanswered, and failures of any of them are told by one value and sent again by the next flush.
- **R9. Consent names a revision.** `resolve(path, choice, seen)` carries the revision the dialog displayed; it does nothing if the record's `seen` differs. Keep my edits sets `base := seen`, so the next explicit save writes with `expected = seen` and a later outside change is a new conflict; no earlier state of the document counts as saved after it, because none of them is what storage holds at that revision. Consent to lose text (Reload) is taken when it is given: it names the document and its revision at the call, and a read that waits behind a save or a check does not renew it (R3's capture at start is for writing the newest text, not for discarding it). Unsaved text that is not in memory is loaded first, so the reload is the same undoable replacement; the consent then names that text as the journal had it, the revision the document is opened at, so what any caller types once the document is in memory stays, also a caller that asked for the file before the Reload. A reload that cannot read the file changes no text and keeps what it found in the file's place (gone, unavailable, too large, not text) as the observation, like any other read.
- **R10. One live owner per session.** `Projects.open(id, done)` answers with the live instance if there is one, and calls made while it is being opened get the same one; `OpenProject` has no public constructor. With R2, two owners of one journal cannot exist in a process. The app has a single process **[code]** (no `android:process` in the manifest).
- **R11. Project-level results carry a generation.** The running selection and build results are tagged with a counter that every explicit choice and every refresh increments, when it starts and when it has taken what it found, whatever that was; a result with an old tag is dropped.
- **R12a. Text given up by Reload leaves the disk only into its history, and that is the journal's obligation, not anyone's memory.** After a Reload the journal holds the replaced text, and undo is the only way back to it. The worker marks the journal's header as "replaced by a Reload" in the task that reads the stored text, before main is told and so before main takes that text. From then on the journal is removed in one place, the worker's removal, and only (1) by the task that first wrote a history whose undo leads back to the replaced text, or (2) when storage is found to hold exactly the journal's text. Nothing main does afterwards lifts this: reloading again while the document is already saved, a refresh that takes another stored text, a save, a fence. The mark ends otherwise only by a new snapshot, which happens when the user types (the journal then holds the typed text) or says to keep the text. While no history can hold the text (the write fails, the history is larger than a kept history may be, or its steps no longer lead back that far), the journal stays and the document, which is saved, stays in memory. A kept history is a bounded cache afterwards: it goes when it is among the oldest beyond the session's bound.
- **R12b. Text that comes back says where it came from.** When the app ends while such a journal exists, the text opens again as unsaved text, and `replaced(path)` tells that it is text a Reload replaced, for the file in memory or not, so a screen can say so and offer to reload again. It stops telling so when the user edits the text, saves it, reloads it again, or keeps it (`keepReplaced`), which rewrites the header.
- **R12. A journal is deleted only when its text is safe elsewhere or the user gave it up**: after an adopted save of exactly that text, when a check or an open finds storage equal to it, after an adopted Reload, after undo back to the saved text, or by an explicit, confirmed discard. Only main commands it, and only the main of the project that owns the session's journal (2.5). Removing a recent hides the shortcut and deletes nothing that holds or may still receive unsaved text.

Late results, summarised: they can only be late relative to a closed or dropped record (R3 leaves no other way) and are discarded (R4). What a completed save did to the store stands. Its journal stays, because nobody adopted the save; the next open finds storage equal to the journal's text and deletes it, or, if the user had typed on, shows the newer text as unsaved against a file that "changed": a question too many, never a loss.

### 5.1a Renaming and deleting

`rename(from, to)` is an operation of the project, for a file or a folder, whether the project holds files there or not. A file the project holds cannot be renamed or deleted by changing only the stored file: the project's copy would stay behind, and a later save would write the old path again from memory. The journals, the undo histories and what the session shows and runs have to follow storage, also when the process dies in between.

- **M1. One operation for all of it.** The operation takes the one operation slot of every file the project holds at or under the path (in memory, with unsaved text, or with a journal that cannot be read), each as soon as a save, reload or check of it that is under way has finished, and also of files it comes to hold while it waits. Then storage is changed by one task of the worker. So it is ordered like any other operation on each of those files.

- **M2. A rename or deletion is noted before storage is touched, and the journals follow storage.** The worker first writes the change into the journal's folder, then asks storage. Storage is the commit point: when it refuses, the note is removed and nothing else happened. A deletion that storage made takes the journals and histories at and under the path with it in the same task. After a rename each journal is written whole under the new path, with its header naming it, and then the old one is removed; the histories are kept for the new paths. A journal found under the new path is the newer one and is never written over by one left behind.
- **M2a. The document stays, and main's writes land on the right side of the move.** When storage made a rename, main gives the held files their new paths: the same record and the same `Document` under a new key, so unsaved text, undo and selection stay. Only then does it have the worker move the journals and histories. What main writes to a journal before it knows of the rename lands under the old path, in front of the move in the queue; what it writes afterwards lands under the new one, behind it. That holds for a close at that moment too: its last write lands under the old path, and the next opening finishes the move.
- **M3. Wherever it stops, the unsaved text is under exactly one path.** A project that opens while such a change is noted looks at storage before it looks at any journal: the target exists and the source does not, so the journals, the histories and the session's shown and running file follow now; the source still exists, so the note is dropped and nothing moved; storage cannot tell (unavailable), so everything stays as it is with the note, for the next time. No journal is read as two files, and none is read as a file that was "deleted elsewhere" because of a change the app itself made.

- **M4. Rename does not look at content.** A file that changed elsewhere is renamed as it is; afterwards each held file is checked as a refresh checks it, so the usual rules apply under the new path (a file without unsaved text takes the stored text, unsaved text is in conflict). A source that vanished elsewhere is refused as absent, and that is what is seen of every held file there (unsaved text stays, in conflict "the file was deleted"). A target that exists in storage, or at or under which the project holds a file, is refused. A project of one lone document refuses: its session and its recent entry name that document.
- What follows a rename is not rebuilt: the diagnostics of the last build keep the old paths until the next build, which the rename makes stale. A load of the old path that was waiting is answered once the file is in memory, under its new path.
- Everyone but the caller learns of it from the listener: `events(after)` gives the renames in order.

### 5.2 Checked with a model

**[probe]** `file-lifecycle-probes/LifecycleProbe.java` beside this document is a 300-line model of the record with these rules: the real `Document` and `Text`, storage and journal as maps, worker and main as queues stepped by hand. It is not the implementation and its journal always sends whole texts. It ran these sequences, all with the expected result:

- finding 1: open, outside write, open again before delivery: one read, the later save is refused, the outside text is intact;
- finding 2: Reload with the file gone: journal and text kept;
- finding 3: Reload, then typing before delivery: typing kept, still a conflict, journaled; and the undisturbed Reload: adopted, journal gone, undo brings the user's text back as unsaved on the new base;
- finding 5: journal unwritable, then repaired: the failure is visible and the next flush writes without another edit;
- finding 7: Keep over one revision: auto-save skips the file, a second outside change is a new conflict, a stale dialog's Keep does nothing, Keep again and an explicit save write;
- finding 17: a listener that edits and flushes during adoption sees the new base and its save is not a false conflict;
- save while typing: the file holds the saved text, the document is unsaved on the new base, the journal's header follows;
- process death between the store write and the journal's deletion: reopens clean, no false conflict;
- restart with the file changed elsewhere: conflict with my text; with storage unavailable: my text opens and is editable.

Six mutations of the rules were run against it. Five made a sequence fail (text replaced although touched; opens not joined; a stale dialog accepted; journal acknowledged before written; no header update after a save typed through). The sixth, a retire that ignores its revision, failed nothing, which is why 2.5 calls that revision a second guard only.

### 5.3 The review's findings through the model

| # | Finding | Verdict |
| --- | --- | --- |
| 1 | duplicate pending opens advance the base | impossible by construction: R3 joins the opens, R6 leaves no base outside the record |
| 2 | failed Reload deleted the draft first | impossible: R12, the journal goes only after a successful adoption |
| 3 | Reload overwrote typing after the request | impossible: R5 |
| 4 | Save a copy discarded later edits | impossible: copying is not a lifecycle transition; it reads snapshots and never reloads or clears the source (section 6) |
| 5 | failed draft write treated as done | impossible: R8 |
| 6 | removing a recent destroyed unsaved work | handled by R12; needs the per-case change in `Projects.remove` / `ProjectSessions` (hide, keep the session while it has journals) |
| 7 | Keep my edits covered later outside edits | impossible: R9 |
| 10 | drafts unreachable when the folder is unavailable | handled by the model: journals are self-contained and `seen` = UNAVAILABLE is an ordinary state; needs the per-case change that `Projects.open` returns a session for an unavailable store |
| 11 | lone document does not compose | still needs a per-case fix: the session carries "entry is this file" and `Projects` accepts document locators. The file's lifecycle is the same single record |
| 12 | refreshed manifest, entry chosen from old text | handled by R11 plus ordering: the selection is computed after all checks of the refresh were adopted |
| 13 | refresh overrode a newer Run this file | impossible: R11 |
| 14 | build results without identity | handled by R11: a build request captures entry, generation and document revisions; one `adoptBuild` checks them. `close()` bumps the generation |
| 16 | ghosts after outside deletion | handled by the model: check outcomes are distinct (same, changed, missing, unavailable, too large, not text) and S4 exists; a build reads storage truth for clean files |
| 17 | re-entrant listener saw the old base | impossible: R7 |
| 18 | main blocked on the session monitor | impossible: R1, there is no shared monitor |
| 19 | `canRun` lexes on main | still needs a per-case fix that fits the model: "runnable" is a fact computed on the worker per (path, revision) and cached in the record; the snapshot exposes yes / no / unknown |
| 20 | 16 MiB limit not a bound, bypassed by drafts | still needs a per-case fix in the stores (bounded read at the stream). Journals are read by streaming and never built eagerly; a text may not grow past the limit by editing (open point: where the editor refuses) |

**Every read from private storage is bounded before it allocates.** A journal header is read up to 256 KiB, a snapshot up to twice the size limit of a document (each character is encoded by itself, which takes at most twice its bytes in a file), a record and all records together up to the 128 KB the compaction rule allows; a snapshot whose text would exceed the limit of a document is reported as unreadable without being built. Session metadata files are read up to 256 KiB, a history file up to the limit of a document. A device that runs out of memory on a file within the limits answers "too large for this device" for that file, on the worker, and carries on.
| 21 | drafting has no owner | impossible: the record's flush timer is the owner; `flush(done)` is the lifecycle fence |
| 22 | serialization was a caller promise | impossible: R2 |
| 34 | content check obsolete during a long save | per-case fix plus accepted limitation: `DirectoryProjectFiles` re-checks the revision immediately before the rename (window shrinks from encode time to microseconds); a provider cannot compare-and-swap, so SAF keeps a window. To be stated in SPEC 10 |
| 35 | no unique live owner | impossible within the process: R10 with R2 |
| 36 | clean cap not enforced on becoming clean | impossible: the budget is checked at the end of every transition (section 4, rule 3) |

Not in the list asked for, but touched: finding 8 (atomic replace detaches a writer that came through our provider) is outside this model and needs its own decision; finding 33 (crash leftovers counted as unsaved) disappears with header-validated journals.

## 6. The interface

All on the main thread. Callbacks arrive on main. The object is given three seams at construction, by `Projects` only: the serial queue, "run this on main", and "run this on main in n ms" (the flush timer). Tests supply all three and step them by hand.

```java
public final class OpenProject {          // obtained only from Projects.open(id); one live instance per id
    Snapshot snapshot();                  // immutable; a new instance whenever anything in it changed
    void setListener(Runnable changed);   // coalesced; called after a transition is complete

    void open(ProjectPath path, Callback<Opened> done);   // Opened: the Document, or a Problem
    Document document(ProjectPath path);                  // null when not loaded
    void show(ProjectPath path);

    void save(Collection<ProjectPath> paths, boolean explicit, Callback<Map<ProjectPath, SaveResult>> done);
    void refresh(Runnable done);                          // check storage for loaded and unsaved files, then what runs
    void resolve(ProjectPath path, Choice choice, Seen seen);   // RELOAD or KEEP, for the revision the dialog showed
    void copyTo(ProjectFiles destination, Collection<ProjectPath> paths, Callback<CopyResult> done);

    void run(ProjectPath path);
    void build(Map<String, String> reserved, Callback<Build> done);   // Build is tagged; stale ones are not delivered
    void setDiagnostics(Build build, List<ProjectDiagnostic> diagnostics);

    void list(ProjectPath folder, Callback<Folder> done); // listing and offers move onto the queue too
    void flush(Runnable done);                            // everything typed is in the journal and synced; call from onStop
    void close();
}

public record Snapshot(
        Storage storage,                  // AVAILABLE, READ_ONLY, UNAVAILABLE(reason)
        ProjectPath shown,
        ProjectPath running,
        Map<ProjectPath, FileState> files,// only files that are loaded, unsaved or marked; all others are plain
        List<Used> used,
        List<ProjectDiagnostic> diagnostics,
        String unprotected) {}            // why unsaved text is not in the journal, or null

public record FileState(
        boolean loaded,
        boolean unsaved,
        Outside outside,                  // SAME, CHANGED, MISSING, UNAVAILABLE
        Seen seen,                        // what to hand back to resolve()
        boolean kept,
        Runs runs,                        // YES, NO, UNKNOWN: whether its own text defines main
        Busy busy,                        // NONE, OPENING, SAVING, CHECKING
        String problem) {}
```

Folder marks (unsaved, conflict, error counts under a path) are a pure function of the `Snapshot`, computed once per snapshot into a per-folder index, not per visible row (the review's cost note).

`copyTo` is "Save a copy" in the core: it writes the current text of the given paths (document, else journal, else stored bytes) into another store, expecting each destination to be absent. It never changes the source's records. Choosing the paths (the running shader's closure) and the destination, and switching to the copy afterwards, belong to the caller.

**What the existing classes become**

| Class | Becomes |
| --- | --- |
| `OpenProject` | kept as the one public face; loses file state to `FileRecord`, loses `persist()` (internal timer), `keepEdits`, `reload`, `saveCopy`, `canRun` |
| `FileRecord` | new, package-private: the state machine of sections 1 and 5. No I/O, no threads: requests go out as values, outcomes come in through `adopt`. Testable without any queue |
| `FileTexts` | private worker helper (bounded read, decode, encode, write with expected revision); its `bases` map is deleted |
| `EditingSession` | deleted. Conflicts are `record.seen`; save is one worker call; restore is the journal's header scan |
| `DraftFiles` | deleted. The "project as edited" view for a build is made from the snapshot's document texts plus journals of unloaded unsaved files, read on the worker like any other file |
| `DraftJournal` | replaced by `Journal` (format of 2.3 (c)); its atomic `replace` helper is kept for snapshots and session metadata |
| `ProjectSessions` | kept; gains "hidden", stops deleting sessions that hold journals; lists a closed folder's unsaved paths from journal headers (NAVIGATION 8.3), which is what makes its `unsaved` flag true only for real journals |
| `Projects` | kept; becomes the registry of live sessions and the owner of the queue; opens unavailable stores and document locators |

## 7. A path of small pull requests

Each compiles and is tested by itself. Text-losing findings first; the journal format changes only after the owner exists, so it is a contained swap.

| PR | Content | Closes | Tests that matter |
| --- | --- | --- | --- |
| 1 | Test tools only: `MemoryProjectFiles` gets content-hash revisions (finding 32); a `Steps` helper with separately stepped worker and main queues | 32 | — |
| 2 | `FileRecord` inside `OpenProject`: base moves from `FileTexts.bases` into the record; tickets; one operation per file; opens joined; text replaced only if untouched (R3 to R7). Journal format untouched | 1, 3, 17 | the reviewer's probes as traces: open / outside write / open again / deliver first / edit / save must conflict; reload / type / deliver must keep the typing; listener that edits during adoption |
| 3 | Reload and copy stop deleting: journal removed only after adoption; copy completion no longer reloads the source (the sibling copy stays as it is until PR 11) | 2, 4 | reload with storage gone keeps the draft on disk; copy / type / deliver keeps the later text |
| 4 | Flush owner: internal timer, acknowledgement, FAILED state and retry, `flush(done)`; `SerialQueue` made by `Projects`; main no longer touches `EditingSession` (worker-owned, results as values) | 5, 18, 21, 22 | journal directory unwritable, then repaired: second flush writes; no flush is acknowledged before its write |
| 5 | Consent and outcomes: `resolve(path, choice, seen)`; Keep = base := seen, `kept`; distinct check outcomes and S4 | 7, 16 | keep over R1, outside writes R2, explicit save conflicts; deleted clean file is not built |
| 6 | `Projects` as registry: one live instance per id; remove hides and keeps journals; open with unavailable store; document locators and "entry is this file" | 6, 10, 11, 35 | open twice returns the same object; remove recent keeps the journal; journal intact + store gone opens and lists the unsaved file |
| 7 | Generation for selection and builds; selection after refresh adoption; `close()` | 12, 13, 14 | the reviewer's three traces |
| 8 | `Journal` in the new format replaces `DraftJournal`, `EditingSession` and `DraftFiles` are deleted; no sync per flush, sync in `flush(done)`; unloaded unsaved files cost no memory | 20 (journal half), 33 | append / kill at every record boundary / reopen (a loop over truncation points, one test); bad checksum in tail and in snapshot; append with wrong `from` is answered with "snapshot" |
| 9 | Byte budget replaces `KEPT_CLEAN`; modified documents may be dropped; runnable fact off the main thread; bounded reads in the stores; revision re-check before rename | 19, 20, 34, 36 | budget holds after undo-to-clean; drop and reopen of a modified file gives the same text |
| 10 | History file and `Document` export/restore (if D1 is yes) | — | round trip; hash mismatch is ignored; undo after reopen equals undo before drop |
| 11 | `copyTo(destination, paths)` replaces the sibling copy | 9 | copy leaves source records untouched; partial failure reported per path |

**The interleavings are enumerated, not picked.** One test runs every order of a few steps out of: the user types, the flush timers fire, the worker runs, the worker runs while nothing can be written, main runs, reload, save, flush(done), close, and the process dies and the project opens again. Steps that change nothing where they stand are left out, as is the same user step twice in a row. It starts from four states: a saved file (up to five steps, without reload); the same where everything typed holds a character no file can hold (four); unsaved text the journal holds (four); and that text replaced by a reload whose history could not be written, so its journal waits (four). "Nothing can be written" is journal and history in the first two, the history alone in the last two. After every sequence the process dies and the project is opened anew; what opens must be a text the file had since the project last told that all was held or saved, or one a reload replaced since; exactly the current text where it told so; the text flush(done) answered for without a reason must be in the journal at that moment; a document that counts as saved must be what opening the stored file gives; text a reload replaced after it was held must be there again, as it is or by undo, and say that a reload replaced it when it comes back as it is; and no text says so that no reload was asked for. 8,567 sequences, counted exactly per start, so a change in what is possible shows; about ten seconds. What it leaves out: more than one file, files leaving memory, changes made elsewhere, damaged journals, and reload from a saved file while the journal cannot be written.

Tests that are not worth writing **[judgement]**: multithreaded stress of the queue (R2 makes it serial by type), tests of each getter of the snapshot, format tests beyond round trip and damage, and anything that needs a device for this layer. Power loss and real providers stay manual, as the review lists under "not checked".

PRs 2 to 5 are where the risk is; each is small because `FileRecord` has no I/O and its tests are sequences of calls with no queue at all. The stepped-queue traces are needed only at the `OpenProject` level, one per reviewed finding.

## 8. Open decisions for the owner

**D1. Does undo survive app restarts?**
- (a) *Recommended:* yes, through the history file, written on drop and on background, ignored on hash mismatch. Cost: PR 10, about 250 lines with tests; makes dropping documents free for the user. A foreground crash still loses undo, not text.
- (b) No in the first version. Cost: none now; undo is lost whenever Android kills the app in the background or a file is dropped by the budget, so the budget should then keep modified documents in memory as today.
- (c) One log for journal and undo. Undo survives crashes too. Cost: the largest and the riskiest; couples recovery to undo grouping. Not recommended.

**D2. The journal design.**
- (a) *Recommended:* snapshot + change records, self-contained, no sync per flush, sync on background (2.3 (c)).
- (b) Keep whole-text snapshots, add pacing and acknowledgement only. Least code; fine up to about 100 KB files, with a sync at every flush; for large files either gigabytes a day or a longer loss window. Can be replaced by (a) later without touching anything outside the worker.
- (c) Change log against the stored file's hash with a fallback draft. Fewest bytes; loses unsaved text when the base is gone at restart. Not recommended.
- Sub-decision: sync on every flush as Neovim does by default (720 syncs a day, loses at most 5 s on power loss) or only on background (about 50 a day, loses up to about 30 s on power loss, nothing more on a kill). Recommended: only on background.

**D3. The memory budget.**
- (a) *Recommended:* heap class / 8 within 8 to 32 MB for everything but the shown document; modified documents may be dropped once journaled.
- (b) A fixed 16 MB. One constant; too much on a 64 MB heap.
- (c) Keep counts (modified + N clean). Simple, and unbounded in bytes.

**D4. What "Keep my edits" consents to.**
- (a) *Recommended:* overwriting the revision the dialog showed, at the next explicit save. If the file changes again before that save, the user is asked again. Survives a restart (it is in the journal header).
- (b) Overwriting whatever is there at the next explicit save (today's behaviour). One question fewer in a rare case; can silently destroy an outside edit nobody saw.
- (c) As (a), but not remembered across restarts. Slightly less code; asks again after every restart.

**D5. What Save a copy is in the first version.**
- (a) *Recommended:* one core operation `copyTo(destination, paths)` that never touches the source; the screen picks destination and paths (running shader's closure) and switches afterwards, as UX 5 says. Cost: PR 11 in the core; the picker flow is shared with New.
- (b) Keep the sibling copy ("x copy.glsl" next to the file) under an honest name for conflicts only, and do the folder copy later. Cheapest; does nothing for read-only or unavailable folders, which is where UX 5 needs it.
- (c) No Save a copy in the first version; conflicts offer Reload and Keep only. Cheapest of all; the journal still keeps the text, but a read-only folder is then a dead end until the user chooses a folder again.

**D6. A clean file that was deleted elsewhere while shown.** (a) *Recommended:* keep showing its text with a notice, do not build it, Save re-creates it. (b) Close it. (a) costs state S4; (b) can surprise by closing what the user is looking at.

**D7. Large files.** Whether files above about 1 MB open without whole-file analysis, and where the editor refuses an edit that would grow a text past 16 MiB. Outside this design; needed before the budget can be trusted on small heaps.

## 9. Not verified

- Nothing ran on a device or emulator. Every time is from the desktop JVM; the phone factor is a guess.
- Flash and file-system behaviour: physical bytes per append, the cost of a sync, how long unsynced data stays in the page cache, device endurance. All from memory. The conclusion "wear does not decide this" would survive being wrong by a factor of ten.
- An external app that keeps a file descriptor it got through the app's document provider writes to the old file after a save replaced it by rename; those writes are lost. Accepted limit of saving atomically; the provider does not coordinate writers.
- A journal in the format before this one is deleted when a project opens; that format was never in a release.
- That written-but-unsynced data survives a process kill is standard kernel behaviour and I did not test it on Android; that an in-place overwrite of the journal's first block is atomic under power loss is an assumption (the header has its own checksum, and the text does not depend on it).
- Whether the directory must be synced after a journal is created or renamed for it to survive power loss, and how to do that on API 23. Today's `DraftJournal` does not do it either.
- That `onStop` always runs before Android may kill the process, and how long it may wait for the flush without an ANR. From memory.
- Heap class on the smallest devices (64 MB assumed) and string storage on Android 6 and 7 (two bytes a character assumed).
- Neovim: the documentation and the comments of `memline.c` and `undo.c` were fetched and read today; I did not run it. That it rewrites the whole undo file on each write is from memory.
- I read `OpenProject`, `FileTexts`, `EditingSession`, `DraftFiles`, `DraftJournal`, `Document`, parts of `Text`, `Projects`, `ProjectFiles` and `DirectoryProjectFiles.write`. I did not read `SafProjectFiles`, `DocumentProjectFiles`, `DatabaseProjectFiles`, `ProjectSessions` in full, `UserFilesProvider` or the `navigation` package beyond what the review and NAVIGATION 8.2/8.3 say about them; the claims about them come from the review.
- The line counts for new code are estimates.
- The reviewer's probes were read as described in the review, not re-run.

## Decisions of 2026-11-13

The owner decided every question of section 8; where he wrote text it is quoted. Section 8 is kept as the record of the options.

| # | Decision | Taken |
| --- | --- | --- |
| D1 | Does undo survive app restarts | Yes, through the history file: written when a document leaves memory and when the app goes to the background, ignored on any mismatch |
| D2 | The journal | A snapshot plus change records per unsaved file, self-contained; flushed 2 to 5 s after typing; synced only when the app goes to the background |
| D3 | The memory budget | One eighth of the heap class, within 8 to 32 MB, for everything but the shown document; modified documents may be dropped once journaled |
| D4 | What "Keep my edits" consents to | Overwriting the revision the dialog showed, at the next explicit save, remembered across restarts; a later outside change asks again |
| D5 | Save a copy | One core operation `copyTo(destination, paths)` that never touches the source |
| D6 | A clean file deleted elsewhere while shown | Keep showing its text with a notice; it is not built; Save re-creates it |
| D7 | Large files | Whole-file analysis runs at every size (it is bounded in memory and paced). **One fixed size limit of 16 MiB on every device, for opening and for editing**: an edit that would grow a text past it is refused with a message and the text stays as it was |

On D7 the owner asked about a limit derived from the device's memory: "would this make some files editable by some devices and block on others? E.g. someone shares a shader with someone that has a lower-end phone. Would that be expected?" It would not be expected, so the limit is the same number everywhere. A device that cannot hold a text below the limit reports that it is too large for this device instead of crashing.

Two statements of this document correct what was said before it: Neovim syncs after each swap write by default (not syncing per flush is this design's choice), and flash wear does not decide the journal design.
