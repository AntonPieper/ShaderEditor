package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;

/**
 * What the project knows of one file, on the main thread: its document
 * while it is in memory, the stored revision its text was last equal
 * to, the one operation on storage in flight for it, and what its
 * journal is. It does no I/O: an operation starts as a {@link Request},
 * a write of the journal as a {@link Flush} the worker completes by
 * itself, and what storage gave is adopted only for the request still
 * current, so text and base change together, before the listeners of
 * the document hear. Nobody else writes what it knows of the journal:
 * each change of it is a method here.
 */
final class FileRecord {
	enum Kind {
		OPEN, RELOAD, SAVE, CHECK, MOVE
	}

	/**
	 * An operation on storage: its ticket, the document when it started,
	 * null when not in memory, with its revision and state then, and the
	 * base it expects.
	 */
	record Request(Kind kind, int ticket, Document document, int revision,
			int state, String base) {
	}

	/**
	 * What the journal of the file is to do, in one task of the worker:
	 * hold text at revision to, by appending changes when it is at
	 * revision from and by a snapshot with header otherwise, or, when
	 * header is null, go; to also tells the writes apart.
	 */
	record Flush(Journal.Header header, Text text, int from, int to,
			List<Document.Change> changes) {
		boolean removes() {
			return header == null;
		}
	}

	/**
	 * What the journal file of the path is, as the last write sent
	 * leaves it; {@link #settled} tells whether the worker said so too.
	 */
	enum Journaled {
		/** There is none. */
		NONE,
		/** It holds the unsaved text of the file. */
		TEXT,
		/**
		 * One is there that could not be read. It stays as it is until
		 * the user gives it up or types, which writes a new one over it.
		 */
		UNREADABLE,
		/**
		 * It holds text that storage holds too, of a file not in memory,
		 * and goes by the next write.
		 */
		SAVED
	}

	/**
	 * Text a reload replaced, kept beside the journal under number, and
	 * the state of the document that was this text: while undo or redo
	 * lead there, it is kept.
	 */
	record Replaced(int number, int state) {
	}

	// Where the file is; a rename gives it another path.
	ProjectPath path;
	// What the view keeps for the document, and the tickets of the
	// timers that flush it.
	Object state;
	Document.Listener listener;
	int timer;
	int limit;
	// What storage was last seen to hold, compared with the base, and
	// its revision then, null when none is known. An observation: only
	// taking the stored text, a save or the user's consent make it the
	// base.
	FileTexts.Found found = FileTexts.Found.SAME;
	String seen;
	// How many callers are about to be told that the document is there.
	int awaited;
	// Whether the text at revision runs, and the revision asked for.
	OpenProject.Runs runs = OpenProject.Runs.UNKNOWN;
	int runsRevision = -1;
	int runsAsked = -1;
	// The revision and the selection of the document its history was
	// kept at.
	int historyAt;
	Document.Selection historySelection;

	private final List<Runnable> waiting = new ArrayList<>();
	private Document document;
	private String base;
	private Request op;
	private int tickets;
	// Whether the user chose to keep the unsaved text over the base,
	// so only an explicit save writes it.
	private boolean kept;
	// What the journal file is; why it could not be read, and whether
	// reading it again would find the same, while UNREADABLE.
	private Journaled journal = Journaled.NONE;
	private String unreadable;
	private boolean damaged;
	// Why the last write of the journal failed, or null.
	private String failure;
	// The write the journal continues from, -1 when it starts again
	// with a snapshot; the last write sent and the last acknowledged;
	// the revision of the document as last sent; and its changes since.
	private int at = -1;
	private int writes;
	private int acked;
	private int flushed;
	private List<Document.Change> changes = new ArrayList<>();
	// While the file is being deleted, the revision of the document
	// whose text goes with it, -1 without a document; else KEPT.
	private static final int KEPT = Integer.MIN_VALUE;
	private int discarding = KEPT;
	// The texts reloads replaced that undo or redo of the document
	// still lead to, oldest first; and the revision the document was
	// opened at.
	private final List<Replaced> replaced = new ArrayList<>();
	private int opened;

	FileRecord(ProjectPath path) {
		this.path = path;
	}

	/** Return the document, or null when it is not in memory. */
	Document document() {
		return document;
	}

	/**
	 * Return the revision the text was last equal to in storage, or
	 * null when unknown or there was no file.
	 */
	String base() {
		return base;
	}

	/**
	 * Return the revision a save expects storage to hold: the base, or
	 * none for a file without unsaved text that storage was seen not to
	 * hold, which a save creates again.
	 */
	String expected() {
		return !edited() && found == FileTexts.Found.MISSING ? null : base;
	}

	/**
	 * Return what names the text the file has now for a deletion: the
	 * revision of its document, -1 when it is not in memory.
	 */
	int stamp() {
		return document != null ? document.revision() : -1;
	}

	/**
	 * The file is being deleted with the text it has now, when deleting:
	 * until that is over, this text is not written to the journal again,
	 * whoever asks; text typed from now on is.
	 */
	void discard(boolean deleting) {
		discarding = deleting ? stamp() : KEPT;
	}

	/**
	 * Return whether the file is being deleted and still has the text
	 * the deletion is for.
	 */
	boolean discarded() {
		return discarding != KEPT && discarding == stamp();
	}

	/**
	 * The stored file and the journal are gone, and the document has
	 * text typed since the deletion was asked for: it is unsaved text
	 * of a file that is not there, to be kept in a new journal.
	 */
	void orphan() {
		discarding = KEPT;
		journal = Journaled.NONE;
		unreadable = null;
		at = -1;
		replaced.clear();
		// Deleted here, not elsewhere: the text is of no file.
		base = null;
		kept = false;
		see(FileTexts.Found.MISSING, null);
	}

	/**
	 * The stored file and the journal are gone with the text the
	 * deletion was for: nothing is left to keep.
	 */
	void deleted() {
		discarding = KEPT;
		journal = Journaled.NONE;
		unreadable = null;
		replaced.clear();
	}

	/** Return the operation in flight, or null. */
	Request pending() {
		return op;
	}

	/**
	 * Return whether the file has unsaved text to save: its document is
	 * modified, or, when not in memory, it has a journal.
	 */
	boolean edited() {
		return document != null
				? document.isModified()
				: journal == Journaled.TEXT;
	}

	/**
	 * Return whether the file is not in memory and has a journal,
	 * also one that cannot be read: what is built reads it there.
	 */
	boolean journaled() {
		return document == null && (journal == Journaled.TEXT ||
				journal == Journaled.UNREADABLE);
	}

	/**
	 * Return why the journal of the file could not be read, else null;
	 * {@link #damaged} tells whether it can be given up.
	 */
	String unreadable() {
		return journal == Journaled.UNREADABLE ? unreadable : null;
	}

	/**
	 * Return whether the journal that could not be read is damaged, so
	 * that reading it again finds the same; else the device had no
	 * memory for it, and it may be whole.
	 */
	boolean damaged() {
		return journal == Journaled.UNREADABLE && damaged;
	}

	/** Return why the last write of the journal failed, or null. */
	String failure() {
		return failure;
	}

	/**
	 * Return whether the user chose to keep the unsaved text over the
	 * base, so only an explicit save writes it.
	 */
	boolean kept() {
		return kept;
	}

	/**
	 * Return whether the file counts as unsaved: it is edited, or it had
	 * unsaved text that could not be read.
	 */
	boolean unsaved() {
		return journal == Journaled.UNREADABLE || edited();
	}

	/**
	 * Return whether the record holds anything to keep: a document, an
	 * operation, a journal, a write of it on its way or why one could
	 * not be read.
	 */
	boolean needed() {
		return document != null || op != null ||
				journal != Journaled.NONE || acked != writes;
	}

	/**
	 * Return why storage does not hold what the unsaved text is based on
	 * anymore, as last seen, or null when it does, cannot be read or
	 * there is no unsaved text.
	 */
	String conflict() {
		if (!edited()) {
			return null;
		}
		return switch (found) {
			case SAME, UNAVAILABLE -> null;
			case MISSING -> base != null ? "the file was deleted" : null;
			default -> base == null
					? "the file exists"
					: "the file has changed";
		};
	}

	/**
	 * Return whether the journal acknowledged everything sent and holds
	 * the document as it is: its text when modified, nothing when not.
	 */
	boolean settled() {
		if (acked != writes || failure != null || document == null) {
			return acked == writes && failure == null;
		}
		return document.isModified()
				? at >= 0 && document.revision() == flushed
				: journal == Journaled.NONE ||
						journal == Journaled.UNREADABLE;
	}

	/**
	 * Take what the journal header of a file not in memory tells, its
	 * base and whether the user kept the text over it, and what storage
	 * was found to hold, at revision.
	 */
	void recover(Journal.Header header, FileTexts.Found found,
			String revision) {
		base = header.base();
		kept = header.kept();
		journal = Journaled.TEXT;
		see(found, revision);
	}

	/**
	 * The journal of the file could not be read, for reason: it is left
	 * as it is. damaged tells that reading it again finds the same.
	 */
	void unread(String reason, boolean damaged) {
		journal = Journaled.UNREADABLE;
		unreadable = reason;
		this.damaged = damaged;
		at = -1;
	}

	/**
	 * The user gave up the journal that could not be read, and it is
	 * gone.
	 */
	void dismissed() {
		if (journal == Journaled.UNREADABLE) {
			journal = Journaled.NONE;
			unreadable = null;
		}
	}

	/**
	 * Take whether the journal of the file, not in memory, kept the
	 * header that names the base and the consent as they are now.
	 */
	void headerKept(boolean kept) {
		failure = kept ? null : "the journal cannot be written";
	}

	/**
	 * Run start once no operation is in flight, now when none is; start
	 * is expected to {@link #start} one.
	 */
	void whenIdle(Runnable start) {
		if (op == null) {
			start.run();
		} else {
			waiting.add(start);
		}
	}

	/** Start an operation of kind; none may be in flight. */
	Request start(Kind kind) {
		if (op != null) {
			throw new IllegalStateException(op.kind() + " in flight");
		}
		op = new Request(kind, ++tickets, document,
				document != null ? document.revision() : -1,
				document != null ? document.state() : -1, base);
		return op;
	}

	/**
	 * End request if it is the current one and start what waited for
	 * it; return whether it was current.
	 */
	boolean end(Request request) {
		if (request != op) {
			return false;
		}
		op = null;
		while (op == null && !waiting.isEmpty()) {
			waiting.remove(0).run();
		}
		return true;
	}

	/**
	 * Take document, opened from storage at revision, or from the
	 * journal at write journal based on it, -1 for none, for request;
	 * replaced are the texts reloads replaced that its history leads
	 * to. Return whether it was taken. Opened from storage, the file
	 * has no journal, unless {@link #unread} tells of one.
	 */
	boolean adoptOpened(Request request, Document document,
			String revision, int journal, List<Replaced> replaced) {
		if (request != op || this.document != null) {
			return false;
		}
		this.document = document;
		base = revision;
		if (journal < 0) {
			see(FileTexts.Found.SAME, revision);
		}
		this.journal = journal >= 0 ? Journaled.TEXT : Journaled.NONE;
		unreadable = null;
		at = journal;
		writes = acked = Math.max(journal, 0);
		flushed = opened = document.revision();
		changes = new ArrayList<>();
		this.replaced.clear();
		this.replaced.addAll(replaced);
		return true;
	}

	/**
	 * Return the journal write that holds the unsaved text of the
	 * document as it is now, as sent, else -1.
	 */
	int holding() {
		return document != null && document.isModified() &&
				flushed == document.revision()
				? at
				: -1;
	}

	/**
	 * Return the revision the document had when it was opened, before
	 * anyone was told of it.
	 */
	int opened() {
		return opened;
	}

	/**
	 * Give the document text, stored at revision, as one change that
	 * can be undone, keeping the selection where it can; only when the
	 * document is still asked, at revision at: as it was when the user
	 * asked for the stored text, or when a check started. Unsaved text
	 * goes from the screen only when its journal, as the write held
	 * left it, was put aside before, under the number aside; -1 for a
	 * document without unsaved text. Return whether it took the text.
	 */
	boolean adoptStored(Request request, Document asked, int at, Text text,
			String revision, int aside, int held) {
		if (request != op || document == null || document != asked ||
				document.revision() != at ||
				document.isModified() != aside >= 0) {
			return false;
		}
		Document.Selection selection = document.selection();
		if (aside >= 0) {
			replaced.add(new Replaced(aside, document.state()));
			// The file has no journal now, unless one was sent since,
			// as a fence does: that one goes by the next write.
			if (this.at == held) {
				journal = Journaled.NONE;
			}
			this.at = -1;
		}
		document.beginBatch();
		document.replace(0, document.text().length(), text);
		document.select(Math.min(selection.anchor(), text.length()),
				Math.min(selection.head(), text.length()));
		document.markSaved(document.state());
		base = revision;
		see(FileTexts.Found.SAME, revision);
		kept = false;
		document.endBatch();
		return true;
	}

	/**
	 * Take what request found storage to hold, at revision, null when
	 * none is known. It is seen, not taken: the base stays, and unsaved
	 * text on another base is in conflict. Return whether request was
	 * current.
	 */
	boolean observe(Request request, FileTexts.Found found,
			String revision) {
		if (request != op) {
			return false;
		}
		see(found, revision);
		return true;
	}

	// Storage holds found at revision, compared with the base as it is
	// now.
	private void see(FileTexts.Found found, String revision) {
		boolean text = found == FileTexts.Found.SAME ||
				found == FileTexts.Found.CHANGED;
		this.found = !text ? found : Objects.equals(revision, base)
				? FileTexts.Found.SAME
				: FileTexts.Found.CHANGED;
		seen = revision;
	}

	/**
	 * Base the unsaved text on revision, null for none, as the user
	 * consented to overwrite it at the next explicit save.
	 */
	void consent(String revision) {
		base = revision;
		kept = true;
		// No text the document had is what storage holds there.
		if (document != null) {
			document.markSaved(-1);
		}
		see(revision != null
				? FileTexts.Found.SAME
				: FileTexts.Found.MISSING, revision);
		at = -1;
	}

	/**
	 * Take that storage holds the text request started with at revision:
	 * a save wrote it, or a check found exactly it there. Return whether
	 * request was current.
	 */
	boolean adoptSaved(Request request, String revision) {
		if (request != op || document != request.document()) {
			return false;
		}
		base = revision;
		see(FileTexts.Found.SAME, revision);
		kept = false;
		// The journal header names the new base.
		at = -1;
		if (document != null) {
			document.markSaved(request.state());
		} else if (journal == Journaled.TEXT) {
			// Its journal is the text stored, and goes by the next flush.
			journal = Journaled.SAVED;
		}
		return true;
	}

	/** Keep change of the document for the next append. */
	void collect(Document.Change change) {
		changes.add(change);
	}

	/**
	 * Return what the journal is to write to hold the text of the
	 * document, or to go when the file has no unsaved text anymore, or
	 * null when it is as it should be; it counts as sent.
	 */
	Flush flush() {
		return flush(false);
	}

	/**
	 * Return what {@link #flush()} returns; at a fence, where the text
	 * is to be held whatever happens to the answers on their way, also
	 * when the last write sent is not acknowledged: it may have failed
	 * and the failure may never be heard, so what the journal is to be
	 * is sent again, the text whole.
	 */
	Flush flush(boolean fence) {
		if (discarded()) {
			return null;
		}
		boolean unsure = fence && acked != writes;
		if (document == null) {
			// The last write of a file not in memory is the removal of
			// a journal whose text storage holds.
			if (journal != Journaled.SAVED &&
					!(unsure && journal == Journaled.NONE)) {
				return null;
			}
			journal = Journaled.NONE;
			return new Flush(null, null, -1, ++writes, List.of());
		}
		int revision = document.revision();
		List<Document.Change> changed = changes;
		// Undone to the text saved before while a save of another text
		// is on its way, the document has what storage is about to
		// lose: it is unsaved text until main hears of that save.
		boolean saving = op != null && op.kind() == Kind.SAVE &&
				op.state() != document.state();
		if (!document.isModified() && !saving) {
			changes = new ArrayList<>();
			flushed = revision;
			// One that cannot be read is not this record's to remove.
			if (journal == Journaled.UNREADABLE ||
					journal == Journaled.NONE && !unsure) {
				return null;
			}
			journal = Journaled.NONE;
			at = -1;
			return new Flush(null, null, -1, ++writes, List.of());
		} else if (at >= 0 && revision == flushed && !unsure) {
			return null;
		}
		changes = new ArrayList<>();
		flushed = revision;
		// Typed over one that could not be read: the snapshot takes its
		// place.
		journal = Journaled.TEXT;
		unreadable = null;
		int from = unsure ? -1 : at;
		at = ++writes;
		return new Flush(new Journal.Header(path, base, kept,
				kept ? base : null), document.text(), from, writes, changed);
	}

	/**
	 * Take whether the journal did what flush asked. Only the answer to
	 * the last write sent tells how the journal is: the text counts as
	 * held once that write, which names its revision, is acknowledged.
	 * An earlier write that failed counts as a failure until then.
	 */
	void acknowledge(Flush flush, boolean done) {
		if (flush.to() != writes) {
			// Until the last one sent is answered, one that failed is
			// not forgotten: nobody is told the text is held meanwhile.
			if (!done) {
				failure = "the journal cannot be written";
			}
			return;
		} else if (done) {
			acked = writes;
			failure = null;
			return;
		}
		at = -1;
		if (flush.removes() && journal == Journaled.NONE) {
			// It is still there, with the text it had.
			journal = Journaled.TEXT;
		}
		failure = "the journal cannot be written";
	}

	/**
	 * The journal was put aside for a reload that did not take the
	 * stored text after all, and is put back: it holds what was sent
	 * before, so whatever was typed since is written whole.
	 */
	void putBack() {
		at = -1;
	}

	/**
	 * Return the numbers of the texts reloads replaced that neither
	 * undo nor redo of the document lead to anymore, and forget them:
	 * nothing is left to keep them for.
	 */
	List<Integer> unreachable() {
		if (replaced.isEmpty() || document == null) {
			return List.of();
		}
		Document.History history = document.history();
		List<Integer> gone = new ArrayList<>();
		for (int i = replaced.size(); i-- > 0; ) {
			int state = replaced.get(i).state();
			boolean reached = document.state() == state;
			for (Document.History.Step step : history.undo()) {
				reached |= step.stateBefore() == state;
			}
			for (Document.History.Step step : history.redo()) {
				reached |= step.stateAfter() == state;
			}
			if (!reached) {
				gone.add(replaced.remove(i).number());
			}
		}
		return gone;
	}

	/**
	 * Forget the document and return it. What reloads replaced stays
	 * where it is kept, for the history the file is opened with next.
	 */
	Document drop() {
		Document dropped = document;
		document = null;
		replaced.clear();
		return dropped;
	}
}
